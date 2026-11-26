// SPDX-License-Identifier: GPL-3.0-only
// Diagnostic only: report the symbol range `body()` would compute for a function,
// so a locator rule can be checked against what the table actually holds.
// Diagnostic dump of payload body sections.
#[cfg(test)]
mod probe {
    #[test]
    #[ignore]
    fn dump_body_range() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let name = std::env::var("CAIRN_PROBE_FUNC").expect("CAIRN_PROBE_FUNC");
        let raw = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&raw).expect("open boot");
        let k = &opened.image;
        let symbols = crate::ksyms::discover(k).expect("kallsyms");
        let hits = symbols.addrs_of(&name);
        println!("{} hits={:?}", name, hits.iter().map(|a| format!("{a:#x}")).collect::<Vec<_>>());
        let base = hits[0] as usize;
        let mut end = k.len();
        let mut next = None;
        for &a in &symbols.addresses {
            let a = a as usize;
            if a > base && a < end {
                end = a;
                next = Some(a);
            }
        }
        println!("base={:#x} end={:#x} next={:?}", base, end, next.map(|v| format!("{v:#x}")));
        
        let mut inversions = 0;
        for w in symbols.addresses.windows(2) {
            if w[0] > w[1] {
                inversions += 1;
            }
        }
        println!("addresses inversions={inversions} of {}", symbols.addresses.len());
    }
}
