// SPDX-License-Identifier: GPL-3.0-only
// Diagnostic only: why did kallsyms discovery fail for this image?
//

// Diagnostic dump of symbol resolution failures.
#[cfg(test)]
mod probe {
    use crate::ksyms;

    #[test]
    #[ignore]
    fn explain_discovery() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let raw = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&raw).expect("open boot");
        let k = &opened.image;
        println!("image bytes = {}", k.len());
        println!(
            "arm64 magic at 56 = {:?}",
            std::str::from_utf8(k.get(56..60).unwrap_or(b""))
        );
        let image_size = u64::from_le_bytes(k[16..24].try_into().unwrap());
        println!("header image_size = {image_size} ({image_size:#x})");
        println!(
            "header image_size minus file bytes = {}",
            image_size as i64 - k.len() as i64
        );

        let needle = b"__ksymtab\0";
        let mut count = 0usize;
        let mut first = Vec::new();
        let mut at = 0usize;
        while let Some(p) = find(k, needle, at) {
            count += 1;
            if first.len() < 8 {
                first.push(format!("{p:#x}"));
            }
            at = p + 1;
        }
        println!("__ksymtab anchors = {count} first={first:?}");

        match ksyms::discover_all(k) {
            Ok(all) => {
                println!("discover_all OK: {} layouts", all.len());
                for s in &all {
                    let end = s.names.iter().position(|n| n.get(1..) == Some("_end"));
                    println!(
                        "  count_at={:#x} syms={} first={:?} last={:?} _end={:?}",
                        s.count_at,
                        s.addresses.len(),
                        s.names.first().map(|n| n.as_str()),
                        s.names.last().map(|n| n.as_str()),
                        end.map(|i| s.addresses[i]),
                    );
                }
            }
            Err(e) => println!("discover_all ERR: {e:?} ({e})"),
        }

        let tables = match token_tables_probe(k) {
            Ok(t) => t,
            Err(e) => {
                println!("token_tables ERR: {e}");
                return;
            }
        };
        println!("token tables = {}", tables.len());
        for (i, (table, index, tokens)) in tables.iter().enumerate() {
            println!(
                "  [{i}] table={table:#x} index={index:#x} t0={:?} t255={:?}",
                String::from_utf8_lossy(tokens[0]),
                String::from_utf8_lossy(tokens[255])
            );
            let start = table.saturating_sub(8 * 1024 * 1024) & !3;
            let mut at = start;
            let mut cands = Vec::new();
            while at + 8 < *table {
                let n = u32::from_le_bytes(k[at..at + 4].try_into().unwrap()) as usize;
                if (20000..=900000).contains(&n) {
                    cands.push((at, n));
                }
                at += 4;
            }
            println!("    count candidates = {} {:?}", cands.len(), &cands[..cands.len().min(4)]);
            for &(at, n) in cands.iter().take(4) {
                let mut p = at + 4;
                if p >= *table {
                    continue;
                }
                let mut size = k[p] as usize;
                p += 1;
                if size & 128 != 0 {
                    size = (size & 127) | ((k[p] as usize) << 7);
                    p += 1;
                }
                let first: Vec<u8> = k[p..(p + size).min(*table)]
                    .iter()
                    .filter_map(|t| tokens.get(*t as usize).copied())
                    .flatten()
                    .copied()
                    .collect();
                println!(
                    "      at {at:#x} n={n} first_name={:?}",
                    String::from_utf8_lossy(&first)
                );
            }
        }
    }

    fn token_tables_probe(d: &[u8]) -> Result<Vec<(usize, usize, Vec<&[u8]>)>, &'static str> {
        let needle = b"__ksymtab\0";
        let mut anchors = Vec::new();
        let mut at = 0usize;
        while let Some(p) = find(d, needle, at) {
            anchors.push(p);
            at = p + 1;
        }
        let mut out = Vec::new();
        for &anchor in &anchors {
            let from = (anchor + 1) & !1;
            let to = (anchor + 5000).min(d.len().saturating_sub(512));
            let mut idx = from;
            while idx + 512 <= d.len() && idx < to {
                let mut offsets = [0u16; 256];
                let mut ok = true;
                for i in 0..256 {
                    let o = idx + i * 2;
                    if o + 2 > d.len() {
                        ok = false;
                        break;
                    }
                    offsets[i] = u16::from_le_bytes([d[o], d[o + 1]]);
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
                                tokens.push(t);
                            }
                            _ => {
                                good = false;
                                break;
                            }
                        }
                    }
                    if good && !out.iter().any(|(t, i, _)| *t == table && *i == idx) {
                        out.push((table, idx, tokens));
                    }
                }
                idx += 2;
            }
        }
        if out.is_empty() {
            Err("no token table candidates")
        } else {
            Ok(out)
        }
    }

    fn cstr(d: &[u8], start: usize, limit: usize) -> Option<&[u8]> {
        let end = d.get(start..limit.min(d.len()))?.iter().position(|b| *b == 0)? + start;
        Some(&d[start..end])
    }

    fn find(hay: &[u8], needle: &[u8], from: usize) -> Option<usize> {
        if from >= hay.len() {
            return None;
        }
        hay[from..]
            .windows(needle.len())
            .position(|w| w == needle)
            .map(|p| p + from)
    }
}
