// SPDX-License-Identifier: GPL-3.0-only
//! Native port of adapt/replay_encoding.py. A branch must use a kernel bridge,
//! never verbatim PC-relative replay. PAC replay still requires correct SP/LR.
// Replays the instructions a hook site overwrote.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Replay {
    Inline(u32),
    Branch { target: usize, linked: bool },
}

pub fn is_pc_relative(word: u32) -> bool {
    let branch = word & 0x7c00_0000 == 0x1400_0000; 
    let adr = word & 0x1f00_0000 == 0x1000_0000; 
    let literal = word & 0x3b00_0000 == 0x1800_0000; 
    branch || adr || literal
}

pub fn is_inline_safe(word: u32) -> bool {
    
    let sp_adjust = word & 0x1f00_0000 == 0x1100_0000
        && (word & 0x1f) == 31
        && (word >> 5) & 0x1f == 31;
    if sp_adjust {
        return false;
    }
    
    let zero_adjust = word & 0x1f00_0000 == 0x1100_0000
        && (word >> 10) & 0xfff == 0
        && (word & 0x1f) == (word >> 5) & 0x1f;
    if zero_adjust {
        return false;
    }
    
    let dp_imm = word & 0x1f00_0000 == 0x1100_0000;
    
    let dp_reg = word & 0x1f00_0000 == 0x0a00_0000;
    
    let ldst_uimm = word & 0x3b00_0000 == 0x3900_0000;
    
    let ldst_multi = word & 0x3b00_0000 == 0x2800_0000;
    dp_imm || dp_reg || ldst_uimm || ldst_multi
}

pub fn derive(word: u32, at: usize) -> Result<Replay, String> {
    if word & 0x7c00_0000 == 0x1400_0000 {
        let displacement = (((word & 0x03ff_ffff) as i32) << 6 >> 6) as i64 * 4;
        let target = (at as i128) + i128::from(displacement);
        let target = usize::try_from(target).map_err(|_| "branch target overflow")?;
        return Ok(Replay::Branch { target, linked: word & 0x8000_0000 != 0 });
    }
    let encoded = if matches!(word & 0xff80_0000, 0x9100_0000 | 0xd100_0000)
        && word & 0x3ff == 0x3ff {
        let immediate = (word >> 10) & 0xfff;
        if immediate == 0 { return Err("zero SP adjustment unsupported".into()); }
        0x9100_03ff | (word & 0x4000_0000) | (word & 0x0040_0000) | (immediate << 10)
    } else if word & 0xff80_0000 == 0xa980_0000 && (word >> 5) & 31 == 31 {
        let rt = word & 31;
        let rt2 = (word >> 10) & 31;
        if rt > 30 || rt2 > 30 { return Err("pair register unsupported".into()); }
        let offset = ((word >> 15) & 0x7f) as i32;
        let signed = (offset << 25 >> 25) * 8;
        0xa980_03e0 | (word & 0x0040_0000)
            | (((signed / 8) as u32 & 0x7f) << 15) | (rt2 << 10) | rt
    } else if word == 0xd503_233f {
        word
    } else if is_inline_safe(word) {
        
        word
    } else {
        return Err(format!("REPLAY_UNSUPPORTED at {at:#x}: {word:#010x}"));
    };
    if encoded != word {
        return Err(format!("REPLAY_ENCODE_MISMATCH {word:#x} != {encoded:#x}"));
    }
    Ok(Replay::Inline(encoded))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn real_kernel_covered_words_roundtrip() {
        for word in [0xd102c3ff, 0xd10143ff, 0xa9ba7bfd, 0xd10543ff,
            0xa9bb7bfd, 0xd10303ff, 0xd105c3ff, 0xd503233f] {
            assert_eq!(derive(word, 0).unwrap(), Replay::Inline(word));
        }
    }
    #[test]
    fn branch_uses_original_pc() {
        assert_eq!(derive(0x94000baa, 0x5ba5c).unwrap(),
            Replay::Branch { target: 0x5e904, linked: true });
        assert_eq!(derive(0x17ffffff, 4).unwrap(),
            Replay::Branch { target: 0, linked: false });
        assert!(derive(0x17ffffff, 0).is_err());
    }
    #[test]
    fn refuses_unknown_and_invalid_registers() {
        for word in [0, 0xd61f0200, 0xd1000000, 0xa9bf7fff] {
            assert!(derive(word, 0).is_err(), "{word:#x}");
        }
    }
    #[test]
    fn three_real_kernel_site_replays() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        for sample in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let kernel = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&kernel).unwrap();
            let version = crate::kernel_version::detect(&kernel).unwrap();
            let sites = version.strategy.resolve(&kernel, &symbols).unwrap();
            for site in &sites {
                derive(site.word, site.offset).unwrap_or_else(|error| panic!("{sample} {}: {error}", site.name));
            }
            println!("NATIVE_REPLAY {} sites={}", version.release, sites.len());
        }
    }
    #[test]
    fn pair_fields_roundtrip() {
        for offset in 0..128 {
            for rt in 0..31 {
                let word = 0xa98003e0 | (offset << 15) | (30 << 10) | rt;
                assert_eq!(derive(word, 0).unwrap(), Replay::Inline(word));
                assert_eq!(derive(word | 0x00400000, 0).unwrap(), Replay::Inline(word | 0x00400000));
            }
        }
    }
}
