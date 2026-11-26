// SPDX-License-Identifier: GPL-3.0-only
// Candidate-only placement discovery. This does not authorize image writing.
// Chooses where the payload is placed in the image.
use crate::ksyms::Symbols;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Placement {
    pub base: usize,
    pub capacity: usize,
    pub cave_end: usize,
    pub hyp_text_end: usize,
    pub slot: usize,
    pub state: usize,
    pub reserved_end: usize,
    pub bss_start: usize,
    pub runtime_image: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PlacementError {
    MissingOrAmbiguous(&'static str),
    Invalid(&'static str),
}
impl core::fmt::Display for PlacementError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            Self::MissingOrAmbiguous(s) => write!(f, "missing or ambiguous boundary symbol {s}"),
            Self::Invalid(s) => write!(f, "placement rejected: {s}"),
        }
    }
}
fn unique(symbols: &Symbols, name: &'static str) -> Result<usize, PlacementError> {
    let hits = symbols.addrs_of(name);
    let unique: std::collections::BTreeSet<_> = hits.into_iter().collect();
    if unique.len() != 1 { return Err(PlacementError::MissingOrAmbiguous(name)); }
    Ok(*unique.iter().next().unwrap() as usize)
}
pub fn derive(kernel: &[u8], symbols: &Symbols, capacity: usize) -> Result<Placement, PlacementError> {
    if kernel.len() < 64 || kernel.get(56..60) != Some(&b"ARMd"[..]) {
        return Err(PlacementError::Invalid("not an uncompressed ARM64 Image"));
    }
    if capacity == 0 || capacity % 8 != 0 { return Err(PlacementError::Invalid("capacity alignment")); }
    let flags = u64::from_le_bytes(kernel[24..32].try_into().unwrap());
    if flags & 1 != 0 { return Err(PlacementError::Invalid("big-endian Image")); }
    if (flags >> 1) & 3 != 1 { return Err(PlacementError::Invalid("4K page size required")); }
    let runtime_image = u64::from_le_bytes(kernel[16..24].try_into().unwrap()) as usize;
    let hyp_text_end = unique(symbols, "__hyp_text_end")?;
    let cave_end = unique(symbols, "_etext")?;
    let file_end = unique(symbols, "_edata")?;
    let bss_start = unique(symbols, "__bss_start")?;
    let bss_stop = unique(symbols, "__bss_stop")?;
    let image_end = unique(symbols, "_end")?;
    if hyp_text_end > cave_end || cave_end > kernel.len() || cave_end % 4096 != 0 {
        return Err(PlacementError::Invalid("executable boundary order/alignment"));
    }
    let base = cave_end.checked_sub(capacity).ok_or(PlacementError::Invalid("cave underflow"))?;
    if base < hyp_text_end { return Err(PlacementError::Invalid("cave overlaps hyp text")); }
    if kernel[base..cave_end].iter().any(|b| *b != 0) { return Err(PlacementError::Invalid("cave bytes are not all zero")); }
    if symbols.addresses.iter().any(|a| base <= *a as usize && (*a as usize) < cave_end) {
        return Err(PlacementError::Invalid("native symbol lies inside cave"));
    }
    if file_end != kernel.len() || file_end % 8 != 0 { return Err(PlacementError::Invalid("_edata/file-end mismatch or slot alignment")); }
    if !(file_end <= bss_start && bss_start <= bss_stop && bss_stop <= runtime_image && runtime_image == image_end) {
        return Err(PlacementError::Invalid("inconsistent BSS/runtime boundaries"));
    }
    let state = file_end.checked_add(24).ok_or(PlacementError::Invalid("slot overflow"))?;
    let reserved_end = state.checked_add(56).ok_or(PlacementError::Invalid("state overflow"))?;
    if reserved_end > bss_start { return Err(PlacementError::Invalid("runtime state overlaps native BSS")); }
    Ok(Placement { base, capacity, cave_end, hyp_text_end, slot:file_end, state, reserved_end, bss_start, runtime_image })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ksyms;
    fn samples() -> Vec<(&'static str,usize,usize)> {
        vec![("1ce5a4f8b813ed21",0x102daa0,0x220ba00),("2178360d572d1f63",0x10adaa0,0x2349a00),("888332748bd958be",0x129daa0,0x2753a00)]
    }
    #[test]
    fn discovers_placement_on_three_real_kernels() {
        let root=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        for (dir,base,slot) in samples() {
            let kernel=std::fs::read(root.join(dir).join("kernel.bin")).unwrap();
            let symbols=ksyms::discover(&kernel).unwrap();
            let p=derive(&kernel,&symbols,0x2560).unwrap_or_else(|e|panic!("{dir}: {e}"));
            assert_eq!((p.base,p.slot),(base,slot),"{dir}");
            assert!(p.reserved_end<=p.bss_start);
        }
    }
    #[test]
    fn refuses_zero_or_overlapping_candidates() {
        let root=std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic/1ce5a4f8b813ed21/kernel.bin");
        let kernel=std::fs::read(root).unwrap();
        let symbols=ksyms::discover(&kernel).unwrap();
        assert!(derive(&kernel,&symbols,0).is_err());
        assert!(derive(&kernel,&symbols,0x10000).is_err());
    }
}
