// SPDX-License-Identifier: GPL-3.0-only
// Diagnostic only: list the symbols a target actually carries for one concern,
// so a per-series site table is built from evidence instead of assumption.

// Diagnostic dump of the symbol table.
#[cfg(test)]
mod probe {
    
    #[test]
    #[ignore]
    fn dump_owners() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let list = std::env::var("CAIRN_PROBE_ADDRS").expect("CAIRN_PROBE_ADDRS");
        let raw = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&raw).expect("open boot");
        let symbols = crate::ksyms::discover(&opened.image).expect("kallsyms");
        for item in list.split(',') {
            let item = item.trim();
            if item.is_empty() {
                continue;
            }
            let value = u32::from_str_radix(item.trim_start_matches("0x"), 16).unwrap();
            match symbols.enclosing(value) {
                Some((name, 0)) => {
                    println!("{value:#010x} == START of {}", name.get(1..).unwrap_or(name))
                }
                Some((name, delta)) => println!(
                    "{value:#010x} inside {} +{delta:#x} (no symbol starts here)",
                    name.get(1..).unwrap_or(name)
                ),
                None => println!("{value:#010x} before the first symbol"),
            }
        }
    }

    #[test]
    #[ignore]
    fn dump_symbols() {
        let path = std::env::var("CAIRN_PROBE_BOOT").expect("CAIRN_PROBE_BOOT");
        let raw = std::fs::read(&path).unwrap();
        let opened = crate::kcompress::open_boot(&raw).expect("open boot");
        let k = &opened.image;
        let symbols = crate::ksyms::discover(k).expect("kallsyms");
        println!("SYMBOLS {} count={}", path, symbols.count);

        const WANT: &[&str] = &[
            "vfs_statx", "vfs_statx_path", "vfs_fstatat", "vfs_getattr",
            "vfs_getattr_nosec", "do_statx", "cp_new_stat", "cp_new_statx",
            "__arm64_sys_newfstatat", "__arm64_sys_statx", "__arm64_sys_fstatat64",
            "path_get", "user_path_at_empty", "vfs_stat_setattr_fn",
            
            "vfs_read", "do_faccessat", "do_execveat_common", "iterate_dir",
            "security_file_open", "security_inode_getattr", "security_bprm_check",
            "selinux_inode_permission", "selinux_mmap_file", "selinux_file_ioctl",
            "avc_has_perm", "avc_has_perm_noaudit", "avc_has_extended_perms",
            "file_has_perm", "generic_permission", "selinux_inode_getattr",
            "__arm64_sys_prctl",
        ];
        for name in WANT {
            match symbols.addr_of(name) {
                Some(a) => println!("  {:<28} {:#010x}", name, a),
                None => println!("  {:<28} ABSENT", name),
            }
        }

        println!("\nSTAT-SHAPED SYMBOLS");
        for (name, addr) in symbols.names.iter().zip(&symbols.addresses) {
            let bare = name.get(1..).unwrap_or(name);
            let low = bare.to_ascii_lowercase();
            let hit = (low.contains("stat") && !low.contains("state") && !low.contains("station"))
                || low.contains("getattr");
            if hit {
                println!("  {:<40} {:#010x}", bare, addr);
            }
        }
    }
}
