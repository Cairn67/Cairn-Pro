// SPDX-License-Identifier: GPL-3.0-only
// Diagnostic only: print the BTF offsets this build would need for a stat hook that
// reads the name out of a `struct path` instead of a `struct filename`.

// Diagnostic dump of resolved symbol names.
#[cfg(test)]
mod probe {
    #[test]
    #[ignore]
    fn dump_stat_names() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let raw = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&raw).expect("open boot");
        let kernel = &opened.image;
        let (start, end) = crate::btf::locate(kernel).expect("btf section");
        let btf = crate::btf::Btf::parse(kernel, start, end).expect("btf parse");
        const WANT: &[(&str, &str)] = &[
            ("path", "dentry"),
            ("dentry", "d_name"),
            ("dentry", "d_iname"),
            ("dentry", "d_inode"),
            ("qstr", "len"),
            ("qstr", "name"),
            ("filename", "name"),
        ];
        println!("BTF {}", path);
        for (structure, field) in WANT {
            match btf.field(structure, field) {
                Ok(v) => println!("  {structure}.{field:<10} {v:#x} ({v})"),
                Err(e) => println!("  {structure}.{field:<10} ABSENT ({e:?})"),
            }
        }
    }
}
