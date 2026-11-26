// SPDX-License-Identifier: GPL-3.0-only
//! Write a patched Image from a finished build, and prove the byte footprint.
//!

// Reads and writes the kernel image container.
use crate::placement::Placement;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Site {
    pub name: &'static str,
    pub offset: usize,
    pub word: u32,
    
    pub entry: usize,
    
    pub linked: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Patched {
    pub image: Vec<u8>,
    pub changed_bytes: usize,
    pub changed_bytes_in_cave: usize,
    pub changed_site_words: usize,
    pub hook_branches: usize,
    pub runtime_tail_bytes: usize,
}

fn put32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn get32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

pub fn branch(src: usize, dst: usize, link: bool) -> Result<u32, String> {
    let delta = dst as i64 - src as i64;
    if delta % 4 != 0 || !(-0x800_0000..0x800_0000).contains(&delta) {
        return Err(format!("branch range {src:#x}->{dst:#x}"));
    }
    let opcode = if link { 0x9400_0000u32 } else { 0x1400_0000u32 };
    Ok(opcode | (((delta >> 2) as u32) & 0x03ff_ffff))
}

pub const RUNTIME_TAIL: usize = 56;

pub fn apply(
    stock: &[u8],
    bootstrap: &[u8],
    sites: &[Site],
    boot: &Site,
    placement: &Placement,
) -> Result<Patched, String> {
    let base = placement.base;
    let cave_end = placement.cave_end;
    if cave_end <= base || cave_end > stock.len() {
        return Err(format!("PLACEMENT_INVALID cave {base:#x}..{cave_end:#x}"));
    }
    let limit = cave_end - base;
    if bootstrap.len() > limit {
        return Err(format!("BOOTSTRAP_TOO_LARGE {} > {limit}", bootstrap.len()));
    }
    if stock[base..cave_end].iter().any(|&b| b != 0) {
        return Err("CAVE_NOT_EMPTY: another patcher already used this region".into());
    }

    let mut out = stock.to_vec();
    let mut allowed = vec![false; stock.len()];
    for i in base..cave_end {
        allowed[i] = true;
    }

    for site in sites.iter().chain(std::iter::once(boot)) {
        if site.offset + 4 > stock.len() {
            return Err(format!("SITE_OUTSIDE_IMAGE {}: {:#x}", site.name, site.offset));
        }
        if get32(&out, site.offset) != site.word {
            return Err(format!(
                "HOOK_FINGERPRINT_MISMATCH {}: found {:#010x}, expected {:#010x}",
                site.name,
                get32(&out, site.offset),
                site.word
            ));
        }
        let target = base.checked_add(site.entry).ok_or("entry overflow")?;
        if target >= cave_end {
            return Err(format!("ENTRY_OUTSIDE_CAVE {}: {target:#x}", site.name));
        }
        let word = branch(site.offset, target, site.linked)?;
        put32(&mut out, site.offset, word);
        for i in site.offset..site.offset + 4 {
            allowed[i] = true;
        }
    }

    out[base..base + bootstrap.len()].copy_from_slice(bootstrap);
    out.extend(std::iter::repeat_n(0u8, RUNTIME_TAIL));

    let mut changed = 0usize;
    let mut in_cave = 0usize;
    let mut site_words = 0usize;
    for (i, (&old, &new)) in stock.iter().zip(out.iter()).enumerate() {
        if old == new {
            continue;
        }
        if !allowed[i] {
            return Err(format!("WRITE_OUTSIDE_ALLOWED_REGION at {i:#x}"));
        }
        changed += 1;
        if (base..cave_end).contains(&i) {
            in_cave += 1;
        } else {
            site_words += 1;
        }
    }
    if out[stock.len()..].iter().any(|&b| b != 0) {
        return Err("RUNTIME_TAIL_NOT_ZERO".into());
    }
    Ok(Patched {
        image: out,
        changed_bytes: changed,
        changed_bytes_in_cave: in_cave,
        changed_site_words: site_words,
        hook_branches: sites.len() + 1,
        runtime_tail_bytes: RUNTIME_TAIL,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn placement(base: usize, cave_end: usize) -> Placement {
        Placement {
            base,
            capacity: cave_end - base,
            cave_end,
            hyp_text_end: 0,
            slot: base + 0x1000,
            state: base + 0x1018,
            reserved_end: 0,
            bss_start: 0,
            runtime_image: 0,
        }
    }

    fn site(name: &'static str, offset: usize, word: u32, entry: usize, linked: bool) -> Site {
        Site { name, offset, word, entry, linked }
    }

    #[test]
    fn branch_encoding_matches_architecture() {
        assert_eq!(branch(0x100, 0x104, false).unwrap(), 0x1400_0001);
        assert_eq!(branch(0x104, 0x100, true).unwrap(), 0x97ff_ffff);
        assert!(branch(0x100, 0x102, false).is_err());
        assert!(branch(0x100, 0x1000_0000, false).is_err());
    }

    #[test]
    fn patches_only_the_cave_and_the_site_words() {
        let stock = vec![0u8; 0x2000];
        let mut stock = stock;
        
        put32(&mut stock, 0x100, 0xdead_beef);
        
        put32(&mut stock, 0x200, 0x9700_0000);
        let bootstrap = vec![0x5au8; 0x40];
        let s = site("hook", 0x100, 0xdead_beef, 0x08, false);
        let boot = site("boot", 0x200, 0x9700_0000, 0x10, true);
        let p = apply(&stock, &bootstrap, &[s], &boot, &placement(0x1000, 0x1080)).unwrap();
        assert_eq!(p.changed_bytes_in_cave, 0x40);
        
        assert_eq!(p.changed_site_words, 7);
        assert_eq!(p.hook_branches, 2);
        let word = get32(&p.image, 0x100);
        
        assert_eq!(word & 0xfc00_0000, 0x1400_0000, "hook must be a b");
        let delta = (((word & 0x03ff_ffff) as i64) << 2) | 0;
        let back = delta as i32;
        let target = (0x100i64 + back as i64) as usize;
        assert_eq!(target, 0x1000 + 0x08);
    }

    #[test]
    fn refuses_a_cave_that_was_already_used_or_a_stale_fingerprint() {
        let mut stock = vec![0u8; 0x2000];
        put32(&mut stock, 0x100, 0xdead_beef);
        stock[0x1000] = 1;
        let s = site("hook", 0x100, 0xdead_beef, 0x08, false);
        let boot = site("boot", 0x200, 0, 0x10, true);
        let err = apply(&stock, &[0u8; 8], &[s.clone()], &boot, &placement(0x1000, 0x1080))
            .unwrap_err();
        assert!(err.starts_with("CAVE_NOT_EMPTY"), "{err}");

        let clean = vec![0u8; 0x2000];
        let err = apply(&clean, &[0u8; 8], &[s], &boot, &placement(0x1000, 0x1080)).unwrap_err();
        assert!(err.starts_with("HOOK_FINGERPRINT_MISMATCH"), "{err}");
    }

    #[test]
    fn refuses_a_bootstrap_larger_than_the_cave() {
        let mut stock = vec![0u8; 0x2000];
        put32(&mut stock, 0x100, 0xdead_beef);
        let s = site("hook", 0x100, 0xdead_beef, 0x08, false);
        let boot = site("boot", 0x200, 0, 0x10, true);
        let err = apply(&stock, &vec![0x5a; 0x200], &[s], &boot, &placement(0x1000, 0x1080))
            .unwrap_err();
        assert!(err.starts_with("BOOTSTRAP_TOO_LARGE"), "{err}");
    }
}
