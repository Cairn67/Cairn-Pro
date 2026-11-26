// SPDX-License-Identifier: GPL-3.0-only
//! Linux 6.1 AVC adapter: prove the supported argument-save shape from the
//! target function, then generate loads from cav_save's 80-byte save frame.

// AVC structure layout used when resolving symbols.
use crate::ksyms::Symbols;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StateLeading {
    pub evidence_at: usize,
}

pub fn detect(kernel: &[u8], symbols: &Symbols) -> Result<StateLeading, String> {
    let mut starts = symbols.addrs_of("avc_has_perm");
    starts.sort_unstable(); starts.dedup();
    if starts.len() != 1 { return Err("AVC_SYMBOL_AMBIGUOUS".into()); }
    let start = starts[0] as usize;
    let end = symbols.addresses.iter().map(|&a| a as usize)
        .filter(|&a| a > start).min().unwrap_or(kernel.len()).min(kernel.len());
    
    let sequence = [0xaa0503f3u32, 0x2a0403f4, 0x2a0303f5,
        0x2a0203f6, 0x2a0103f7, 0xaa0003f8];
    let pattern: Vec<u8> = sequence.iter().flat_map(|w| w.to_le_bytes()).collect();
    let stop = end.min(start.saturating_add(256));
    let mut hits = Vec::new();
    for at in (start..stop).step_by(4) {
        if kernel.get(at..at + pattern.len()) == Some(pattern.as_slice())
            && at + pattern.len() <= stop {
            hits.push(at);
        }
    }
    if hits.len() != 1 { return Err(format!("AVC_STATE_LEADING_SHAPE_UNPROVEN count={}",hits.len())); }
    Ok(StateLeading { evidence_at: hits[0] })
}

impl StateLeading {
    
    pub fn permission_adapter(self) -> [u32; 6] {
        [ldr_w(0,24), ldr_w(1,32), ldr_w(2,40), ldr_w(3,48),
            ldr_x(4,56), 0xd5384105] 
    }
}
fn ldr_w(rt: u32, offset: u32) -> u32 { 0xb94003e0 | ((offset / 4) << 10) | rt }
fn ldr_x(rt: u32, offset: u32) -> u32 { 0xf94003e0 | ((offset / 8) << 10) | rt }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn mapping_uses_saved_arguments_not_state() {
        let abi = StateLeading { evidence_at: 0 };
        let saved = [0xdead0000u64, 101, 202, 11, 0x400, 0xbeef0000];
        let mut got = Vec::new();
        for w in abi.permission_adapter().iter().take(5) {
            assert_eq!((w >> 5) & 31,31); 
            let wide = w & 0x40000000 != 0;
            let offset = ((w >> 10) & 0xfff) * if wide {8} else {4};
            let value = saved[((offset - 16) / 8) as usize];
            got.push(if wide {value} else {value & 0xffffffff});
        }
        assert_eq!(got,[101,202,11,0x400,0xbeef0000]);
    }
    #[test]
    fn real_61_shape_and_other_family_refusal() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic");
        for (sample, accepts) in [("1ce5a4f8b813ed21",true),("2178360d572d1f63",false)] {
            let mut k = std::fs::read(root.join(sample).join("kernel.bin")).expect("kernel fixture");
            let symbols = crate::ksyms::discover(&k).unwrap();
            let result = detect(&k,&symbols);
            assert_eq!(result.is_ok(),accepts,"{sample}: {result:?}");
            if let Ok(abi) = result {
                println!("NATIVE_AVC_STATE_LEADING evidence={:#x} words={:x?}",abi.evidence_at,abi.permission_adapter());
                k[abi.evidence_at] ^= 1;
                assert!(detect(&k,&symbols).is_err(),"mutated ABI evidence must refuse");
            }
        }
    }
}
