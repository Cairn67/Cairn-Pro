// SPDX-License-Identifier: GPL-3.0-only
// Offline kallsyms discovery, ported from adapt/symbol_layout.py + discover_symbols.py.
//

// Resolves kernel symbols from the image.
#![allow(clippy::needless_range_loop)]

#[derive(Debug, PartialEq, Eq)]
pub enum SymError {
    NotArm64,
    TooLarge,
    Truncated,
    Header,
    NoTokens,
    TooManyTokens,
    NoLayout,
    AmbiguousLayout(String),
    BadLayout(&'static str),
    Budget,
}

impl core::fmt::Display for SymError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            SymError::NotArm64 => write!(f, "not an uncompressed ARM64 Image"),
            SymError::TooLarge => write!(f, "image exceeds the scan budget"),
            SymError::Truncated => write!(f, "truncated image"),
            SymError::Header => write!(f, "malformed section header"),
            SymError::NoTokens => write!(f, "no kallsyms token table found"),
            SymError::TooManyTokens => write!(f, "too many token tables"),
            SymError::NoLayout => write!(f, "no consistent symbol layout"),
            SymError::AmbiguousLayout(s) => write!(f, "{}", s),
            SymError::BadLayout(s) => write!(f, "{}", s),
            SymError::Budget => write!(f, "discovery budget exhausted"),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Family {
    
    PostTokenImageRelativeU32,
    
    PreCountUnsignedRelativeU32,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Symbols {
    pub family: Family,
    pub count: usize,
    
    pub count_at: usize,
    pub addresses: Vec<u32>,
    pub names: Vec<String>,
}

impl Symbols {
    
    pub fn addr(&self, name: &str) -> Option<u32> {
        self.names
            .iter()
            .position(|n| n == name)
            .map(|i| self.addresses[i])
    }

    pub fn addr_of(&self, bare: &str) -> Option<u32> {
        self.names
            .iter()
            .position(|n| n.get(1..) == Some(bare))
            .map(|i| self.addresses[i])
    }

    pub fn addrs_of(&self, bare: &str) -> Vec<u32> {
        self.names
            .iter()
            .enumerate()
            .filter(|(_, n)| n.get(1..) == Some(bare))
            .map(|(i, _)| self.addresses[i])
            .collect()
    }

    pub fn enclosing(&self, site: u32) -> Option<(&str, u32)> {
        if self.addresses.is_empty() || site < self.addresses[0] {
            return None;
        }
        let idx = match self.addresses.binary_search(&site) {
            Ok(i) => i,
            Err(i) => i - 1,
        };
        Some((&self.names[idx], site - self.addresses[idx]))
    }
}

fn u16_at(d: &[u8], o: usize) -> Option<u16> {
    d.get(o..o + 2).map(|s| u16::from_le_bytes([s[0], s[1]]))
}

fn u32_at(d: &[u8], o: usize) -> Option<u32> {
    d.get(o..o + 4)
        .map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

fn u64_at(d: &[u8], o: usize) -> Option<u64> {
    d.get(o..o + 8).map(|s| {
        u64::from_le_bytes([s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]])
    })
}

fn cstr(d: &[u8], start: usize, limit: usize) -> Option<&[u8]> {
    let end = d.get(start..limit.min(d.len()))?.iter().position(|b| *b == 0)? + start;
    Some(&d[start..end])
}

fn token_tables(d: &[u8]) -> Result<Vec<(usize, usize, Vec<&[u8]>)>, SymError> {
    let mut anchors = Vec::new();
    let needle = b"__ksymtab\0";
    let mut at = 0usize;
    while let Some(p) = find_from(d, needle, at) {
        anchors.push(p);
        if anchors.len() > 64 {
            return Err(SymError::TooManyTokens);
        }
        at = p + 1;
    }
    let mut out = Vec::new();
    for &anchor in &anchors {
        let from = (anchor + 1) & !1;
        let to = (anchor + 5000).min(d.len().saturating_sub(512));
        let mut idx = from;
        while idx + 512 <= d.len() && idx < to {
            if u16_at(d, idx) != Some(0) {
                idx += 2;
                continue;
            }
            
            let mut offsets = [0u16; 256];
            let mut ok = true;
            for i in 0..256 {
                match u16_at(d, idx + i * 2) {
                    Some(v) => offsets[i] = v,
                    None => {
                        ok = false;
                        break;
                    }
                }
                if i > 0 && offsets[i] <= offsets[i - 1] {
                    ok = false;
                    break;
                }
            }
            if !ok || !(200 < offsets[255] as usize && offsets[255] < 5000) {
                idx += 2;
                continue;
            }
            for tail in 1..40usize {
                let last = offsets[255] as usize;
                if idx < last + tail {
                    continue;
                }
                let table = idx - last - tail;
                let mut tokens: Vec<&[u8]> = Vec::with_capacity(256);
                let mut good = true;
                for i in 0..256 {
                    let start = table + offsets[i] as usize;
                    let limit = if i < 255 {
                        table + offsets[i + 1] as usize
                    } else {
                        idx
                    };
                    match cstr(d, start, limit) {
                        Some(t) if !t.is_empty() && t.iter().all(|c| (32..127).contains(c)) => {
                            if i < 255 && start + t.len() + 1 != limit {
                                good = false;
                                break;
                            }
                            tokens.push(t);
                        }
                        Some(_) => {
                            good = false;
                            break;
                        }
                        None => {
                            good = false;
                            break;
                        }
                    }
                }
                if good && table <= anchor && anchor < idx && d[table + last + tokens[255].len() + 1..idx].iter().all(|b| *b == 0) {
                    
                    if !out.iter().any(|(t, i, _)| *t == table && *i == idx) {
                        out.push((table, idx, tokens));
                        if out.len() > 16 {
                            return Err(SymError::TooManyTokens);
                        }
                    }
                }
            }
            idx += 2;
        }
    }
    if out.is_empty() {
        return Err(SymError::NoTokens);
    }
    Ok(out)
}

fn find_from(d: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if needle.is_empty() || from >= d.len() {
        return None;
    }
    d[from..]
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| p + from)
}

fn decode_names(
    d: &[u8],
    count_at: usize,
    width: usize,
    table: usize,
    tokens: &[&[u8]],
    n: usize,
) -> Result<(Vec<String>, Vec<u32>, usize), SymError> {
    let mut p = count_at + width;
    let mut names = Vec::with_capacity(n);
    let mut positions = Vec::with_capacity(n);
    for _ in 0..n {
        positions.push(u32::try_from(p - count_at - width).map_err(|_| SymError::BadLayout("name index overflow"))?);
        if p >= table || p >= d.len() {
            return Err(SymError::Truncated);
        }
        let mut size = d[p] as usize;
        p += 1;
        if size & 128 != 0 {
            if p >= table || p >= d.len() {
                return Err(SymError::BadLayout("truncated long name"));
            }
            size = (size & 127) | ((d[p] as usize) << 7);
            p += 1;
        }
        if size == 0 || p + size > table || p + size > d.len() {
            return Err(SymError::BadLayout("invalid name size"));
        }
        let mut name = String::with_capacity(size);
        for &t in &d[p..p + size] {
            let tok = tokens.get(t as usize).ok_or(SymError::BadLayout("token index out of range"))?;
            name.push_str(
                core::str::from_utf8(tok).map_err(|_| SymError::BadLayout("token is not ASCII"))?,
            );
        }
        let bytes = name.as_bytes();
        if bytes.len() < 2
            || !matches!(bytes[0], b'T' | b't' | b'D' | b'd' | b'B' | b'b' | b'R' | b'r'
                | b'A' | b'a' | b'W' | b'w' | b'V' | b'v' | b'N' | b'n' | b'U' | b'u'
                | b'S' | b's' | b'I' | b'i' | b'?')
            || !bytes.iter().all(|c| (33..127).contains(c))
        {
            return Err(SymError::BadLayout("invalid symbol name"));
        }
        names.push(name);
        p += size;
    }
    Ok((names, positions, p))
}

fn validate(
    d: &[u8],
    count_at: usize,
    width: usize,
    table: usize,
    index: usize,
    tokens: &[&[u8]],
) -> Result<Symbols, SymError> {
    if d.len() < 64 || &d[56..60] != b"ARM\x64" {
        return Err(SymError::NotArm64);
    }
    if width != 4 && width != 8 {
        return Err(SymError::BadLayout("unsupported count width"));
    }
    let n = if width == 4 {
        u32_at(d, count_at).ok_or(SymError::Truncated)? as usize
    } else {
        u64_at(d, count_at).ok_or(SymError::Truncated)? as usize
    };
    if !(20000..=900000).contains(&n) {
        return Err(SymError::BadLayout("symbol count outside budget"));
    }
    if count_at + width >= table || table >= index || index + 512 > d.len() {
        return Err(SymError::BadLayout("invalid section order"));
    }
    
    let mut prev = 0u16;
    let mut values = [0u16; 256];
    for i in 0..256 {
        let v = u16_at(d, index + i * 2).ok_or(SymError::Truncated)?;
        if i == 0 {
            if v != 0 {
                return Err(SymError::BadLayout("token index must start at zero"));
            }
        } else if v <= prev {
            return Err(SymError::BadLayout("token index is not ascending"));
        }
        prev = v;
        values[i] = v;
    }
    for i in 0..tokens.len() {
        let start = table + values[i] as usize;
        let raw = tokens[i].len() + 1;
        if start + raw > index {
            return Err(SymError::BadLayout("token extends past its index"));
        }
        let tok = tokens[i];
        if d[start..start + tok.len()] != *tok || d[start + tok.len()] != 0 {
            return Err(SymError::BadLayout("token bytes do not match the index"));
        }
        if i < 255 && values[i] as usize + raw != values[i + 1] as usize {
            return Err(SymError::BadLayout("token boundary is not contiguous"));
        }
    }
    let tail = table + values[255] as usize + tokens[255].len() + 1;
    if tail > index || d[tail..index].iter().any(|b| *b != 0) {
        return Err(SymError::BadLayout("token table has non-zero padding"));
    }
    let (names, positions, names_end) = decode_names(d, count_at, width, table, tokens, n)?;

    let mcount = (n + 255) / 256;
    let mut marks: Vec<usize> = Vec::new();
    for align in [4usize, 8] {
        let m = (names_end + align - 1) & !(align - 1);
        if m + mcount * 4 > table {
            continue;
        }
        let matches = (0..mcount).all(|i| {
            u32_at(d, m + i * 4) == Some(positions[i * 256])
        });
        if matches && d[names_end..m].iter().all(|b| *b == 0) && !marks.contains(&m) {
            marks.push(m);
        }
    }
    if marks.len() != 1 {
        return Err(SymError::BadLayout("nonunique or invalid markers"));
    }
    let marker = marks[0];
    let end = marker + mcount * 4;
    let image = u64_at(d, 16).ok_or(SymError::Truncated)?;

    let mut accepted: Vec<Symbols> = Vec::new();

    if end == table && index + 512 + n * 4 <= d.len() {
        let mut addrs = Vec::with_capacity(n);
        for i in 0..n {
            addrs.push(u32_at(d, index + 512 + i * 4).ok_or(SymError::Truncated)?);
        }
        if plausible(&addrs, image, &names) {
            accepted.push(Symbols {
                family: Family::PostTokenImageRelativeU32,
                count: n,
                count_at,
                addresses: addrs,
                names: names.clone(),
            });
        }
    }

    let seqstart = (end + 7) & !7;
    let seqend = seqstart + n * 3;
    let base_at = count_at.checked_sub(8);
    
    let offsets = base_at.and_then(|b| b.checked_sub(n * 4)).map(|o| o & !7usize);
    if (seqend + 7) & !7 == table {
        if let (Some(base_at), Some(offsets)) = (base_at, offsets) {
            let pad_ok = d[end..seqstart].iter().all(|b| *b == 0)
                && d[seqend..table].iter().all(|b| *b == 0);
            if pad_ok {
                let seq: Vec<usize> = (0..n)
                    .map(|i| {
                        let o = seqstart + i * 3;
                        ((d[o] as usize) << 16) | ((d[o + 1] as usize) << 8) | d[o + 2] as usize
                    })
                    .collect();
                let mut seen = vec![false; n];
                let mut perm = true;
                for &v in &seq {
                    if v >= n || seen[v] {
                        perm = false;
                        break;
                    }
                    seen[v] = true;
                }
                if perm {
                    let ordered: Vec<&str> = seq
                        .iter()
                        .map(|&i| names[i].get(1..).unwrap_or(""))
                        .collect();
                    let mut sorted = ordered.clone();
                    sorted.sort_unstable();
                    if ordered == sorted {
                        let base = u64_at(d, base_at).ok_or(SymError::Truncated)?;
                        let pad2 = d[offsets + n * 4..base_at].iter().all(|b| *b == 0);
                        if pad2 {
                            let mut vals = Vec::with_capacity(n);
                            for i in 0..n {
                                vals.push(u32_at(d, offsets + i * 4).ok_or(SymError::Truncated)?);
                            }
                            if let Some(first) = vals.first() {
                                let addrs: Vec<u32> =
                                    vals.iter().map(|v| v.wrapping_sub(*first)).collect();
                                let _ = base;
                                if plausible(&addrs, image, &names) {
                                    accepted.push(Symbols {
                                        family: Family::PreCountUnsignedRelativeU32,
                                        count: n,
                                        count_at,
                                        addresses: addrs,
                                        names: names.clone(),
                                    });
                                }
                            }
                        }
                    }
                    drop(ordered);
                }
            }
        }
    }

    if end == table {
        if let Some(base_at) = count_at.checked_sub(8) {
            if let Some(offsets) = base_at
                .checked_sub(n * 4)
                .map(|o| o & !7usize)
            {
                
                let pad_ok = d[offsets + n * 4..base_at].iter().all(|b| *b == 0);
                if pad_ok {
                    let mut vals = Vec::with_capacity(n);
                    for i in 0..n {
                        vals.push(u32_at(d, offsets + i * 4).ok_or(SymError::Truncated)?);
                    }
                    if vals.first() == Some(&0) {
                        if plausible(&vals, image, &names) {
                            accepted.push(Symbols {
                                family: Family::PreCountUnsignedRelativeU32,
                                count: n,
                                count_at,
                                addresses: vals,
                                names: names.clone(),
                            });
                        }
                    }
                }
            }
        }
    }

    match accepted.len() {
        0 => Err(SymError::NoLayout),
        1 => Ok(accepted.remove(0)),
        _ => Err(SymError::AmbiguousLayout(format!(
            "{} address layouts validated; refusing to guess",
            accepted.len()
        ))),
    }
}

fn plausible(addrs: &[u32], image: u64, names: &[String]) -> bool {
    if names.first().map(|n| n.get(1..).unwrap_or("")) != Some("_text") {
        return false;
    }
    if addrs.first() != Some(&0) {
        return false;
    }
    if addrs.iter().any(|&a| u64::from(a) > image) {
        return false;
    }
    if addrs.windows(2).any(|w| w[0] > w[1]) {
        return false;
    }
    
    names
        .iter()
        .zip(addrs)
        .filter(|(n, _)| n.get(1..).unwrap_or("") == "_end")
        .all(|(_, a)| u64::from(*a) == image)
        && names.iter().any(|n| n.get(1..).unwrap_or("") == "_end")
}

pub fn discover(d: &[u8]) -> Result<Symbols, SymError> {
    let all = discover_all(d)?;
    match all.len() {
        1 => Ok(all.into_iter().next().unwrap()),
        0 => Err(SymError::NoLayout),
        n => Err(SymError::AmbiguousLayout(format!(
            "{} validated layouts (count_at {:?}); refusing to guess",
            n,
            all.iter().map(|s| s.count_at).collect::<Vec<_>>()
        ))),
    }
}

pub fn discover_all(d: &[u8]) -> Result<Vec<Symbols>, SymError> {
    const LIMIT: usize = 128 * 1024 * 1024;
    if d.len() < 64 || &d[56..60] != b"ARM\x64" {
        return Err(SymError::NotArm64);
    }
    if d.len() > LIMIT {
        return Err(SymError::TooLarge);
    }
    let tables = token_tables(d)?;
    let mut all: Vec<Symbols> = Vec::new();
    let mut attempts = 0usize;
    for (table, index, tokens) in tables {
        
        let start = table.saturating_sub(8 * 1024 * 1024) & !3;
        let mut at = start;
        while at + 8 < table {
            let n = match u32_at(d, at) {
                Some(v) => v as usize,
                None => break,
            };
            if (20000..=900000).contains(&n) {
                for width in [4usize, 8] {
                    if width == 8 && (at % 8 != 0 || u32_at(d, at + 4) != Some(0)) {
                        continue;
                    }
                    if at < width {
                        continue;
                    }
                    
                    let mut p = at + width;
                    if p >= table || p >= d.len() {
                        continue;
                    }
                    let mut size = d[p] as usize;
                    p += 1;
                    if size & 128 != 0 {
                        if p >= table || p >= d.len() {
                            continue;
                        }
                        size = (size & 127) | ((d[p] as usize) << 7);
                        p += 1;
                    }
                    if size == 0 || size > 64 || p + size > table {
                        continue;
                    }
                    let first: Vec<u8> = d[p..p + size]
                        .iter()
                        .filter_map(|t| tokens.get(*t as usize).copied())
                        .flatten()
                        .copied()
                        .collect();
                    if first != b"T_text" && first != b"t_text" {
                        continue;
                    }
                    attempts += 1;
                    if attempts > 64 {
                        return Err(SymError::Budget);
                    }
                    if let Ok(sym) = validate(d, at, width, table, index, &tokens) {
                        all.push(sym);
                    }
                }
            }
            at += 4;
        }
    }
    if all.is_empty() {
        return Err(SymError::NoLayout);
    }
    Ok(all)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn image(size: usize) -> Vec<u8> {
        let mut v = vec![0u8; size];
        v[56..60].copy_from_slice(b"ARM\x64");
        v
    }

    #[test]
    fn rejects_non_arm64() {
        let v = vec![0u8; 4096];
        assert_eq!(discover(&v), Err(SymError::NotArm64));
    }

    #[test]
    fn rejects_empty_and_truncated() {
        assert_eq!(discover(&[]), Err(SymError::NotArm64));
        let mut v = image(64);
        v.truncate(32);
        assert_eq!(discover(&v), Err(SymError::NotArm64));
    }

    #[test]
    fn rejects_image_without_tokens() {
        let v = image(4096);
        assert_eq!(discover(&v), Err(SymError::NoTokens));
    }

    #[test]
    fn rejects_oversized() {
        
        let mut v = image(64);
        v.extend(std::iter::repeat(0u8).take(128 * 1024 * 1024));
        assert_eq!(discover(&v), Err(SymError::TooLarge));
    }

    #[test]
    fn enclosing_uses_binary_search() {
        let s = Symbols {
            family: Family::PostTokenImageRelativeU32,
            count: 3,
            count_at: 0,
            addresses: vec![0, 0x100, 0x200],
            names: vec!["T_text".into(), "T_a".into(), "T_b".into()],
        };
        assert_eq!(s.enclosing(0x104), Some(("T_a", 4)));
        assert_eq!(s.enclosing(0), Some(("T_text", 0)));
        assert_eq!(s.enclosing(0x300), Some(("T_b", 0x100)));
        assert_eq!(s.enclosing(0xffff), Some(("T_b", 0xfdff)));
        assert_eq!(s.addr("T_b"), Some(0x200));
        assert_eq!(s.addr("nope"), None);
    }
}
