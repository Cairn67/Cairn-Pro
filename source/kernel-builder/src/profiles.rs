// SPDX-License-Identifier: GPL-3.0-only
// Multi-device profile registry and selection.
//

// Built-in kernel family profiles.
use std::io::{self, IsTerminal, Write};

pub struct Profile {
    
    pub name: &'static str,
    
    pub stock_sha1: &'static [&'static str],
    
    pub kernel_len: usize,
    
    pub runtime_image: u64,
    
    pub base: usize,
    
    pub slot: usize,
    
    pub pac_prepared: bool,
    
    pub pac_sites: &'static [usize],
    
    pub hooks: &'static [(usize, u32, &'static str, bool)],
    
    pub boot_hook: (usize, u32),
    
    pub relatives: &'static [(&'static str, usize)],
    
    pub targets: &'static [(&'static str, usize)],
    
    pub bootstrap_ready: bool,
}

impl Profile {
    
    pub fn missing_targets(&self) -> Vec<&'static str> {
        self.targets
            .iter()
            .filter(|(_, a)| *a == 0)
            .map(|(n, _)| *n)
            .collect()
    }

    pub fn is_usable(&self) -> bool {
        self.bootstrap_ready && self.missing_targets().is_empty()
    }
}

pub const LEGACY: &str = "OnePlus Turbo 6 16.0.2.407";
pub const TURBO6_170: &str = "OnePlus Turbo 6 17.0.0.100";

static P16: Profile = Profile {
    name: LEGACY,
    stock_sha1: &[
        "bb4761e69ae7d2820ea214bfdd4fef72ff5c30d9",
        "ee05514a43870690e086872ae90b6db1d3e68ba3",
    ],
    kernel_len: 0x22f6a00,
    runtime_image: 0x2390000,
    base: 0x108daa0,
    slot: 0x22f6a00,
    pac_prepared: true,
    pac_sites: &[
        0xea9414, 0xea9548, 0xea9570, 0xea981c, 0xea98b8, 0xea9a40, 0xea9a68,
        0xea9b74, 0xea9b80, 0xea9cb0,
    ],
    hooks: &[
        (0x6368a4, 0xd10543ff, "cav_xperm_entry", false),
        (0x63da08, 0x97ffe6d7, "ca_mmap_avc_entry", true),
        (0x63d698, 0xd503233f, "cr_tty_entry", false),
        (0x62f03c, 0xd503233f, "cairn_bprm", false),
        (0x63bda4, 0xd10143ff, "ca_selinux_inode_entry", false),
        (0x637568, 0xd101c3ff, "cairn_avc", false),
        (0x6373b4, 0xa9bb7bfd, "cairn_avcn", false),
        (0xcdbc0, 0xa9ba7bfd, "cairn_prctl", false),
        (0x3dab80, 0xd503233f, "cairn_stat", false),
        (0x3def58, 0xd101c3ff, "cairn_exec", false),
        (0x3d1910, 0xd10283ff, "cairn_vread", false),
        (0x3d0aa8, 0xd101c3ff, "cairn_faccess", false),
        (0x630720, 0xd503233f, "cp_getattr_entry", false),
        (0x631920, 0xd503233f, "cp_open_entry", false),
        (0x3eeff8, 0xd503233f, "cp_iterate_entry", false),
        (0x63c164, 0xd503233f, "ca_getattr_entry", false),
        (0x3e2324, 0xd503233f, "ca_generic_entry", false),
        (0x63df30, 0x97ffe58d, "ca_file_avc_entry", true),
        (0x6425dc, 0x97ffd3e2, "ca_file_data_entry", true),
    ],
    boot_hook: (0x1ce1844, 0x978cce3d),
    relatives: &[
        ("ca_mmap_return_delta", 0x642ef0),
        ("cairn_wl_slot_delta", 0x22f6a00),
        ("cp_slot_delta", 0x22f6a08),
        ("ca_slot_delta", 0x22f6a10),
        ("cr_blob_delta", 0x1665080),
    ],
    
    targets: &[
        ("cl_allocate", 0x2fa64),
        ("cl_free", 0x185d34),
        ("cl_ro", 0x3d128),
        ("cl_x", 0x3d3b4),
        ("cl_nx", 0x3d38c),
        ("cl_cache", 0x38cdc),
        ("cl_wait_initramfs", 0x15138),
        ("cl_file_open", 0x3cfee4),
        ("cl_file_close", 0x3d013c),
        ("cl_file_read", 0x3d1858),
        ("cairn_wl_copy", 0x101ebc0),
        ("cairn_wl_alloc", 0x35c688),
        ("cairn_wl_free", 0x35b930),
        ("cairn_pd", 0xe2968),
        ("cairn_cm", 0xe2f10),
        ("cairn_abort_creds", 0xe28b4),
        ("cairn_printk", 0x104da58),
        ("cairn_vr_bc", 0x101eec0),
        ("cairn_fac_scf", 0x7b1e0c),
        ("cp_lock", 0x1064e7c),
        ("cp_unlock", 0x1065020),
        ("cl_buffer_alloc", 0x3218ec),
        ("cl_buffer_free", 0x321c2c),
        ("cp_resolve", 0x422f54),
        ("cr_context", 0x633800),
        ("cav_policy_seq", 0x637664),
        ("cr_blob", 0x1665080),
        ("ca_mmap_return", 0x642ef0),
    ],
    bootstrap_ready: true,
};

static P17: Profile = Profile {
    name: TURBO6_170,
    stock_sha1: &["7741871b798165bd66f0949fd9bcc5b4f4364b75"],
    kernel_len: 0x2349a00,
    runtime_image: 0x23f0000,
    
    base: 0x10adaa0,
    
    slot: 0x2349a00,
    
    pac_prepared: false,
    pac_sites: &[
        0xebe754, 0xebe888, 0xebe8b0, 0xebeb5c, 0xebebf8, 0xebed80, 0xebeda8,
        0xebeeb4, 0xebeec0, 0xebeff0,
    ],
    hooks: &[
        (0x641788, 0xd10543ff, "cav_xperm_entry", false),
        (0x648ae4, 0x97ffe6cd, "ca_mmap_avc_entry", true),
        (0x648774, 0xd503233f, "cr_tty_entry", false),
        (0x639f34, 0xd503233f, "cairn_bprm", false),
        (0x646e80, 0xd10143ff, "ca_selinux_inode_entry", false),
        (0x64261c, 0xd101c3ff, "cairn_avc", false),
        (0x642468, 0xa9bb7bfd, "cairn_avcn", false),
        (0xcf1b0, 0xa9ba7bfd, "cairn_prctl", false),
        (0x3e1744, 0xd503233f, "cairn_stat", false),
        (0x3e5b50, 0xd101c3ff, "cairn_exec", false),
        (0x3d845c, 0xd10283ff, "cairn_vread", false),
        (0x3d75f4, 0xd101c3ff, "cairn_faccess", false),
        (0x63b618, 0xd503233f, "cp_getattr_entry", false),
        (0x63c818, 0xd503233f, "cp_open_entry", false),
        (0x3f5c40, 0xd503233f, "cp_iterate_entry", false),
        (0x647240, 0xd503233f, "ca_getattr_entry", false),
        (0x3e8f1c, 0xd503233f, "ca_generic_entry", false),
        (0x64900c, 0x97ffe583, "ca_file_avc_entry", true),
        (0x64d6b8, 0x97ffd3d8, "ca_file_data_entry", true),
    ],
    boot_hook: (0x1d21a50, 0x978bcdba),
    relatives: &[
        
        ("ca_mmap_return_delta", 0x64dfcc),
        
        ("cairn_wl_slot_delta", 0x2349a00),
        ("cp_slot_delta", 0x2349a08),
        ("ca_slot_delta", 0x2349a10),
        
        ("cr_blob_delta", 0x1698e48),
    ],
    targets: &[
        ("cl_allocate", 0x2fbdc),
        ("cl_free", 0x188a9c),
        ("cl_ro", 0x3d320),
        ("cl_x", 0x3d5ac),
        ("cl_nx", 0x3d584),
        ("cl_cache", 0x38ebc),
        ("cl_wait_initramfs", 0x15138),
        ("cl_file_open", 0x3d6a30),
        ("cl_file_close", 0x3d6c88),
        ("cl_file_read", 0x3d83a4),
        ("cairn_wl_copy", 0x1037300),
        ("cairn_wl_alloc", 0x361e94),
        ("cairn_wl_free", 0x36113c),
        ("cairn_pd", 0xe4050),
        ("cairn_cm", 0xe45f8),
        ("cairn_abort_creds", 0xe3f9c),
        ("cairn_printk", 0x10661d8),
        ("cairn_vr_bc", 0x1037600),
        ("cairn_fac_scf", 0x7be330),
        ("cp_lock", 0x107dd70),
        ("cp_unlock", 0x107df14),
        ("cl_buffer_alloc", 0x326788),
        ("cl_buffer_free", 0x326ac8),
        ("cp_resolve", 0x429aa0),
        ("cr_context", 0x63e6f8),
        ("cav_policy_seq", 0x642718),
        ("cr_blob", 0x1698e48),
        ("ca_mmap_return", 0x64dfcc),
    ],
    bootstrap_ready: true,
};

pub const ACE6_170: &str = "OnePlus Ace6 17.0.0.104 [experimental/QEMU-only]";

static ACE6: Profile = Profile {
    name: ACE6_170,
    stock_sha1: &["7741871b798165bd66f0949fd9bcc5b4f4364b75"],
    kernel_len: 0x2349a00,
    runtime_image: 0x23f0000,
    
    base: 0x10adaa0,
    
    slot: 0x2349a00,
    
    pac_prepared: false,
    pac_sites: &[
        0xebe754, 0xebe888, 0xebe8b0, 0xebeb5c, 0xebebf8, 0xebed80, 0xebeda8,
        0xebeeb4, 0xebeec0, 0xebeff0,
    ],
    hooks: &[
        (0x641788, 0xd10543ff, "cav_xperm_entry", false),
        (0x648ae4, 0x97ffe6cd, "ca_mmap_avc_entry", true),
        (0x648774, 0xd503233f, "cr_tty_entry", false),
        (0x639f34, 0xd503233f, "cairn_bprm", false),
        (0x646e80, 0xd10143ff, "ca_selinux_inode_entry", false),
        (0x64261c, 0xd101c3ff, "cairn_avc", false),
        (0x642468, 0xa9bb7bfd, "cairn_avcn", false),
        (0xcf1b0, 0xa9ba7bfd, "cairn_prctl", false),
        (0x3e1744, 0xd503233f, "cairn_stat", false),
        (0x3e5b50, 0xd101c3ff, "cairn_exec", false),
        (0x3d845c, 0xd10283ff, "cairn_vread", false),
        (0x3d75f4, 0xd101c3ff, "cairn_faccess", false),
        (0x63b618, 0xd503233f, "cp_getattr_entry", false),
        (0x63c818, 0xd503233f, "cp_open_entry", false),
        (0x3f5c40, 0xd503233f, "cp_iterate_entry", false),
        (0x647240, 0xd503233f, "ca_getattr_entry", false),
        (0x3e8f1c, 0xd503233f, "ca_generic_entry", false),
        (0x64900c, 0x97ffe583, "ca_file_avc_entry", true),
        (0x64d6b8, 0x97ffd3d8, "ca_file_data_entry", true),
    ],
    boot_hook: (0x1d21a50, 0x978bcdba),
    relatives: &[
        
        ("ca_mmap_return_delta", 0x64dfcc),
        
        ("cairn_wl_slot_delta", 0x2349a00),
        ("cp_slot_delta", 0x2349a08),
        ("ca_slot_delta", 0x2349a10),
        
        ("cr_blob_delta", 0x1698e48),
    ],
    targets: &[
        ("cl_allocate", 0x2fbdc),
        ("cl_free", 0x188a9c),
        ("cl_ro", 0x3d320),
        ("cl_x", 0x3d5ac),
        ("cl_nx", 0x3d584),
        ("cl_cache", 0x38ebc),
        ("cl_wait_initramfs", 0x15138),
        ("cl_file_open", 0x3d6a30),
        ("cl_file_close", 0x3d6c88),
        ("cl_file_read", 0x3d83a4),
        ("cairn_wl_copy", 0x1037300),
        ("cairn_wl_alloc", 0x361e94),
        ("cairn_wl_free", 0x36113c),
        ("cairn_pd", 0xe4050),
        ("cairn_cm", 0xe45f8),
        ("cairn_abort_creds", 0xe3f9c),
        ("cairn_printk", 0x10661d8),
        ("cairn_vr_bc", 0x1037600),
        ("cairn_fac_scf", 0x7be330),
        ("cp_lock", 0x107dd70),
        ("cp_unlock", 0x107df14),
        ("cl_buffer_alloc", 0x326788),
        ("cl_buffer_free", 0x326ac8),
        ("cp_resolve", 0x429aa0),
        ("cr_context", 0x63e6f8),
        ("cav_policy_seq", 0x642718),
        ("cr_blob", 0x1698e48),
        ("ca_mmap_return", 0x64dfcc),
    ],
    bootstrap_ready: true,
};

pub static PROFILES: &[&Profile] = &[&P16, &P17, &ACE6];

pub fn by_name(name: &str) -> Option<&'static Profile> {
    PROFILES.iter().copied().find(|p| p.name == name)
}

pub fn by_sha1(_sha1: &str) -> Option<&'static Profile> {
    None
}

pub fn is_static(name: &str) -> bool {
    PROFILES.iter().any(|p| p.name == name)
}

pub const DYNAMIC: &str = "动态修补";
pub fn is_dynamic(name: &str) -> bool { name == DYNAMIC || name == "dynamic" }
pub fn dynamic_unavailable() -> ! {
    eprintln!("动态修补 [实验/未就绪]: runtime resolver and compatible loader are not implemented; refusing to write any image. No fixed-device fallback.");
    std::process::exit(5);
}
pub enum Choice {
    Dynamic,
    Named(&'static Profile),
    Cancelled,
}

fn describe(p: &Profile, index: usize) {
    let state = if p.is_usable() {
        "ready"
    } else if !p.bootstrap_ready {
        "patch table not built"
    } else {
        "missing kernel addresses"
    };
    println!("  {}. {} [{}]", index + 1, p.name, state);
    println!(
        "     kernel 0x{:x} bytes, runtime image 0x{:x}, slot 0x{:x}",
        p.kernel_len, p.runtime_image, p.slot
    );
    if !p.is_usable() {
        let missing = p.missing_targets();
        if !missing.is_empty() {
            println!("     unresolved: {}", missing.join(", "));
        }
    }
}

pub fn prompt() -> Choice {
    println!("\nSelect the patch scheme for this device:\n");
    for (i, p) in PROFILES.iter().enumerate() {
        describe(p, i);
    }
    println!("  {}. {} [实验/未就绪]", PROFILES.len() + 1, DYNAMIC);
    print!(
        "\nEnter 1-{} or a name, empty to abort: ",
        PROFILES.len() + 1
    );
    let _ = io::stdout().flush();

    let mut line = String::new();
    if io::stdin().read_line(&mut line).is_err() {
        return Choice::Cancelled;
    }
    let answer = line.trim();
    if answer.is_empty() {
        return Choice::Cancelled;
    }
    if is_dynamic(answer) { return Choice::Dynamic; }
    if let Ok(n) = answer.parse::<usize>() {
        if n == PROFILES.len() + 1 { return Choice::Dynamic; }
        if n >= 1 && n <= PROFILES.len() {
            return Choice::Named(PROFILES[n - 1]);
        }
        println!("No scheme numbered {n}.");
        return Choice::Cancelled;
    }
    match by_name(answer) {
        Some(p) => Choice::Named(p),
        None => {
            println!("Unknown scheme: {answer}");
            Choice::Cancelled
        }
    }
}

pub fn select(explicit: Option<&str>, sha1_of_stock: impl FnOnce() -> Option<String>) -> Choice {
    if let Some(name) = explicit {
        if is_dynamic(name) { return Choice::Dynamic; }
        return match by_name(name) {
            Some(p) => Choice::Named(p),
            None => {
                println!("Unknown scheme: {name}");
                println!("Known schemes:");
                for p in PROFILES {
                    println!("  {}", p.name);
                }
                Choice::Cancelled
            }
        };
    }
    if let Some(sha1) = sha1_of_stock() {
        if let Some(p) = by_sha1(&sha1) {
            println!("Detected {} from the stock kernel fingerprint.", p.name);
            return Choice::Named(p);
        }
    }
    if !io::stdin().is_terminal() {
        println!("No scheme given and the stock kernel is not a known device.");
        return Choice::Cancelled;
    }
    prompt()
}

pub fn validate(p: &Profile, kernel: &[u8]) -> Result<(), String> {
    if kernel.len() != p.kernel_len {
        return Err(format!(
            "kernel is 0x{:x} bytes, profile expects 0x{:x}",
            kernel.len(),
            p.kernel_len
        ));
    }
    let limit = 0x2560usize;
    let end = p.base.checked_add(limit).ok_or("base overflow")?;
    if end > kernel.len() {
        return Err(format!("bootstrap cave 0x{:x}..0x{:x} outside kernel", p.base, end));
    }
    if kernel[p.base..end].iter().any(|b| *b != 0) {
        return Err("bootstrap cave is not zero-filled".into());
    }
    
    if p.slot % 8 != 0 {
        return Err(format!("slot 0x{:x} is not eight-byte aligned", p.slot));
    }
    let needed = p.slot + 24 + 56;
    let image = p.runtime_image as usize;
    if needed > image {
        return Err(format!(
            "runtime slot ends at 0x{needed:x}, past the runtime image 0x{image:x}"
        ));
    }
    let mut seen = std::collections::HashSet::new();
    for &(off, _w, name, _l) in p.hooks {
        if !seen.insert(off) {
            return Err(format!("duplicate hook site 0x{off:x} ({name})"));
        }
        if off % 4 != 0 || off + 4 > kernel.len() {
            return Err(format!("hook {name} at 0x{off:x} is out of range"));
        }
    }
    if !seen.insert(p.boot_hook.0) {
        return Err(format!("boot hook collides at 0x{:x}", p.boot_hook.0));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_profile_is_describable_and_unique() {
        let mut names = std::collections::HashSet::new();
        for p in PROFILES {
            assert!(names.insert(p.name), "duplicate profile name {}", p.name);
            assert!(!p.stock_sha1.is_empty());
            for s in p.stock_sha1 {
                assert_eq!(s.len(), 40, "{} has a malformed sha1", p.name);
            }
            assert_eq!(p.hooks.len(), 19, "{} must patch 19 hooks", p.name);
            assert_eq!(p.pac_sites.len(), 10);
        }
    }

    #[test]
    fn shipped_profile_is_usable_and_the_new_one_is_honest() {
        let legacy = by_name(LEGACY).unwrap();
        assert!(legacy.is_usable());
        assert_eq!(legacy.base, 0x108daa0);
        assert_eq!(legacy.slot, 0x22f6a00);
        assert!(legacy.pac_prepared);

        let turbo = by_name(TURBO6_170).unwrap();
        
        assert!(turbo.is_usable());
        assert!(turbo.missing_targets().is_empty());
        assert!(!turbo.pac_prepared);
    }

    #[test]
    fn fingerprint_lookup_covers_both_devices() {
        
        for sha1 in [
            "ee05514a43870690e086872ae90b6db1d3e68ba3",
            "7741871b798165bd66f0949fd9bcc5b4f4364b75",
            "0000000000000000000000000000000000000000",
        ] {
            assert!(by_sha1(sha1).is_none(), "{sha1} must not select a scheme");
        }
        
        assert_eq!(by_name(LEGACY).unwrap().name, LEGACY);
        assert_eq!(by_name(TURBO6_170).unwrap().name, TURBO6_170);
        assert!(is_static(LEGACY) && is_static(TURBO6_170));
        assert!(!is_static(DYNAMIC));
    }

    #[test]
    fn slot_relatives_track_the_slot_base() {
        for p in PROFILES {
            let slots: Vec<usize> = p
                .relatives
                .iter()
                .filter(|(n, _)| n.ends_with("slot_delta"))
                .map(|(_, a)| *a)
                .collect();
            assert_eq!(slots, vec![p.slot, p.slot + 8, p.slot + 16], "{}", p.name);
        }
    }

    #[test]
    fn selection_rejects_unknown_names() {
        assert!(matches!(select(Some("nope"), || None), Choice::Cancelled));
        assert!(matches!(
            select(Some(TURBO6_170), || None),
            Choice::Named(p) if p.name == TURBO6_170
        ));
    }
}
#[cfg(test)] mod ace6_tests {
 use super::*;
 #[test] fn ace6_uses_exact_shared_kernel() {
 let p=by_name(ACE6_170).unwrap(); assert!(p.is_usable()); assert_eq!(p.stock_sha1,P17.stock_sha1); assert_eq!(p.hooks,P17.hooks); assert_eq!(p.base,P17.base); assert_eq!(p.boot_hook,P17.boot_hook);
 }
 #[test] fn dynamic_is_separate_and_selectable() {assert!(matches!(select(Some("dynamic"),||panic!("must not read input")),Choice::Dynamic)); assert_eq!(PROFILES.last().unwrap().name,ACE6_170);}
}
