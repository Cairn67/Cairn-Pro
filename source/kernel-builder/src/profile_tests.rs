// SPDX-License-Identifier: GPL-3.0-only
// Test the active detached artifacts, not the frozen legacy code-cave binary.
// Tests for profile selection.
use super::*;
use std::collections::HashSet;
mod detached_layout { include!("../detached_profile.rs"); }
fn symbols()->HashMap<&'static str,usize>{include!("../detached_symbols.rs").iter().copied().collect()}
fn core()->&'static [u8]{&include_bytes!("../detached_core.cld")[48..]}

#[test]
fn detached_symbols_and_key_are_bounded(){
 let bytes=core();let sy=symbols();assert_eq!(bytes.len(),detached_layout::CORE_BYTES);
 let container=include_bytes!("../detached_core.cld");assert_eq!(get32(container,8),detached_layout::CORE_PROFILE);assert_eq!(&container[16..48],&detached_layout::CORE_SHA256);
 for name in ["ca_kernel_apply","ca_file_avc_entry","ca_file_data_entry","ca_mmap_avc_entry","cr_raise_private","cr_shell_prepare"]{assert!(sy[name]<bytes.len());}
 let key=sy["cairn_hash"];assert_eq!(&bytes[key..key+16],&[0;16]);
}
#[test]
fn profile_has_unique_hooks_and_three_independent_policy_slots(){
 let sy=symbols();let bytes=core();let mut sites=HashSet::new();let bootstrap=include_bytes!("../detached_bootstrap.bin");
 assert_eq!(HOOKS.len(),19);assert_eq!(HOOKS.len(),detached_layout::HOOKS.len());
 assert_eq!(detached_layout::BOOTSTRAP_BASE,BASE);assert!(bootstrap.len()<=LIMIT);
 assert_eq!(&bootstrap[detached_layout::AUTH_OFFSET..detached_layout::AUTH_OFFSET+16],&[0;16]);
 for (i,&(address,original,name,linked)) in HOOKS.iter().enumerate(){
  assert!(sites.insert(address),"duplicate hook at {address:#x}");assert_eq!(address%4,0);assert!(sy[name]+4<=bytes.len());
  let (a,w,entry,l)=detached_layout::HOOKS[i];assert_eq!((a,w,l),(address,original,linked));assert!(entry+4<=bootstrap.len());
 }
 assert!(sites.insert(detached_layout::BOOT_HOOK.0));
 let slots:Vec<usize>=RELATIVE_TARGETS.iter().filter(|(name,_)|name.ends_with("slot_delta")).map(|(_,address)|*address).collect();
 assert_eq!(slots,vec![SLOT,SLOT+8,SLOT+16]);assert_eq!(RUNTIME_BYTES,24);
 for &(name,_) in RELATIVE_TARGETS{let o=sy[name];assert!(o+8<=bytes.len());assert_eq!(&bytes[o..o+8],&[0;8]);}
}
#[test]
fn every_external_branch_uses_a_bound_detached_bridge(){
 let sy=symbols();let bytes=core();let mut names=HashSet::new();
 for (i,&(name,target,link)) in FIXUPS.iter().enumerate(){
  assert!(names.insert(name),"duplicate fixup {name}");let offset=sy[name];let opcode=get32(bytes,offset);
  assert_eq!(opcode&0xfc000000,if link{0x94000000}else{0x14000000},"wrong branch {name}");
  let immediate=(((opcode&0x03ffffff)<<6)as i32>>4)as i64;
  assert_eq!(offset as i64+immediate,sy[format!("cl_core_bridge_{i}").as_str()]as i64,"unbound bridge {name}");
  let literal=sy[format!("cl_core_target_{i}").as_str()];assert_eq!(&bytes[literal..literal+8],&[0;8]);let _=branch(BASE,target,false);
 }
 assert!(names.contains("ca_mmap_avc_tail"));assert!(names.contains("ca_file_data_tail"));assert!(names.contains("cairn_abort_creds_call"));
}
#[test]
fn mmap_wrapper_preserves_access_mask_before_setting_provenance(){
 let bytes=core();let start=symbols()["ca_file_data_entry"];
 
 let mut sequence=Vec::new();sequence.extend_from_slice(&0x2a0303e1u32.to_le_bytes());sequence.extend_from_slice(&0x1a9f17e3u32.to_le_bytes());
 assert!(bytes[start..start+128].windows(sequence.len()).any(|w|w==sequence));
 assert!(include_str!("../access_hooks.S").contains("mov w1,w3\n cset w3,eq"));
}
