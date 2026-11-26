// SPDX-License-Identifier: GPL-3.0-only
// Locating the patch sites inside a stock kernel, without per-device offsets.
//

// Finds and validates hook sites in the target image.
use crate::ksyms::Symbols;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SiteKind {
    
    Entry,
    
    AfterPac,
    
    Call,
    
    CalleeBody,
    
    AfterCall,
}

#[derive(Debug, Clone)]
pub struct SiteSpec {
    
    pub name: &'static str,
    
    pub func: &'static str,
    pub kind: SiteKind,
    
    pub callee: Option<&'static str>,
    
    pub needs_arg_prefix: bool,
}

const PACIA: u32 = 0xd503_233f;
const BTI_C: u32 = 0xd503_245f;
const NOP: u32 = 0xd503_201f;

fn bl_destination(at: usize, word: u32) -> usize {
    let off = (word & 0x03ff_ffff) as i32;
    let signed = (off << 6) >> 6;
    (at as i64 + (signed as i64) * 4) as usize
}

fn begins_like_stat_body(kernel: &[u8], base: usize) -> bool {
    let Some(first) = word_at(kernel, base) else { return false };
    if first != PACIA && first != BTI_C && first != NOP {
        return false;
    }
    
    const CMN_X1_FFF: u32 = 0xb13f_fc3f;
    let mut guarded = false;
    let mut kept = false;
    for i in 1..24 {
        let Some(w) = word_at(kernel, base + i * 4) else { return false };
        if w == CMN_X1_FFF {
            guarded = true;
            continue;
        }
        
        let mov_from_x1 = w & 0xffe0_ffe0 == 0xaa00_03e0;
        let str_x1_sp = w & 0xffc0_03ff == 0xf900_03e1;
        if mov_from_x1 || str_x1_sp {
            kept = true;
        }
        if guarded && kept {
            return true;
        }
        
        if w == 0xd503_233f {
            break;
        }
    }
    guarded && kept
}

pub const HOOK_SITES: &[SiteSpec] = &[
    SiteSpec { name: "cav_xperm_entry", func: "avc_has_extended_perms", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_mmap_avc_entry", func: "selinux_mmap_file", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
    SiteSpec { name: "cr_tty_entry", func: "selinux_file_ioctl", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_bprm", func: "security_bprm_check", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_selinux_inode_entry", func: "selinux_inode_permission", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_avc", func: "avc_has_perm", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_avcn", func: "avc_has_perm_noaudit", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_prctl", func: "__arm64_sys_prctl", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_stat", func: "vfs_statx", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_exec", func: "do_execveat_common", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_vread", func: "vfs_read", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_faccess", func: "do_faccessat", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_getattr_entry", func: "security_inode_getattr", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_open_entry", func: "security_file_open", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_iterate_entry", func: "iterate_dir", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_getattr_entry", func: "selinux_inode_getattr", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_generic_entry", func: "generic_permission", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_file_avc_entry", func: "selinux_file_open", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
    SiteSpec { name: "ca_file_data_entry", func: "file_has_perm", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
];

pub const BOOT_SITE: SiteSpec = SiteSpec {
    name: "boot_initramfs",
    func: "kernel_init_freeable",
    kind: SiteKind::Call,
    callee: Some("wait_for_initramfs"),
    needs_arg_prefix: false,
};

pub const HOOK_SITES_612: &[SiteSpec] = &[
    SiteSpec { name: "cav_xperm_entry", func: "avc_has_extended_perms", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_mmap_avc_entry", func: "selinux_mmap_file", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
    SiteSpec { name: "cr_tty_entry", func: "selinux_file_ioctl", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_bprm", func: "security_bprm_check", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_selinux_inode_entry", func: "selinux_inode_permission", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_avc", func: "avc_has_perm", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_avcn", func: "avc_has_perm_noaudit", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_prctl", func: "__arm64_sys_prctl", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    
    SiteSpec { name: "cairn_stat", func: "vfs_statx", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_exec", func: "do_execveat_common", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_vread", func: "vfs_read", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cairn_faccess", func: "do_faccessat", kind: SiteKind::AfterPac, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_getattr_entry", func: "security_inode_getattr", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_open_entry", func: "security_file_open", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "cp_iterate_entry", func: "iterate_dir", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_getattr_entry", func: "selinux_inode_getattr", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_generic_entry", func: "generic_permission", kind: SiteKind::Entry, callee: None, needs_arg_prefix: false },
    SiteSpec { name: "ca_file_avc_entry", func: "selinux_file_open", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
    SiteSpec { name: "ca_file_data_entry", func: "file_has_perm", kind: SiteKind::Call, callee: Some("avc_has_perm"), needs_arg_prefix: true },
];

#[derive(Debug, PartialEq, Eq)]
pub enum LocateError {
    
    MissingFunction(&'static str),
    
    NoPrologue(&'static str),
    
    NoCall(&'static str, &'static str),
    
    Ambiguous(&'static str, usize),
    
    OutOfRange(&'static str),
    
    Unresolvable(&'static str),
}

impl core::fmt::Display for LocateError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            LocateError::MissingFunction(s) => write!(f, "function {s} not present"),
            LocateError::NoPrologue(s) => write!(f, "{s} does not begin with a known prologue"),
            LocateError::NoCall(s, c) => write!(f, "{s} has no call to {c}"),
            LocateError::Ambiguous(s, n) => write!(f, "{s} has {n} candidate sites"),
            LocateError::OutOfRange(s) => write!(f, "{s} resolves outside its function"),
            LocateError::Unresolvable(s) => write!(f, "{s}"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Site {
    pub name: &'static str,
    pub offset: usize,
    
    pub word: u32,
    
    pub decided: &'static str,
}

const PROLOGUE_SCAN: usize = 4;

const ARG_WINDOW: usize = 3;

fn word_at(kernel: &[u8], at: usize) -> Option<u32> {
    kernel
        .get(at..at + 4)
        .map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

fn body(sym: &Symbols, name: &str, kernel_len: usize) -> Option<(usize, usize)> {
    let mut hits = sym.addrs_of(name);
    hits.sort_unstable();
    hits.dedup();
    if hits.len() != 1 {
        return None;
    }
    let base = hits[0] as usize;
    let mut end = kernel_len;
    for &a in &sym.addresses {
        let a = a as usize;
        if a > base && a < end {
            end = a;
        }
    }
    Some((base, end))
}

fn is_bl_to(kernel: &[u8], at: usize, target: usize) -> bool {
    let Some(w) = word_at(kernel, at) else { return false };
    if w & 0xfc00_0000 != 0x9400_0000 {
        return false;
    }
    let mut imm = (w & 0x03ff_ffff) as i64;
    if imm & 0x0200_0000 != 0 {
        imm -= 0x0400_0000;
    }
    let dest = (at as i64).wrapping_add(imm * 4);
    dest == target as i64
}

fn as_ldr_class(word: u32) -> Option<(u32, u32)> {
    
    if word & 0xffc0_0000 != 0xb940_0000 {
        return None;
    }
    if (word >> 10) & 0xfff != 0x1c / 4 {
        return None;
    }
    Some((word & 0x1f, (word >> 5) & 0x1f))
}

fn is_ldrh_mask(word: u32, rt: u32, rn: u32) -> bool {
    
    if word & 0xffc0_0000 != 0x7940_0000 {
        return false;
    }
    (word >> 10) & 0xfff == 0x20 / 2 && word & 0x1f == rt && (word >> 5) & 0x1f == rn
}

fn matches_arg_shape(kernel: &[u8], at: usize) -> bool {
    let lo = at.saturating_sub(ARG_WINDOW * 4);
    let mut i = lo;
    while i < at {
        let Some(a) = word_at(kernel, i) else { return false };
        if let Some((rt, rn)) = as_ldr_class(a) {
            let mut j = i + 4;
            while j < at {
                let Some(b) = word_at(kernel, j) else { return false };
                if is_ldrh_mask(b, (rt + 1) & 0x1f, rn) {
                    return true;
                }
                j += 4;
            }
        }
        i += 4;
    }
    false
}

fn tests_w0(word: u32) -> bool {
    
    if word & 0xff00_001f == 0x3500_0000 {
        return true;
    }
    
    let is_subs = word & 0x7f00_0000 == 0x6b00_0000 || word & 0x7f00_0000 == 0x7100_0000;
    if is_subs && (word >> 5) & 0x1f == 0 && word & 0x1f == 0x1f {
        return true;
    }
    false
}

fn span_is_replayable(kernel: &[u8], at: usize, end: usize) -> bool {
    let mut seen = 0usize;
    let mut cursor = at;
    while cursor + 4 <= end && seen < 3 {
        let Some(word) = word_at(kernel, cursor) else {
            return false;
        };
        if replay_is_obstacle(word) {
            return false;
        }
        seen += 1;
        cursor += 4;
    }
    seen == 3
}

fn replay_is_obstacle(word: u32) -> bool {
    
    if crate::replay::is_pc_relative(word) {
        return true;
    }
    
    let b_cond = word & 0xff00_0010 == 0x5400_0000;
    let cbz = word & 0x7e00_0000 == 0x3400_0000;
    let tbz = word & 0x7e00_0000 == 0x3600_0000;
    let br = word & 0xffff_fc1f == 0xd61f_0000 || word & 0xffff_fc1f == 0xd63f_0000;
    b_cond || cbz || tbz || br
}

fn locate_one(
    kernel: &[u8],
    sym: &Symbols,
    spec: &SiteSpec,
) -> Result<Site, LocateError> {
    let (base, end) = body(sym, spec.func, kernel.len()).ok_or(LocateError::MissingFunction(spec.func))?;
    if end <= base {
        return Err(LocateError::OutOfRange(spec.func));
    }
    let first = word_at(kernel, base).ok_or(LocateError::OutOfRange(spec.func))?;

    match spec.kind {
        SiteKind::Entry => {
            
            if first != PACIA && first != BTI_C && first != NOP {
                return Err(LocateError::NoPrologue(spec.func));
            }
            Ok(Site { name: spec.name, offset: base, word: first, decided: "entry" })
        }
        SiteKind::AfterPac => {
            
            let mut pad = None;
            for i in 0..PROLOGUE_SCAN {
                let at = base + i * 4;
                if at + 4 > end {
                    break;
                }
                let w = word_at(kernel, at).ok_or(LocateError::OutOfRange(spec.func))?;
                if w == PACIA || w == BTI_C {
                    if pad.is_some() {
                        return Err(LocateError::Ambiguous(spec.func, 2));
                    }
                    pad = Some(at);
                }
            }
            let pad = pad.ok_or(LocateError::NoPrologue(spec.func))?;
            let at = pad + 4;
            if at + 4 > end {
                return Err(LocateError::OutOfRange(spec.func));
            }
            let w = word_at(kernel, at).ok_or(LocateError::OutOfRange(spec.func))?;
            Ok(Site { name: spec.name, offset: at, word: w, decided: "after-pac" })
        }
        SiteKind::CalleeBody => {
            
            Err(LocateError::NoPrologue("CalleeBody rule removed; use resolve_stat_site"))
        }
        SiteKind::Call => {
            let callee = spec.callee.ok_or(LocateError::NoCall(spec.func, "?"))?;
            let target = sym.addr_of(callee).ok_or(LocateError::MissingFunction(callee))? as usize;
            let mut hits = Vec::new();
            let mut at = base;
            while at + 4 <= end {
                if is_bl_to(kernel, at, target) {
                    hits.push(at);
                }
                at += 4;
            }
            
            if hits.len() > 1 && spec.needs_arg_prefix {
                let shaped: Vec<usize> = hits
                    .iter()
                    .copied()
                    .filter(|&a| matches_arg_shape(kernel, a))
                    .collect();
                if shaped.len() == 1 {
                    return Ok(Site {
                        name: spec.name,
                        offset: shaped[0],
                        word: word_at(kernel, shaped[0])
                            .ok_or(LocateError::OutOfRange(spec.func))?,
                        decided: "shape",
                    });
                }
                if shaped.len() > 1 {
                    let verdict: Vec<usize> = shaped
                        .iter()
                        .copied()
                        .filter(|&a| {
                            word_at(kernel, a + 4).map(tests_w0).unwrap_or(false)
                        })
                        .collect();
                    if verdict.len() == 1 {
                        return Ok(Site {
                            name: spec.name,
                            offset: verdict[0],
                            word: word_at(kernel, verdict[0])
                                .ok_or(LocateError::OutOfRange(spec.func))?,
                            decided: "shape+verdict",
                        });
                    }
                    return Err(LocateError::Ambiguous(spec.func, shaped.len()));
                }
                
                return Err(LocateError::NoCall(spec.func, callee));
            }
            match hits.len() {
                0 => Err(LocateError::NoCall(spec.func, callee)),
                1 => {
                    let at = hits[0];
                    let w = word_at(kernel, at).ok_or(LocateError::OutOfRange(spec.func))?;
                    Ok(Site { name: spec.name, offset: at, word: w, decided: "sole" })
                }
                n => Err(LocateError::Ambiguous(spec.func, n)),
            }
        }
        SiteKind::AfterCall => {
            
            let callee = spec.callee.ok_or(LocateError::NoCall(spec.func, "?"))?;
            let target = sym.addr_of(callee).ok_or(LocateError::MissingFunction(callee))? as usize;
            let mut candidates = Vec::new();
            let mut at = base;
            while at + 4 <= end {
                if is_bl_to(kernel, at, target) {
                    let after = at + 4;
                    if after + 4 <= end && span_is_replayable(kernel, after, end) {
                        candidates.push(after);
                    }
                }
                at += 4;
            }
            candidates.dedup();
            match candidates.len() {
                0 => Err(LocateError::NoCall(spec.func, callee)),
                1 => {
                    let at = candidates[0];
                    let w = word_at(kernel, at).ok_or(LocateError::OutOfRange(spec.func))?;
                    Ok(Site { name: spec.name, offset: at, word: w, decided: "after-call" })
                }
                n => Err(LocateError::Ambiguous(spec.func, n)),
            }
        }
    }
}

pub fn resolve_all(kernel: &[u8], sym: &Symbols) -> Result<Vec<Site>, (String, LocateError)> {
    resolve_over(HOOK_SITES, kernel, sym)
}

pub fn resolve_for(
    strategy: crate::kernel_version::Strategy,
    kernel: &[u8],
    sym: &Symbols,
) -> Result<Vec<Site>, (String, LocateError)> {
    resolve_over(strategy.site_table(), kernel, sym)
}

fn resolve_over(
    table: &[SiteSpec],
    kernel: &[u8],
    sym: &Symbols,
) -> Result<Vec<Site>, (String, LocateError)> {
    let mut out = Vec::with_capacity(table.len() + 1);
    for spec in table.iter().chain(core::iter::once(&BOOT_SITE)) {
        
        if spec.name == "cairn_stat" {
            match resolve_stat_site(spec, kernel, sym) {
                Ok(s) => {
                    out.push(s);
                    continue;
                }
                Err(e) => return Err((spec.name.to_string(), e)),
            }
        }
        match locate_one(kernel, sym, spec) {
            Ok(s) => out.push(s),
            Err(e) => return Err((spec.name.to_string(), e)),
        }
    }
    Ok(out)
}

fn resolve_stat_site(spec: &SiteSpec, kernel: &[u8], sym: &Symbols) -> Result<Site, LocateError> {
    let mut tried: Vec<String> = Vec::new();
    
    match accept_anchor(kernel, sym, "vfs_statx") {
        Ok(site) => {
            if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
                eprintln!(
                    "TRACE cairn_stat rung=vfs_statx {:#x} first={:#010x}",
                    site.offset, site.word
                );
            }
            return Ok(Site { name: spec.name, offset: site.offset, word: site.word, decided: "stat anchor symbol" });
        }
        Err(e) => tried.push(format!("vfs_statx: {e}")),
    }
    
    for rung in ["__arm64_sys_newfstatat", "__arm64_sys_statx"] {
        let spec_rung = match rung {
            "__arm64_sys_newfstatat" => SiteSpec {
                name: spec.name,
                func: rung,
                kind: SiteKind::AfterCall,
                callee: Some("memset"),
                needs_arg_prefix: false,
            },
            _ => SiteSpec {
                name: spec.name,
                func: rung,
                kind: SiteKind::AfterCall,
                callee: Some("getname_flags"),
                needs_arg_prefix: false,
            },
        };
        match locate_one(kernel, sym, &spec_rung) {
            Ok(site) => {
                if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
                    eprintln!(
                        "TRACE cairn_stat rung={rung} {:#x} first={:#010x} (after-call)",
                        site.offset, site.word
                    );
                }
                return Ok(Site {
                    name: spec.name,
                    offset: site.offset,
                    word: site.word,
                    decided: "syscall after-call",
                });
            }
            Err(e) => tried.push(format!("{rung}: {e:?}")),
        }
    }
    if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
        eprintln!("TRACE cairn_stat refused; rungs tried: {}", tried.join(" | "));
    }
    Err(LocateError::Unresolvable(
        "no 6.12 stat anchor is usable: vfs_statx is absent (inlined by this build) \
         and __arm64_sys_statx is missing or does not start a function; refusing \
         rather than hooking a function that cannot see the caller's path",
    ))
}

fn accept_anchor(kernel: &[u8], sym: &Symbols, name: &str) -> Result<Site, &'static str> {
    let Some(addr) = sym.addr_of(name) else {
        return Err("absent");
    };
    match sym.enclosing(addr) {
        Some((owner, 0)) if owner.get(1..) == Some(name) => {}
        Some((_, delta)) if delta != 0 => return Err("resolves into another function"),
        _ => return Err("aliased"),
    }
    let at = addr as usize;
    let word = word_at(kernel, at).ok_or("outside the image")?;
    Ok(Site { name: "", offset: at, word, decided: "" })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn sample(dir: &str) -> Option<(Vec<u8>, PathBuf)> {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let base = root.join("../../../../research/three-device-dynamic").join(dir);
        let kernel = std::fs::read(base.join("kernel.bin")).ok()?;
        Some((kernel, base.join("symbols.txt")))
    }

    fn reference(name: &str, symbols: &str) -> Option<(Vec<u8>, PathBuf)> {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let adapt = root.join("../../adapt");
        let kernel = std::fs::read(adapt.join(name)).ok()?;
        Some((kernel, adapt.join(symbols)))
    }

    fn symbols(path: &std::path::Path) -> Symbols {
        let text = std::fs::read_to_string(path).expect("symbols.txt");
        let mut rows = Vec::new();
        for line in text.lines() {
            let mut it = line.splitn(3, ' ');
            let a = it.next().unwrap();
            let kind = it.next().unwrap();
            let n = it.next().unwrap();
            rows.push((
                u32::from_str_radix(a, 16).unwrap(),
                format!("{kind}{n}"),
            ));
        }
        rows.sort_by_key(|r| r.0);
        Symbols {
            family: crate::ksyms::Family::PostTokenImageRelativeU32,
            count: rows.len(),
            count_at: 0,
            
            names: rows.iter().map(|r| r.1.clone()).collect(),
            addresses: rows.iter().map(|r| r.0).collect(),
        }
    }

    #[test]
    fn resolves_every_site_on_all_three_kernels() {
        let mut seen = 0;
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let Some((kernel, sym_path)) = sample(dir) else { continue };
            let sym = symbols(&sym_path);
            let sites = resolve_all(&kernel, &sym)
                .unwrap_or_else(|(name, e)| panic!("{dir}: {name}: {e}"));
            assert_eq!(sites.len(), HOOK_SITES.len() + 1, "{dir}");
            
            let mut offsets = std::collections::HashSet::new();
            for s in &sites {
                assert_eq!(s.offset % 4, 0, "{dir} {} alignment", s.name);
                assert!(s.offset + 4 <= kernel.len(), "{dir} {} bounds", s.name);
                assert!(offsets.insert(s.offset), "{dir} {} collides with another site", s.name);
                
                assert_eq!(word_at(&kernel, s.offset), Some(s.word), "{dir} {} word", s.name);
            }
            seen += 1;
        }
        assert!(seen >= 3, "expected three samples, saw {seen}");
    }

    #[test]
    fn reproduces_the_shipped_hook_table() {
        
        for (image, syms, profile) in [
            ("ref_16.0.2.407.bin", "symbols_16.0.2.407.txt", "16.0.2.407"),
            ("ref_17.0.0.100.bin", "symbols_17.0.0.100.txt", "17.0.0.100"),
        ] {
            let Some((kernel, sym_path)) = reference(image, syms) else { continue };
            let sym = symbols(&sym_path);
            let sites = resolve_all(&kernel, &sym)
                .unwrap_or_else(|(name, e)| panic!("{profile}: {name}: {e}"));
            let p = crate::profiles::by_name(profile)
                .unwrap_or_else(|| panic!("{profile}: profile missing"));
            for (off, word, name, _bl) in p.hooks.iter() {
                let s = sites
                    .iter()
                    .find(|s| s.name == *name)
                    .unwrap_or_else(|| panic!("{profile}: {name} not resolved"));
                assert_eq!(s.offset, *off, "{profile}: {name} offset");
                assert_eq!(s.word, *word, "{profile}: {name} original word");
            }
            let boot = sites.iter().find(|s| s.name == BOOT_SITE.name).unwrap();
            assert_eq!(boot.offset, p.boot_hook.0, "{profile}: boot hook offset");
            assert_eq!(boot.word, p.boot_hook.1, "{profile}: boot hook word");
        }
    }

    #[test]
    fn boot_hook_lands_on_wait_for_initramfs_call() {
        
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let Some((kernel, sym_path)) = sample(dir) else { continue };
            let sym = symbols(&sym_path);
            let site = locate_one(&kernel, &sym, &BOOT_SITE).unwrap();
            let target = sym.addr_of("wait_for_initramfs").unwrap() as usize;
            assert!(is_bl_to(&kernel, site.offset, target), "{dir}: boot target");
            assert_eq!(word_at(&kernel, site.offset), Some(site.word), "{dir}: boot word");
        }
    }

    #[test]
    fn dump_one_sample() {
        let Some((_kernel, sym_path)) = sample("1ce5a4f8b813ed21") else { return };
        let rc = crate::diag_sites::run(
            &format!("{}/kernel.bin", sym_path.parent().unwrap().display()),
            &sym_path.display().to_string(),
        );
        println!("rc={rc}");
    }

    #[test]
    fn missing_function_is_reported_not_guessed() {
        let Some((kernel, sym_path)) = sample("888332748bd958be") else { return };
        let sym = symbols(&sym_path);
        let spec = SiteSpec {
            name: "bogus",
            func: "no_such_kernel_function",
            kind: SiteKind::Entry,
            callee: None,
            needs_arg_prefix: false,
        };
        assert_eq!(
            locate_one(&kernel, &sym, &spec),
            Err(LocateError::MissingFunction("no_such_kernel_function"))
        );
    }
}
