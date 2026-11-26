// SPDX-License-Identifier: GPL-3.0-only
//! CFG-aware proof that one callee-saved register holds a function's first
//! argument at a hook point.

// Builds the prologue that re-enters the target function.
use std::collections::{BTreeMap, VecDeque};

pub const X0: u8 = 0;

fn writes(w: u32) -> Option<Vec<u8>> {
    let rd = (w & 31) as u8;
    let rt2 = ((w >> 10) & 31) as u8;
    let cmp_form = (w >> 29) & 1 == 1 && rd == 31;

    if w & 0x7c00_0000 == 0x1400_0000 { return Some(Vec::new()); }  
    if w & 0xff00_0010 == 0x5400_0000 { return Some(Vec::new()); }  
    if w & 0x7e00_0000 == 0x3400_0000 { return Some(Vec::new()); }  
    if w & 0x7e00_0000 == 0x3600_0000 { return Some(Vec::new()); }  
    if w & 0xffff_fc1f == 0xd61f_0000 { return Some(Vec::new()); }  
    if w & 0xffff_fc1f == 0xd65f_0000 { return Some(Vec::new()); }  
    if w & 0xfc00_0000 == 0x9400_0000 { return Some(vec![30]); }    
    if w & 0xffff_fc1f == 0xd63f_0000 { return Some(vec![30]); }    

    if w & 0xffff_f01f == 0xd503_201f { return Some(Vec::new()); }  
    if w & 0xfff8_f01f == 0xd503_301f { return Some(Vec::new()); }  
    if w & 0xfff0_0000 == 0xd530_0000 { return Some(vec![rd]); }    
    if w & 0xfff0_0000 == 0xd510_0000 { return Some(Vec::new()); }  
    
    if w & 0xffe0_001f == 0xd420_0000 || w & 0xffe0_001f == 0xd440_0000
        || w & 0xffe0_001f == 0xd4a0_0000 { return Some(Vec::new()); }

    if w & 0x3a00_0000 == 0x2800_0000 {
        if w & 0x0400_0000 != 0 { return Some(Vec::new()); }        
        if w & 0x0040_0000 == 0 { return Some(Vec::new()); }        
        return Some(vec![rd, rt2]);                                 
    }
    
    if w & 0x3a00_0000 == 0x3800_0000 {
        if w & 0x0400_0000 != 0 { return Some(Vec::new()); }        
        if w & 0x0040_0000 == 0 { return Some(Vec::new()); }        
        return Some(vec![rd]);
    }
    
    if w & 0x3f00_0000 == 0x0800_0000 {
        return Some(if w & 0x0040_0000 == 0 { Vec::new() } else { vec![rd] });
    }
    
    if w & 0x3b00_0000 == 0x1800_0000 {
        return Some(if w & 0x0400_0000 != 0 { Vec::new() } else { vec![rd] });
    }

    if w & 0x1f00_0000 == 0x1100_0000 || w & 0x1f00_0000 == 0x9100_0000 {
        return Some(if cmp_form { Vec::new() } else { vec![rd] });  
    }
    if w & 0x1f80_0000 == 0x1200_0000 {
        return Some(if cmp_form { Vec::new() } else { vec![rd] });  
    }
    if w & 0x1f80_0000 == 0x1280_0000 { return Some(vec![rd]); }    
    if w & 0x1f00_0000 == 0x1000_0000 { return Some(vec![rd]); }    
    if w & 0x1f80_0000 == 0x1380_0000 { return Some(vec![rd]); }    

    if w & 0x1f00_0000 == 0x0b00_0000 {
        return Some(if cmp_form { Vec::new() } else { vec![rd] });  
    }
    if w & 0x1f00_0000 == 0x0a00_0000 {
        return Some(if cmp_form { Vec::new() } else { vec![rd] });  
    }
    
    if w & 0x1f00_0000 == 0x1a00_0000 {
        return Some(if cmp_form { Vec::new() } else { vec![rd] });
    }
    if w & 0x1f00_0000 == 0x1b00_0000 { return Some(vec![rd]); }    
    if w & 0x1fe0_0000 == 0x1a80_0000 { return Some(vec![rd]); }    
    if w & 0x1fe0_0000 == 0x1ac0_0000 { return Some(vec![rd]); }    
    if w & 0x1f80_0000 == 0x1300_0000                              
        || w & 0x1f80_0000 == 0x3300_0000                          
        || w & 0x1f80_0000 == 0x5300_0000 { return Some(vec![rd]); } 
    if w & 0x5fe0_0000 == 0x5ac0_0000 { return Some(vec![rd]); }    

    if w & 0xffff_fc00 == 0x1e26_0000 || w & 0xffff_fc00 == 0x1e27_0000 {
        return Some(vec![rd]);
    }
    if w & 0x1f00_0000 == 0x1e00_0000 { return Some(Vec::new()); }  
    if w & 0x0c00_0000 == 0x0c00_0000 { return Some(Vec::new()); }  
    None
}

fn word(k: &[u8], at: usize) -> Result<u32, String> {
    let b = k.get(at..at.checked_add(4).ok_or("offset overflow")?)
        .ok_or("instruction outside Image")?;
    Ok(u32::from_le_bytes(b.try_into().unwrap()))
}

fn successors(k: &[u8], at: usize, end: usize) -> Result<Vec<usize>, String> {
    let w = word(k, at)?;
    let relative = |bits: u32| -> Result<usize, String> {
        let mask = (1u32 << bits) - 1;
        let raw = (w >> 5) & mask;
        let disp = ((raw << (32 - bits)) as i32 >> (32 - bits)) as i64 * 4;
        usize::try_from(at as i128 + i128::from(disp)).map_err(|_| "target overflow".to_string())
    };
    if w & 0x7c00_0000 == 0x1400_0000 {
        let raw = (w & 0x03ff_ffff) as i32;
        let disp = ((raw << 6) >> 6) as i64 * 4;
        return Ok(vec![usize::try_from(at as i128 + i128::from(disp))
            .map_err(|_| "target overflow".to_string())?]);
    }
    if w & 0xff00_0010 == 0x5400_0000 { return Ok(vec![relative(19)?, at + 4]); }
    if w & 0x7e00_0000 == 0x3400_0000 { return Ok(vec![relative(19)?, at + 4]); }
    if w & 0x7e00_0000 == 0x3600_0000 { return Ok(vec![relative(14)?, at + 4]); }
    if w & 0xffff_fc1f == 0xd65f_0000 || w & 0xffff_fc1f == 0xd61f_0000 { return Ok(Vec::new()); }
    if w & 0xfe00_0000 == 0xd600_0000 || w & 0xffff_fc1f == 0xd63f_0000 {
        return Err(format!("INDIRECT_CONTROL_FLOW_UNPROVEN at {at:#x}"));
    }
    
    if w & 0xffe0_001f == 0xd420_0000 || w & 0xffe0_001f == 0xd440_0000
        || w & 0xffe0_001f == 0xd4a0_0000 { return Ok(Vec::new()); }
    if at + 4 >= end { return Ok(Vec::new()); }
    Ok(vec![at + 4])
}

pub fn holder_at(k: &[u8], start: usize, end: usize, hook: usize) -> Result<u8, String> {
    if start % 4 != 0 || hook % 4 != 0 || hook < start || end <= start || end > k.len() {
        return Err("PROLOGUE_EXTENT_INVALID".into());
    }
    let mut seen: BTreeMap<(usize, Option<u8>), ()> = BTreeMap::new();
    let mut queue = VecDeque::new();
    seen.insert((start, Some(X0)), ());
    queue.push_back((start, Some(X0)));
    let mut reached: Option<u8> = None;
    let mut steps = 0usize;

    while let Some((at, holder)) = queue.pop_front() {
        steps += 1;
        if steps > 200_000 { return Err("PROLOGUE_BUDGET".into()); }
        if at == hook {
            let Some(h) = holder.filter(|r| (19..=28).contains(r)) else {
                return Err("MMAP_FILE_REGISTER_UNRESOLVED".into());
            };
            match reached {
                Some(prev) if prev != h => return Err("MMAP_FILE_REGISTER_AMBIGUOUS".into()),
                _ => reached = Some(h),
            }
            continue;
        }
        if at >= end { continue; }
        let w = word(k, at)?;
        let next = match writes(w) {
            Some(written) => {
                let mut h = holder;
                if let Some(cur) = h {
                    
                    if written.contains(&cur) || (w & 0xfc00_0000 == 0x9400_0000 && cur <= 18) {
                        h = None;
                    }
                }
                
                if w & 0xffe0_ffe0 == 0xaa00_03e0 {
                    let rd = (w & 31) as u8;
                    let rm = ((w >> 16) & 31) as u8;
                    if holder == Some(rm) && !written.contains(&rm) { h = Some(rd); }
                }
                h
            }
            None => {
                
                if holder.is_none() { continue; }
                return Err(format!("PROLOGUE_INSTRUCTION_UNPROVEN at {at:#x}: {w:#x}"));
            }
        };
        for succ in successors(k, at, end)? {
            if succ >= end { continue; }
            if seen.contains_key(&(succ, next)) { continue; }
            seen.insert((succ, next), ());
            queue.push_back((succ, next));
        }
    }
    reached.ok_or_else(|| "MMAP_FILE_HOOK_UNREACHABLE".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn image(words: &[u32]) -> Vec<u8> { words.iter().flat_map(|w| w.to_le_bytes()).collect() }

    #[test]
    fn finds_holder_across_branches() {
        
        let k = image(&[0xaa0003f3, 0xb4000060, 0xf9401268, 0xd503201f]);
        assert_eq!(holder_at(&k, 0, k.len(), 12).unwrap(), 19);
    }

    #[test]
    fn refuses_ambiguous_and_clobbered() {
        
        let k = image(&[
            0xb4000060, 
            0xaa0003f3, 
            0x14000002, 
            0xaa0003f4, 
            0xd503201f, 
        ]);
        assert!(holder_at(&k, 0, k.len(), 16).is_err());
        
        let k = image(&[0xaa0003f3, 0xaa0103f3, 0xd503201f]);
        assert!(holder_at(&k, 0, k.len(), 8).is_err());
    }

    #[test]
    fn candidate_set_is_derived_from_code_not_a_constant() {
        for rd in [19u32, 20, 24, 28] {
            let k = image(&[0xaa0003e0 | rd, 0xd503201f]);
            assert_eq!(holder_at(&k, 0, k.len(), 4).unwrap(), rd as u8);
        }
    }

    #[test]
    fn real_kernel_holder() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic/1ce5a4f8b813ed21");
        let k = std::fs::read(root.join("kernel.bin")).expect("6.1 kernel fixture");
        let symbols = crate::ksyms::discover(&k).unwrap();
        let version = crate::kernel_version::detect(&k).unwrap();
        let sites = version.strategy.resolve(&k, &symbols).unwrap();
        let start = symbols.addr_of("selinux_mmap_file").unwrap() as usize;
        let end = symbols.addresses.iter().map(|&a| a as usize)
            .filter(|&a| a > start).min().unwrap();
        let hook = sites.iter().find(|s| s.name == "ca_mmap_avc_entry").unwrap().offset;
        let h = holder_at(&k, start, end, hook).unwrap();
        println!("NATIVE_PROLOGUE selinux_mmap_file holder=x{h} start={start:#x} hook={hook:#x}");
        assert!((19..=28).contains(&h));
    }
}
