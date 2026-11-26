// SPDX-License-Identifier: GPL-3.0-only
//! Native fixup/relative resolution for a target kernel.
//!

// Applies relocations and site fixups to the payload.
use crate::ksyms::Symbols;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AllocFamily {
    
    ModuleAlloc,
    
    Execmem,
}
impl AllocFamily {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ModuleAlloc => "module_alloc",
            Self::Execmem => "execmem",
        }
    }
}

pub const EXECMEM_MODULE_TEXT: u32 = 0;

pub const KERNEL_TARGETS: &[(&str, &[&str], u32)] = &[
    ("cl_allocate", &["module_alloc"], 0),
    ("cl_free", &["module_memfree"], 0),
    ("cl_ro", &["set_memory_ro"], 0),
    ("cl_x", &["set_memory_x"], 0),
    ("cl_nx", &["set_memory_nx"], 0),
    ("cl_cache", &["caches_clean_inval_pou"], 0),
    ("cl_native_copy", &["__arch_copy_from_user"], 0),
    ("cl_wait_initramfs", &["wait_for_initramfs"], 0),
    ("cl_file_open", &["filp_open"], 0),
    ("cl_file_close", &["filp_close"], 0),
    ("cl_file_read", &["kernel_read"], 0),
    ("cl_buffer_alloc", &["__kmalloc", "__kmalloc_noprof"], 0),
    ("cl_buffer_free", &["kfree"], 0),
    ("cl_print", &["_printk"], 0),
];

pub const FIXUP_OWNERS: &[(&str, &str, u32, bool)] = &[
    ("cav_avc_tail", "avc_has_perm", 8, false),
    ("cav_avcn_tail", "avc_has_perm_noaudit", 12, false),
    ("cav_xperm_tail", "avc_has_extended_perms", 8, false),
    ("cav_policy_seq_tail", "avc_policy_seqno", 0, false),
    ("cav_context_tail", "security_secctx_to_secid", 0, false),
    ("ca_mmap_avc_tail", "avc_has_perm", 0, false),
    ("cr_tty_original", "selinux_file_ioctl", 4, false),
    ("cairn_wl_copy", "__arch_copy_from_user", 0, true),
    ("cairn_wl_alloc", "vmalloc", 0, true),
    ("cairn_wl_free", "vfree", 0, true),
    ("cairn_pd", "prepare_creds", 0, true),
    ("cairn_cm", "commit_creds", 0, true),
    ("cairn_ok_bl", "_printk", 0, true),
    ("cairn_stat_bl", "_printk", 0, true),
    ("cairn_fac_bl", "_printk", 0, true),
    ("cairn_cndstat_bl", "_printk", 0, true),
    ("cairn_vr_blr", "_printk", 0, true),
    ("cairn_vr_be", "_printk", 0, true),
    ("cairn_vr_bc", "__arch_copy_to_user", 0, true),
    ("cairn_fac_scf", "strncpy_from_user", 0, true),
    ("cairn_exec_log", "_printk", 0, true),
    ("cairn_bprm_b", "security_bprm_check", 4, false),
    ("cairn_sip_b", "selinux_inode_permission", 8, false),
    ("cairn_avc_b", "avc_has_perm", 8, false),
    ("cairn_avcn_b", "avc_has_perm_noaudit", 12, false),
    ("cairn_prctl_tail", "__arm64_sys_prctl", 36, false),
    
    ("cairn_stat_getname", "getname_flags", 0, true),
    ("cairn_stat_ret", "vfs_statx", 32, false),
    ("cairn_exec_j", "do_execveat_common", 8, false),
    ("cairn_exec_j2", "do_execveat_common", 8, false),
    ("cairn_fac_j", "do_faccessat", 32, false),
    ("cairn_vr_j", "vfs_read", 28, false),
    ("cp_iterate_tail", "iterate_dir", 4, false),
    ("cp_original_iterate_tail", "iterate_dir", 4, false),
    ("cp_open_tail", "security_file_open", 4, false),
    ("cp_getattr_tail", "security_inode_getattr", 4, false),
    ("cp_lock_target", "_raw_spin_lock_irqsave", 0, false),
    ("cp_unlock_target", "_raw_spin_unlock_irqrestore", 0, false),
    ("cp_alloc_target", "__kmalloc", 0, false),
    ("cp_free_target", "kfree", 0, false),
    ("cp_resolve_target", "d_path", 0, false),
    ("ca_lock_target", "_raw_spin_lock_irqsave", 0, false),
    ("ca_unlock_target", "_raw_spin_unlock_irqrestore", 0, false),
    ("ca_alloc_target", "__kmalloc", 0, false),
    ("ca_free_target", "kfree", 0, false),
    ("ca_resolve_target", "d_path", 0, false),
    ("ca_generic_tail", "generic_permission", 4, false),
    ("ca_file_avc_tail", "avc_has_perm", 0, false),
    ("ca_file_data_tail", "avc_has_perm", 0, false),
    ("ca_getattr_tail", "selinux_inode_getattr", 4, false),
    ("cp_copy_target", "__arch_copy_from_user", 0, true),
    ("ca_copy_target", "__arch_copy_from_user", 0, true),
    ("cr_context_call", "security_secctx_to_secid", 0, true),
    ("cr_prepare_call", "prepare_creds", 0, true),
    ("cr_commit_call", "commit_creds", 0, true),
    ("cr_abort_call", "abort_creds", 0, true),
    ("cairn_abort_creds_call", "abort_creds", 0, true),
];

pub const RELATIVE_OWNERS: &[(&str, Option<(&str, u32)>)] = &[
    ("ca_mmap_return_delta", Some(("file_map_prot_check", 176))),
    ("cairn_wl_slot_delta", None),
    ("cp_slot_delta", None),
    ("ca_slot_delta", None),
    ("cr_blob_delta", Some(("selinux_blob_sizes", 0))),
];

pub const TAIL_OF_HOOK: &[(&str, &str)] = &[
    ("cav_avc_tail", "cairn_avc"),
    ("cav_avcn_tail", "cairn_avcn"),
    ("cav_xperm_tail", "cav_xperm_entry"),
    ("cr_tty_original", "cr_tty_entry"),
    ("cairn_bprm_b", "cairn_bprm"),
    ("cairn_sip_b", "ca_selinux_inode_entry"),
    ("cairn_avc_b", "cairn_avc"),
    ("cairn_avcn_b", "cairn_avcn"),
    ("cairn_exec_j", "cairn_exec"),
    ("cairn_exec_j2", "cairn_exec"),
    ("cp_iterate_tail", "cp_iterate_entry"),
    ("cp_original_iterate_tail", "cp_iterate_entry"),
    ("cp_open_tail", "cp_open_entry"),
    ("cp_getattr_tail", "cp_getattr_entry"),
    ("ca_generic_tail", "ca_generic_entry"),
    ("ca_getattr_tail", "ca_getattr_entry"),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Fixup {
    pub name: &'static str,
    pub target: usize,
    
    pub linked: bool,
    
    pub evidence: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Relative {
    pub name: &'static str,
    
    pub target: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KernelTarget {
    pub name: &'static str,
    pub target: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plan {
    pub fixups: Vec<Fixup>,
    pub relatives: Vec<Relative>,
    pub targets: Vec<KernelTarget>,
    
    pub alloc_family: AllocFamily,
}

fn alloc_family_for(symbols: &Symbols) -> Result<AllocFamily, String> {
    let module_alloc = symbols.addr_of("module_alloc").is_some();
    let module_memfree = symbols.addr_of("module_memfree").is_some();
    let execmem_alloc = symbols.addr_of("execmem_alloc").is_some();
    let execmem_free = symbols.addr_of("execmem_free").is_some();
    match (module_alloc, module_memfree, execmem_alloc, execmem_free) {
        (true, true, false, false) => Ok(AllocFamily::ModuleAlloc),
        (false, false, true, true) => Ok(AllocFamily::Execmem),
        (false, false, false, false) => Err(
            "KERNEL_TARGET_UNRESOLVED cl_allocate: neither module_alloc/module_memfree nor execmem_alloc/execmem_free".into(),
        ),
        _ => Err(format!(
            "KERNEL_ALLOC_FAMILY_AMBIGUOUS module_alloc={module_alloc} module_memfree={module_memfree} execmem_alloc={execmem_alloc} execmem_free={execmem_free}"
        )),
    }
}

fn slot_offsets(name: &str) -> Option<usize> {
    match name {
        "cairn_wl_slot_delta" => Some(0),
        "cp_slot_delta" => Some(8),
        "ca_slot_delta" => Some(16),
        _ => None,
    }
}

fn aliases(symbol: &str) -> &'static [&'static str] {
    match symbol {
        "vmalloc" => &["vmalloc", "vmalloc_noprof"],
        "__kmalloc" => &["__kmalloc", "__kmalloc_noprof"],
        "kmem_cache_alloc" => &["kmem_cache_alloc", "kmem_cache_alloc_noprof"],
        _ => &[],
    }
}

fn resolve_symbol<'a>(symbols: &Symbols, symbol: &'a str) -> Option<(u32, &'a str)> {
    if let Some(addr) = symbols.addr_of(symbol) {
        return Some((addr, symbol));
    }
    
    let table: &'static [&'static str] = aliases(symbol);
    for alias in table {
        if *alias == symbol {
            continue;
        }
        if let Some(addr) = symbols.addr_of(alias) {
            return Some((addr, alias));
        }
    }
    None
}

fn owner_of(symbols: &Symbols, address: usize) -> Option<(&str, u32)> {
    
    let mut best: Option<(u32, &str)> = None;
    for (i, &addr) in symbols.addresses.iter().enumerate() {
        if (addr as usize) <= address {
            let name = symbols.names[i].get(1..).unwrap_or("");
            if best.map_or(true, |(a, _)| addr >= a) {
                best = Some((addr, name));
            }
        }
    }
    best.map(|(addr, name)| (name, (address - addr as usize) as u32))
}

pub fn resolve(
    symbols: &Symbols,
    slot: usize,
    hooks: &[(&str, usize)],
) -> Result<Plan, String> {
    resolve_with_frame(symbols, slot, hooks, None)
}

pub fn resolve_with_frame(
    symbols: &Symbols,
    slot: usize,
    hooks: &[(&str, usize)],
    frame: Option<&crate::frame::Frame>,
) -> Result<Plan, String> {
    resolve_with_context(symbols, slot, hooks, frame, None, None)
}

fn stat_return_anchor(shape: StatShape) -> u32 {
    match shape {
        StatShape::Entry => 32,
        StatShape::AfterPac => 52,
        
        StatShape::LeafEntry => 16,
        
        StatShape::SyscallAfterCall => 16,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StatShape {
    
    Entry,
    
    AfterPac,
    
    LeafEntry,
    
    SyscallAfterCall,
}

pub fn resolve_with_context(
    symbols: &Symbols,
    slot: usize,
    hooks: &[(&str, usize)],
    frame: Option<&crate::frame::Frame>,
    strategy: Option<crate::kernel_version::Strategy>,
    stat_shape: Option<StatShape>,
) -> Result<Plan, String> {
    let hook_offset = |name: &str| hooks.iter().find(|(n, _)| *n == name).map(|(_, o)| *o);
    let mut fixups = Vec::with_capacity(FIXUP_OWNERS.len());
    for (name, symbol, in_function, linked) in FIXUP_OWNERS {
        
        let in_function = if *name == "cairn_prctl_tail" {
            match frame {
                Some(frame) => &frame.continuation,
                None => in_function,
            }
        } else {
            in_function
        };
        
        let stat_ret = if *name == "cairn_stat_ret" {
            let site = hook_offset("cairn_stat");
            if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
                eprintln!(
                    "TRACE cairn_stat_ret site={:?} shape={:?} -> target={:?}",
                    site.map(|s| format!("{s:#x}")),
                    stat_shape,
                    site.zip(stat_shape)
                        .map(|(s, sh)| format!("{:#x}", s + stat_return_anchor(sh) as usize))
                );
            }
            match (site, stat_shape) {
                (Some(site), Some(shape)) => Some(site + stat_return_anchor(shape) as usize),
                _ => None,
            }
        } else {
            None
        };
        let (symbol, site_base): (&'static str, Option<usize>) = (symbol, stat_ret);
        let _ = &site_base;
        
        if let Some(target) = site_base {
            
            fixups.push(Fixup {
                name,
                target,
                linked: *linked,
                evidence: "stat continuation from hook site",
            });
            continue;
        }
        let (base, resolved) = resolve_symbol(symbols, symbol)
            .ok_or_else(|| format!("FIXUP_SYMBOL_ABSENT {name}: {symbol}"))?;
        let base = base as usize;
        let mut target = base + *in_function as usize;
        let mut evidence = "symbol+offset";
        if let Some((_, hook)) = TAIL_OF_HOOK.iter().find(|(n, _)| n == name) {
            let continuation = hook_offset(hook).ok_or_else(|| format!("FIXUP_HOOK_ABSENT {name}: {hook}"))? + 4;
            if continuation == target {
                evidence = "symbol+offset, agrees with hook+4";
            } else {
                
                target = continuation;
                evidence = "hook+4 (prologue frontier moved)";
            }
        }
        
        let owner = owner_of(symbols, target)
            .ok_or_else(|| format!("FIXUP_NO_OWNER {name} at {target:#x}"))?;
        if owner.0 != resolved {
            return Err(format!(
                "FIXUP_OUTSIDE_OWNER {name}: {target:#x} lands in {} +{:#x}, not {resolved}",
                owner.0, owner.1
            ));
        }
        fixups.push(Fixup { name, target, linked: *linked, evidence });
    }

    let mut relatives = Vec::with_capacity(RELATIVE_OWNERS.len());
    for (name, anchor) in RELATIVE_OWNERS {
        if let Some(off) = slot_offsets(name) {
            relatives.push(Relative { name, target: slot + off });
            continue;
        }
        let (symbol, in_function) = (*anchor).ok_or_else(|| format!("RELATIVE_UNRESOLVED {name}"))?;
        let (symbol, in_function) = if *name == "ca_mmap_return_delta" && symbol == "file_map_prot_check" {
            
            ("file_has_perm", 176u32)
        } else {
            (symbol, in_function)
        };
        let base = symbols
            .addr_of(symbol)
            .ok_or_else(|| format!("RELATIVE_SYMBOL_ABSENT {name}: {symbol}"))? as usize;
        relatives.push(Relative { name, target: base + in_function as usize });
    }

    let mut targets = Vec::with_capacity(KERNEL_TARGETS.len());
    let mut alloc_family = None;
    for (name, aliases, offset) in KERNEL_TARGETS {
        
        if *name == "cl_allocate" || *name == "cl_free" {
            if alloc_family.is_none() {
                alloc_family = Some(alloc_family_for(symbols)?);
            }
            let family = alloc_family.unwrap();
            let resolved = match family {
                AllocFamily::ModuleAlloc => {
                    let symbol = if *name == "cl_allocate" { "module_alloc" } else { "module_memfree" };
                    symbols.addr_of(symbol).map(|a| a as usize)
                }
                AllocFamily::Execmem => {
                    
                    let symbol = if *name == "cl_allocate" { "execmem_alloc" } else { "execmem_free" };
                    symbols.addr_of(symbol).map(|a| a as usize + *offset as usize)
                }
            };
            let target = resolved.ok_or_else(|| format!("KERNEL_TARGET_UNRESOLVED {name}: {}", aliases.join(",")))?;
            targets.push(KernelTarget { name, target });
            continue;
        }
        let mut resolved = None;
        for alias in *aliases {
            if let Some(addr) = symbols.addr_of(alias) {
                resolved = Some(addr as usize + *offset as usize);
                break;
            }
        }
        let target = resolved.ok_or_else(|| format!("KERNEL_TARGET_UNRESOLVED {name}: {}", aliases.join(",")))?;
        targets.push(KernelTarget { name, target });
    }
    Ok(Plan { fixups, relatives, targets, alloc_family: alloc_family.unwrap_or(AllocFamily::ModuleAlloc) })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn sample(dir: &str) -> (Vec<u8>, crate::ksyms::Symbols) {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        let kernel = std::fs::read(root.join(dir).join("kernel.bin")).expect("kernel fixture");
        let symbols = crate::ksyms::discover(&kernel).expect("kallsyms");
        (kernel, symbols)
    }

    #[test]
    fn linux61_resolves_every_fixup_and_relative() {
        let (kernel, symbols) = sample("1ce5a4f8b813ed21");
        let sites = crate::sites::resolve_all(&kernel, &symbols).expect("sites");
        let hooks: Vec<(&str, usize)> = sites.iter().map(|s| (s.name, s.offset)).collect();
        let placement = crate::placement::derive(&kernel, &symbols, 0x2560).expect("placement");
        let plan = resolve(&symbols, placement.slot, &hooks).expect("fixup plan");
        assert_eq!(plan.fixups.len(), FIXUP_OWNERS.len());
        assert_eq!(plan.relatives.len(), RELATIVE_OWNERS.len());
        assert_eq!(plan.targets.len(), KERNEL_TARGETS.len());
        
        assert!(symbols.addr_of("file_has_perm").is_some());
        let rel = plan.relatives.iter().find(|r| r.name == "ca_mmap_return_delta").unwrap();
        assert_eq!(rel.target, symbols.addr_of("file_has_perm").unwrap() as usize + 176);
        
        for name in ["cairn_wl_slot_delta", "cp_slot_delta", "ca_slot_delta"] {
            let r = plan.relatives.iter().find(|r| r.name == name).unwrap();
            assert!(r.target >= placement.slot && r.target < placement.slot + 24);
        }
        
        let agrees = plan.fixups.iter().filter(|f| f.evidence.contains("agrees")).count();
        println!("NATIVE_FIXUPS {} agree_with_hook_plus_4={agrees}", plan.fixups.len());
        assert!(agrees >= 10, "expected most tails to agree with hook+4, got {agrees}");
    }

    #[test]
    fn three_kernels_resolve_or_refuse_deliberately() {
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let (kernel, symbols) = sample(dir);
            let sites = crate::sites::resolve_all(&kernel, &symbols).expect("sites");
            let hooks: Vec<(&str, usize)> = sites.iter().map(|s| (s.name, s.offset)).collect();
            let placement = crate::placement::derive(&kernel, &symbols, 0x2560).expect("placement");
            match resolve(&symbols, placement.slot, &hooks) {
                Ok(plan) => {
                    for f in &plan.fixups {
                        assert!(f.target < kernel.len(), "{} outside Image", f.name);
                    }
                    for t in &plan.targets {
                        assert!(t.target < kernel.len(), "{} outside Image", t.name);
                    }
                    println!("NATIVE_FIXUP_PLAN {dir} fixups={} targets={}", plan.fixups.len(), plan.targets.len());
                }
                Err(reason) => println!("NATIVE_FIXUP_PLAN_REFUSED {dir}: {reason}"),
            }
        }
    }

    #[test]
    fn missing_symbol_refuses_instead_of_reusing_reference_address() {
        let (_, symbols) = sample("1ce5a4f8b813ed21");
        let dead = crate::ksyms::Symbols {
            family: symbols.family,
            count: 0,
            count_at: 0,
            addresses: Vec::new(),
            names: Vec::new(),
        };
        let err = resolve(&dead, 0x1000, &[]).unwrap_err();
assert!(err.starts_with("FIXUP_SYMBOL_ABSENT"), "unexpected: {err}");
    }
    
    #[test]
    fn alloc_family_follows_the_target_kernel() {
        
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63"] {
            let (kernel, symbols) = sample(dir);
            let sites = crate::sites::resolve_all(&kernel, &symbols).expect("sites");
            let hooks: Vec<(&str, usize)> = sites.iter().map(|s| (s.name, s.offset)).collect();
            let placement = crate::placement::derive(&kernel, &symbols, 0x2560).expect("placement");
            let plan = resolve(&symbols, placement.slot, &hooks).expect("plan");
            assert_eq!(plan.alloc_family, AllocFamily::ModuleAlloc, "{dir}");
            let allocate = plan.targets.iter().find(|t| t.name == "cl_allocate").unwrap();
            assert_eq!(
                Some(allocate.target as u32),
                symbols.addr_of("module_alloc"),
                "{dir}: cl_allocate must be module_alloc itself, not a stub"
            );
        }
        
        let (kernel, symbols) = sample("888332748bd958be");
        assert!(symbols.addr_of("module_alloc").is_none());
        assert!(symbols.addr_of("module_memfree").is_none());
        assert!(symbols.addr_of("execmem_alloc").is_some());
        assert!(symbols.addr_of("execmem_free").is_some());
        assert_eq!(alloc_family_for(&symbols).unwrap(), AllocFamily::Execmem);
        
        assert_eq!(EXECMEM_MODULE_TEXT, 0);
    }
    
    #[test]
    fn half_an_alloc_pair_is_refused() {
        let (_, symbols) = sample("1ce5a4f8b813ed21");
        
        let names: Vec<String> = symbols.names.clone();
        let addrs = symbols.addresses.clone();
        let keep = |drop: &str| crate::ksyms::Symbols {
            family: symbols.family,
            count: symbols.count,
            count_at: symbols.count_at,
            names: names.iter().zip(&addrs).filter(|(n, _)| n.get(1..) != Some(drop)).map(|(n, _)| n.clone()).collect(),
            addresses: names.iter().zip(&addrs).filter(|(n, _)| n.get(1..) != Some(drop)).map(|(_, a)| *a).collect(),
        };
        
        assert!(symbols.addr_of("module_memfree").is_some());
        assert!(keep("module_memfree").addr_of("module_memfree").is_none());
        let err = alloc_family_for(&keep("module_memfree")).unwrap_err();
        assert!(err.starts_with("KERNEL_ALLOC_FAMILY_AMBIGUOUS"), "unexpected: {err}");
    }
}
