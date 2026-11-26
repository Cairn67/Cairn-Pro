// SPDX-License-Identifier: GPL-3.0-only
//! Native machine-code generation for in-kernel bridges. No assembler process.
// Bridge between parsed image data and patch planning.
use crate::replay::Replay;

pub fn branch(at: usize, target: usize, linked: bool) -> Result<u32, String> {
    if at % 4 != 0 || target % 4 != 0 { return Err("BRANCH_UNALIGNED".into()); }
    let delta = target as i128 - at as i128;
    if !(-(1i128 << 27)..(1i128 << 27)).contains(&delta) {
        return Err("BRANCH_OUT_OF_RANGE".into());
    }
    Ok(if linked {0x94000000} else {0x14000000} | ((delta / 4) as u32 & 0x03ffffff))
}

pub fn fallback(original: u32, original_at: usize, bridge_at: usize, image_len: usize)
    -> Result<Vec<u32>, String> {
    if original_at.checked_add(4).map_or(true, |end| end > image_len)
        || bridge_at % 4 != 0 { return Err("FALLBACK_INVALID_EXTENT".into()); }
    match crate::replay::derive(original,original_at)? {
        Replay::Branch {target,..} => {
            if target.checked_add(4).map_or(true,|end| end > image_len) {
                return Err("FALLBACK_TARGET_OUTSIDE_IMAGE".into());
            }
            Ok(vec![branch(bridge_at,target,false)?])
        }
        Replay::Inline(word) => {
            let continuation = original_at.checked_add(4).ok_or("continuation overflow")?;
            if continuation >= image_len { return Err("FALLBACK_CONTINUATION_OUTSIDE_IMAGE".into()); }
            Ok(vec![word,branch(bridge_at.checked_add(4).ok_or("bridge overflow")?,continuation,false)?])
        }
    }
}

pub fn kernel_target(at: usize,target: usize) -> Result<[u32;2],String> {
    Ok([0xd50324df,branch(at.checked_add(4).ok_or("bridge overflow")?,target,false)?])
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn checks_signed_range_and_alignment() {
        assert_eq!(branch(4,0,false).unwrap(),0x17ffffff);
        assert_eq!(branch(0,4,true).unwrap(),0x94000001);
        assert!(branch(0,1,false).is_err());
        assert!(branch(0,1<<27,false).is_err());
        assert!(branch(1<<27,0,false).is_ok());
    }
    #[test]
    fn preserves_original_call_lr() {
        let words=fallback(0x94000baa,0x5ba5c,0x102e000,0x220ba00).unwrap();
        assert_eq!(words.len(),1);
        assert_eq!(crate::replay::derive(words[0],0x102e000).unwrap(),
            Replay::Branch {target:0x5e904,linked:false});
    }
    #[test]
    fn replays_prologue_then_continues() {
        let words=fallback(0xd10303ff,0x5e908,0x102e000,0x220ba00).unwrap();
        assert_eq!(words[0],0xd10303ff);
        assert_eq!(crate::replay::derive(words[1],0x102e004).unwrap(),
            Replay::Branch {target:0x5e90c,linked:false});
        assert!(fallback(0xd10303ff,0,0x100,4).is_err());
    }
    #[test]
    fn real_61_all_fallbacks() {
        let root=std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../../research/three-device-dynamic/1ce5a4f8b813ed21/kernel.bin");
        let k=std::fs::read(root).expect("6.1 kernel fixture");
        let symbols=crate::ksyms::discover(&k).unwrap();
        let version=crate::kernel_version::detect(&k).unwrap();
        let sites=version.strategy.resolve(&k,&symbols).unwrap();
        let placement=crate::placement::derive(&k,&symbols,0x2560).unwrap();
        for (i,site) in sites.iter().enumerate() {
            let at=placement.base+i*16;
            let generated=fallback(site.word,site.offset,at,k.len()).unwrap();
            assert!(!generated.is_empty());
            let last_at=at+4*(generated.len()-1);
            let expected=match crate::replay::derive(site.word,site.offset).unwrap() {
                Replay::Branch {target,..}=>target,
                Replay::Inline(_)=>site.offset+4,
            };
            assert_eq!(crate::replay::derive(*generated.last().unwrap(),last_at).unwrap(),
                Replay::Branch {target:expected,linked:false});
        }
        println!("NATIVE_61_FALLBACKS {}",sites.len());
    }
}
