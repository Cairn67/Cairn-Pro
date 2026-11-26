// SPDX-License-Identifier: GPL-3.0-only
//! Target-derived inputs for the native dynamic payload generator.
//! No profile fingerprint, external symbol file or reference addresses.
// Plans hooks from the target's own symbols and instruction structure.
use crate::{kernel_version, ksyms::Symbols, sites};

#[derive(Debug)]
pub struct Plan {
    pub version: kernel_version::KernelVersion,
    pub sites: Vec<sites::Site>,
    pub mmap_file_register: u8,
    pub replays: Vec<crate::replay::Replay>,
    pub prctl_frame: crate::frame::Frame,
    pub avc_state_leading: Option<crate::avc_abi::StateLeading>,
    pub placement: crate::placement::Placement,
    pub fixups: crate::fixups::Plan,
    pub layout: Vec<crate::payload_layout::Offset>,
}

fn word(kernel: &[u8], at: usize) -> Result<u32, String> {
    let bytes = kernel.get(at..at.checked_add(4).ok_or("offset overflow")?)
        .ok_or("instruction outside Image")?;
    Ok(u32::from_le_bytes(bytes.try_into().unwrap()))
}

fn file_register(kernel: &[u8], start: usize, hook: usize) -> Result<u8, String> {
    if hook <= start || hook - start > 4096 { return Err("invalid mmap function extent".into()); }
    let mut saved = None;
    for at in (start..hook).step_by(4) {
        let w = word(kernel, at)?;
        if w & 0xffff_ffe0 == 0xaa00_03e0 {
            let rd = (w & 31) as u8;
            if (19..=28).contains(&rd) { saved = Some((rd, at)); break; }
        }
        let pad = matches!(w, 0xd503233f | 0xd503201f | 0xd503245f | 0xd503249f);
        let mov = w & 0xffe0_ffe0 == 0xaa00_03e0 && w & 31 != 0;
        let stack = (w & 0xffc0_03e0 == 0xa900_03e0)
            || (w & 0xffc0_03e0 == 0xa980_03e0)
            || (w & 0xffc0_03e0 == 0xf900_03e0)
            || (w & 0xff80_03ff == 0xd100_03ff)
            || (w & 0xff80_03ff == 0x9100_03fd);
        let mrs = w & 0xffff_ffe0 == 0xd538_4100 && w & 31 != 0;
        if !(pad || mov || stack || mrs) {
            return Err(format!("MMAP_FILE_PROLOGUE_UNSUPPORTED at {at:#x}: {w:#x}"));
        }
    }
    let (rd, copied_at) = saved.ok_or("MMAP_FILE_REGISTER_UNRESOLVED")?;
    
    for at in ((copied_at + 4)..hook).step_by(4) {
        let w = word(kernel, at)?;
        if w & 0xfc00_0000 == 0x9400_0000 { continue; }
        
        if w & 0x7c000000 == 0x14000000 || w & 0xff000010 == 0x54000000
            || w & 0x7e000000 == 0x34000000 || w & 0x7e000000 == 0x36000000
            || w & 0xfe000000 == 0xd6000000 {
            return Err(format!("MMAP_FILE_CFG_UNPROVEN at {at:#x}"));
        }
        
        let store = w & 0x0a00_0000 == 0x0800_0000 && w & (1 << 22) == 0;
        if !store && w & 31 == u32::from(rd) {
            return Err(format!("MMAP_FILE_REGISTER_CLOBBER at {at:#x}"));
        }
    }
    Ok(rd)
}

pub fn derive(kernel: &[u8], symbols: &Symbols) -> Result<Plan, String> {
    let version = kernel_version::detect(kernel)?;
    let resolved = version.strategy.resolve(kernel, symbols)
        .map_err(|(name, error)| format!("{name}: {error}"))?;
    let start = symbols.addr_of("selinux_mmap_file").ok_or("missing selinux_mmap_file")? as usize;
    let end = symbols.addresses.iter().map(|&a| a as usize)
        .filter(|&a| a > start).min().unwrap_or(kernel.len()).min(kernel.len());
    let hook = resolved.iter().find(|s| s.name == "ca_mmap_avc_entry")
        .ok_or("missing mmap hook")?.offset;
    let mmap_file_register = crate::prologue::holder_at(kernel, start, end, hook)?;
    let replays = resolved.iter().map(|site| {
        let original = word(kernel, site.offset)?;
        if original != site.word { return Err("site changed during analysis".into()); }
        let replay = crate::replay::derive(original, site.offset)?;
        if let crate::replay::Replay::Branch { target, .. } = replay {
            if target.checked_add(4).map_or(true, |end| end > kernel.len()) {
                return Err(format!("branch destination outside Image: {target:#x}"));
            }
        }
        Ok(replay)
    }).collect::<Result<Vec<_>, String>>()?;
    let prctl_frame = crate::frame::derive(kernel, symbols)?;
    let avc_state_leading = match version.strategy {
        kernel_version::Strategy::Linux61 => Some(crate::avc_abi::detect(kernel, symbols)?),
        _ => None,
    };
    
    let placement = crate::placement::derive(kernel, symbols, LIMIT)
        .map_err(|e| format!("PLACEMENT_FAILED {e}"))?;
    let hooks: Vec<(&str, usize)> = resolved.iter().map(|s| (s.name, s.offset)).collect();
    
    let stat_shape = resolved
        .iter()
        .find(|s| s.name == "cairn_stat")
        .map(|s| match s.decided {
            
            "stat anchor symbol" => crate::fixups::StatShape::Entry,
            "vfs_fstatat after-pac" => crate::fixups::StatShape::AfterPac,
            
            "syscall after-call" => crate::fixups::StatShape::SyscallAfterCall,
            _ => crate::fixups::StatShape::Entry,
        });
    if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
        eprintln!(
            "TRACE stat_shape={:?} decided={:?}",
            stat_shape,
            resolved
                .iter()
                .find(|s| s.name == "cairn_stat")
                .map(|s| s.decided)
        );
    }
    let fixups = crate::fixups::resolve_with_context(
        symbols,
        placement.slot,
        &hooks,
        Some(&prctl_frame),
        Some(version.strategy),
        stat_shape,
    )?;
    let layout = crate::payload_layout::derive(kernel)?;
    Ok(Plan {
        version,
        sites: resolved,
        mmap_file_register,
        replays,
        prctl_frame,
        avc_state_leading,
        placement,
        fixups,
        layout,
    })
}

pub const LIMIT: usize = 0x2560;

#[cfg(test)]
mod tests {
    use super::*;
    fn image(words: &[u32]) -> Vec<u8> {
        words.iter().flat_map(|w| w.to_le_bytes()).collect()
    }
    #[test]
    fn reads_target_register_not_family_constant() {
        for rd in [19, 20, 24] {
            let k = image(&[0xd503233f, 0xaa0003e0 | rd, 0xd503201f, 0x94000000]);
            assert_eq!(file_register(&k, 0, 12).unwrap(), rd as u8);
        }
    }
    #[test]
    fn three_real_kernels_derive_or_refuse_deliberately() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        for sample in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let kernel = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&kernel).unwrap();
            match derive(&kernel, &symbols) {
                Ok(plan) => println!("NATIVE_PLAN {} sites={} register=x{}",
                    plan.version.release, plan.sites.len(), plan.mmap_file_register),
                Err(reason) => println!("NATIVE_PLAN_REFUSED {sample}: {reason}"),
            }
        }
    }
    #[test]
    fn rejects_unproven_flow_and_clobber() {
        for w in [0xb4000080, 0xaa0103f3] {
            let k = image(&[0xd503233f, 0xaa0003f3, w, 0x94000000]);
            assert!(file_register(&k, 0, 12).is_err());
        }
    }
}
