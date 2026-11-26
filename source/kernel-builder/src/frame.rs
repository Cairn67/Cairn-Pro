// SPDX-License-Identifier: GPL-3.0-only
//! Target-derived prctl frame contract. Does not authorize continuation reuse.
//!

// Derives the target's stack frame shape from its instructions.
use crate::ksyms::Symbols;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Frame {
    
    pub locals: u32,
    
    pub frame_pointer: u32,
    
    pub allocation_at: usize,
    
    pub continuation: u32,
    
    pub save_end: u32,
    
    pub needs_replay: bool,
}

pub fn parse(kernel: &[u8], start: usize, end: usize) -> Result<Frame, String> {
    if start % 4 != 0 || end > kernel.len() || end <= start {
        return Err("PRCTL_FRAME_INVALID_EXTENT".into());
    }
    let limit = end.min(start.saturating_add(256));

    let mut depth: i64 = 0;
    
    let mut x29_depth: Option<i64> = None;
    let mut seen_store = false;
    let mut allocation_at = 0usize;
    let mut first_frame_insn = 0usize;
    
    let mut continuation = start;
    
    let mut save_end: i64 = 0;
    
    let mut saves: Vec<(i64, u32, u32)> = Vec::new();

    for at in (start..limit).step_by(4) {
        let bytes = kernel.get(at..at + 4).ok_or("PRCTL_FRAME_TRUNCATED")?;
        let w = u32::from_le_bytes(bytes.try_into().unwrap());

        if matches!(w, 0xd503233f | 0xd503201f | 0xd503245f | 0xd503249f) {
            continuation = at + 4;
            continue;
        }

        if w & 0xff80_03ff == 0xd100_03ff {
            let imm = (w >> 10) & 0xfff;
            let amount = i64::from(if w & 0x0040_0000 != 0 { imm << 12 } else { imm });
            if amount == 0 || amount % 16 != 0 {
                return Err("PRCTL_FRAME_INVALID_ALLOCATION".into());
            }
            depth += amount;
            if allocation_at == 0 {
                allocation_at = at;
            }
            if first_frame_insn == 0 {
                first_frame_insn = at;
            }
            continuation = at + 4;
            continue;
        }
        
        let pair = w & 0xffc0_03e0;
        if pair == 0xa980_03e0 || pair == 0xa900_03e0 {
            let rt = w & 31;
            let rt2 = (w >> 10) & 31;
            if !(19..=30).contains(&rt) || !(19..=30).contains(&rt2) {
                return Err(format!("PRCTL_FRAME_NON_CALLEE_STORE at {at:#x}"));
            }
            seen_store = true;
            if first_frame_insn == 0 {
                first_frame_insn = at;
            }
            
            let imm7 = ((w >> 15) & 0x7f) as i32;
            let scaled = i64::from(((imm7 << 25) >> 25) * 8);
            if (w >> 23) & 3 == 3 {
                if scaled >= 0 {
                    return Err(format!("PRCTL_FRAME_POSITIVE_PREINDEX at {at:#x}"));
                }
                depth -= scaled;
                if allocation_at == 0 {
                    allocation_at = at;
                }
            }
            
            let offset = if (w >> 23) & 3 == 3 { 0 } else { scaled };
            saves.push((offset, w & 31, (w >> 10) & 31));
            
            let slot_top = scaled.abs().max(if offset == 0 { scaled.abs() } else { offset });
            if slot_top > save_end {
                save_end = slot_top;
            }
            continuation = at + 4;
            continue;
        }
        
        if w & 0xffc0_03ff == 0x9100_03fd {
            let imm = i64::from((w >> 10) & 0xfff);
            x29_depth = Some(depth - imm);
            continuation = at + 4;
            continue;
        }
        
        if w == 0x910003fd {
            x29_depth = Some(depth);
            continuation = at + 4;
            continue;
        }
        
        break;
    }

    if !seen_store {
        return Err("PRCTL_FRAME_NO_SAVED_REGISTERS".into());
    }
    if depth <= 0 {
        return Err("PRCTL_FRAME_INVALID_ALLOCATION".into());
    }
    let x29_depth = x29_depth.ok_or("PRCTL_FRAME_NO_FRAME_POINTER")?;
    
    if x29_depth < 0 || x29_depth > depth {
        return Err(format!(
            "PRCTL_FRAME_POINTER_OUTSIDE_FRAME x29 depth={x29_depth} frame={depth}"
        ));
    }
    Ok(Frame {
        locals: u32::try_from(depth).map_err(|_| "PRCTL_FRAME_TOO_LARGE")?,
        frame_pointer: u32::try_from(depth - x29_depth).map_err(|_| "PRCTL_FRAME_TOO_LARGE")?,
        allocation_at: if allocation_at == 0 { first_frame_insn } else { allocation_at },
        
        continuation: u32::try_from(continuation.saturating_sub(start))
            .map_err(|_| "PRCTL_FRAME_TOO_LARGE")?,
        save_end: u32::try_from(save_end.max(0)).map_err(|_| "PRCTL_FRAME_TOO_LARGE")?,
        
        needs_replay: {
            
            let expected = [
                (29u32, 30u32, 0i64),
                (28, 27, 16),
                (26, 25, 32),
                (24, 23, 48),
                (22, 21, 64),
                (20, 19, 80),
            ];
            let same_layout = saves.len() == expected.len()
                && expected
                    .iter()
                    .all(|(a, b, want)| saves.iter().any(|(off, rt, rt2)| rt == a && rt2 == b && off == want));
            !same_layout
        },
    })
}

pub fn derive(kernel: &[u8], symbols: &Symbols) -> Result<Frame, String> {
    let start = symbols.addr_of("__arm64_sys_prctl").ok_or("missing __arm64_sys_prctl")? as usize;
    let end = symbols.addresses.iter().map(|&a| a as usize)
        .filter(|&a| a > start).min().unwrap_or(kernel.len());
    parse(kernel, start, end)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn image(words: &[u32]) -> Vec<u8> { words.iter().flat_map(|w| w.to_le_bytes()).collect() }

    #[test]
    fn store_then_allocate_shape() {
        
        let k = image(&[0xd503233f, 0xa9ba7bfd, 0xa9054ff4, 0x910003fd, 0xd10703ff]);
        let f = parse(&k, 0, k.len()).unwrap();
        assert_eq!(f.locals, 96 + 448);
        assert_eq!(f.frame_pointer, 448);
    }

    #[test]
    fn allocate_then_offset_store_shape() {
        
        let k = image(&[0xd503233f, 0xd101c3ff, 0xa9027bfd, 0xa9064ff4, 0x910083fd]);
        let f = parse(&k, 0, k.len()).unwrap();
        assert_eq!(f.locals, 112);
        assert_eq!(f.frame_pointer, 32);
    }

    #[test]
    fn allocate_then_offset_store_shape_large() {
        
        let k = image(&[0xd503233f, 0xd107c3ff, 0xa9197bfd, 0xa91a6ffc, 0x910643fd]);
        let f = parse(&k, 0, k.len()).unwrap();
        assert_eq!(f.locals, 496);
        assert_eq!(f.frame_pointer, 400);
    }

    #[test]
    fn refuses_accidental_sub_after_call() {
        let k = image(&[0xd503233f, 0xa9ba7bfd, 0x94000000, 0xd10683ff]);
        assert!(parse(&k, 0, k.len()).is_err());
        assert!(parse(&k, 1, k.len()).is_err());
        assert!(parse(&k, 0, k.len() + 4).is_err());
    }

    #[test]
    fn refuses_a_frame_pointer_outside_the_frame() {
        
        let k = image(&[0xd503233f, 0xa9ba7bfd, 0xd10103ff, 0x911003fd]);
        let err = parse(&k, 0, k.len()).unwrap_err();
        assert!(err.contains("FRAME_POINTER_OUTSIDE_FRAME"), "{err}");
    }

    #[test]
    fn real_kernel_frames() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        for sample in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let k = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&k).unwrap();
            let version = crate::kernel_version::detect(&k).unwrap();
            let frame = derive(&k, &symbols).unwrap_or_else(|e| panic!("{sample}: {e}"));
            println!(
                "NATIVE_FRAME {} locals={} x29=sp+{} continuation={} at={:#x}",
                version.release, frame.locals, frame.frame_pointer, frame.continuation, frame.allocation_at
            );
        }
    }
    
    #[test]
    fn prctl_continuation_follows_the_target_frame() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        
        for (sample, expected) in [
            ("1ce5a4f8b813ed21", 36u32),
            ("2178360d572d1f63", 36),
            ("888332748bd958be", 32),
        ] {
            let k = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&k).unwrap();
            let frame = derive(&k, &symbols).unwrap_or_else(|e| panic!("{sample}: {e}"));
            assert_eq!(frame.continuation, expected, "{sample}");
        }
    }
    
    #[test]
    fn prctl_replay_need_follows_the_target_save_layout() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        for (sample, replay) in [
            ("1ce5a4f8b813ed21", false),
            ("2178360d572d1f63", false),
            ("888332748bd958be", true),
        ] {
            let k = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&k).unwrap();
            let frame = derive(&k, &symbols).unwrap_or_else(|e| panic!("{sample}: {e}"));
            assert_eq!(frame.needs_replay, replay, "{sample}");
        }
    }
}

#[cfg(test)]
mod probe {
    
    #[test]
    #[ignore]
    fn dump_prctl_prologue() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let p = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&p).expect("open boot");
        let k = &opened.image;
        let symbols = crate::ksyms::discover(k).expect("kallsyms");
        let start = symbols.addr_of("__arm64_sys_prctl").expect("symbol") as usize;
        println!("PROLOGUE {} @ {:#x}", path, start);
        for i in 0..24 {
            let at = start + i * 4;
            let w = u32::from_le_bytes(k[at..at + 4].try_into().unwrap());
            println!("  +{:<3} {:#010x}", i * 4, w);
        }
        println!("frame parse: {:?}", super::derive(k, &symbols));
    }
}
