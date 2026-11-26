// SPDX-License-Identifier: GPL-3.0-only
// Select a version-family locator before resolving any patch sites.
// Extracts the kernel version from the image.
use crate::{ksyms::Symbols, sites};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Strategy { Linux61, Linux66, Linux612 }

impl Strategy {
    pub fn name(self) -> &'static str {
        match self {
            Self::Linux61 => "linux-6.1-structural",
            Self::Linux66 => "linux-6.6-structural",
            Self::Linux612 => "linux-6.12-structural",
        }
    }
    pub fn resolve(self, kernel: &[u8], symbols: &Symbols) -> Result<Vec<sites::Site>, (String, sites::LocateError)> {
        
        sites::resolve_for(self, kernel, symbols)
    }
    
    pub fn site_table(self) -> &'static [sites::SiteSpec] {
        match self {
            
            Self::Linux61 | Self::Linux66 => sites::HOOK_SITES,
            
            Self::Linux612 => sites::HOOK_SITES_612,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KernelVersion {
    pub release: String,
    pub strategy: Strategy,
}

pub fn detect(kernel: &[u8]) -> Result<KernelVersion, String> {
    if kernel.len() < 64 || kernel.get(56..60) != Some(&b"ARMd"[..]) {
        return Err("version detection requires an uncompressed ARM64 Image".into());
    }
    let prefix = b"Linux version ";
    let mut releases = std::collections::BTreeSet::new();
    for (at, bytes) in kernel.windows(prefix.len()).enumerate() {
        if bytes != prefix { continue; }
        let tail = &kernel[at + prefix.len()..];
        let end = tail.iter().position(|b| *b == 0 || b.is_ascii_whitespace())
            .ok_or("unterminated Linux release token")?;
        if end == 0 || end > 256 { return Err("invalid Linux release token length".into()); }
        let release = std::str::from_utf8(&tail[..end]).map_err(|_| "non-UTF8 Linux release")?;
        if !release.bytes().all(|b| b.is_ascii_graphic()) { return Err("invalid Linux release token".into()); }
        releases.insert(release.to_owned());
    }
    if releases.len() != 1 {
        return Err(format!("expected one distinct Linux release, found {}", releases.len()));
    }
    let release = releases.into_iter().next().unwrap();
    let numeric = release.split('-').next().unwrap();
    let parts: Vec<_> = numeric.split('.').collect();
    if parts.len() != 3 || parts.iter().any(|p| p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit())) {
        return Err(format!("invalid Linux version: {release}"));
    }
    let values: Vec<u32> = parts.iter().map(|p| p.parse::<u32>())
        .collect::<Result<_, _>>().map_err(|_| "Linux version number overflow")?;
    let strategy = match (values[0], values[1]) {
        (6, 1) => Strategy::Linux61,
        (6, 6) => Strategy::Linux66,
        (6, 12) => Strategy::Linux612,
        _ => return Err(format!("no dynamic locator strategy for Linux {}.{}", values[0], values[1])),
    };
    Ok(KernelVersion { release, strategy })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn image(banners: &[&str]) -> Vec<u8> {
        let mut data = vec![0u8; 64];
        data[56..60].copy_from_slice(b"ARMd");
        for banner in banners { data.extend_from_slice(banner.as_bytes()); data.push(0); }
        data
    }
    #[test]
    fn routes_supported_families_and_deduplicates_banners() {
        for (release, want) in [("6.1.141-android14-11", Strategy::Linux61), ("6.6.118-android15-8", Strategy::Linux66), ("6.12.58-android16-6", Strategy::Linux612)] {
            let banner = format!("Linux version {release} (builder)");
            let result = detect(&image(&[&banner, &banner])).unwrap();
            assert_eq!(result.release, release);
            assert_eq!(result.strategy, want);
        }
    }
    #[test]
    fn rejects_missing_conflicting_malformed_and_unsupported_versions() {
        for banners in [vec![], vec!["Linux version 6.1.1 ", "Linux version 6.6.1 "], vec!["Linux version 6.1.bad "], vec!["Linux version 6.13.1 "], vec!["Linux version 6.1.4294967296 "]] {
            assert!(detect(&image(&banners)).is_err(), "{banners:?}");
        }
        assert!(detect(&[0u8; 64]).is_err());
    }
    #[test]
    fn routes_and_locates_all_sites_on_three_real_kernels() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        for (dir, expected) in [("1ce5a4f8b813ed21", Strategy::Linux61), ("2178360d572d1f63", Strategy::Linux66), ("888332748bd958be", Strategy::Linux612)] {
            let kernel = std::fs::read(root.join(dir).join("kernel.bin")).expect("real kernel fixture");
            let version = detect(&kernel).unwrap_or_else(|e| panic!("{dir}: {e}"));
            assert_eq!(version.strategy, expected, "{dir}");
            let symbols = crate::ksyms::discover(&kernel).unwrap();
            let resolved = version.strategy.resolve(&kernel, &symbols).unwrap();
            assert_eq!(resolved.len(), sites::HOOK_SITES.len() + 1, "{dir}");
            println!("{}: {} => {} sites={}", dir, version.release, version.strategy.name(), resolved.len());
        }
    }
}
