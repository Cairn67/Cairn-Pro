// SPDX-License-Identifier: GPL-3.0-only
// Detached core packaging with kernel fingerprint validation.
// Detached core packaging and its zero-key invariants.
use super::*;
use std::path::PathBuf;

#[allow(dead_code)] mod layout {include!("../detached_profile.rs");}
const BOOTSTRAP:&[u8]=include_bytes!("../detached_bootstrap.bin");
#[allow(dead_code)] mod layout17 {include!("../p17/detached_profile.rs");}
const BOOTSTRAP17:&[u8]=include_bytes!("../p17/bootstrap.bin");
const CORE:&[u8]=include_bytes!("../detached_core.cld");
fn align(n:usize,a:usize)->usize{n.checked_add(a-1).expect("size overflow")&!(a-1)}
fn cpio_entry(out:&mut Vec<u8>,name:&str,mode:u32,data:&[u8],inode:u32){
 assert!(!name.as_bytes().contains(&0));
 let fields=[inode,mode,0,0,if mode&0o170000==0o040000{2}else{1},0,u32::try_from(data.len()).expect("cpio file too large"),0,0,0,0,u32::try_from(name.len()+1).unwrap(),0];
 out.extend_from_slice(b"070701");for n in fields{out.extend_from_slice(format!("{n:08x}").as_bytes());}
 out.extend_from_slice(name.as_bytes());out.push(0);out.resize(align(out.len(),4),0);out.extend_from_slice(data);out.resize(align(out.len(),4),0);
}
fn ramdisk()->Vec<u8>{
 let mut r=Vec::new();cpio_entry(&mut r,"cairn",0o040700,&[],1);cpio_entry(&mut r,"cairn/core.cld",0o100600,CORE,2);cpio_entry(&mut r,"TRAILER!!!",0,&[],3);r.resize(align(r.len(),512),0);r
}

fn reserve_ramdisk(boot:&mut [u8],old_end:usize,new_end:usize){
 assert!(new_end>=old_end&&new_end%4096==0);let delta=new_end-old_end;if delta==0{return;}
 let footer=boot.len().checked_sub(64).expect("truncated boot");
 assert_eq!(&boot[footer..footer+4],b"AVBf","exact profile requires an AVB footer");
 let be64=|o:usize|u64::from_be_bytes(boot[o..o+8].try_into().unwrap());
 assert_eq!(u32::from_be_bytes(boot[footer+4..footer+8].try_into().unwrap()),1,"unsupported footer version");
 let original=usize::try_from(be64(footer+12)).unwrap();let vbmeta=usize::try_from(be64(footer+20)).unwrap();let vbsize=usize::try_from(be64(footer+28)).unwrap();
 assert!(original>=old_end&&vbmeta>=old_end&&vbsize>=256);assert_eq!(&boot[old_end..old_end+4],b"AVB0","unknown data after kernel");assert_eq!(&boot[vbmeta..vbmeta+4],b"AVB0");
 let used=vbmeta.checked_add(vbsize).expect("vbmeta overflow");assert!(used<=footer&&original<=used);assert!(boot[used..footer].iter().all(|b|*b==0),"unrecognized nonzero trailing data");
 let shifted=used.checked_add(delta).expect("AVB relocation overflow");assert!(shifted<=footer,"insufficient boot partition space");boot.copy_within(old_end..used,new_end);boot[old_end..new_end].fill(0);
 boot[footer+12..footer+20].copy_from_slice(&u64::try_from(original+delta).unwrap().to_be_bytes());boot[footer+20..footer+28].copy_from_slice(&u64::try_from(vbmeta+delta).unwrap().to_be_bytes());
}
pub(super) fn run(profile:Option<&str>,args:Vec<String>){
  
  let _ = (profile, args);
  eprintln!("Static patch schemes have been removed. This build derives every site, offset and");
  eprintln!("replayed instruction from the supplied image itself. Run the patcher with no arguments");
  eprintln!("and give it the boot image; it will analyse and build on its own.");
  std::process::exit(5);
}
#[allow(dead_code)]
fn legacy_run(profile:Option<&str>,args:Vec<String>){
  
  let interactive=args.is_empty();
  if !interactive && args.len()!=2&&args.len()!=3{eprintln!("Cairn Pro 1.0.1 detached-core development candidate\nUsage: cairn-kernel-builder [--profile NAME] STOCK_BOOT OUTPUT_DIR\nLegacy: cairn-kernel-builder STOCK_KERNEL STOCK_BOOT OUTPUT_DIR\nRun without arguments for the interactive patcher.\nList schemes with --list-profiles.");std::process::exit(2);}
  let (bootpath, out, selected, interactive_key) = if interactive {
    use std::io::{self,IsTerminal,Write};
    if !io::stdin().is_terminal(){eprintln!("Interactive patcher requires a terminal; use STOCK_BOOT OUTPUT_DIR for scripted use.");std::process::exit(2);}
    println!("Cairn Pro 1.0.1 kernel patcher\n");
    println!("Supported patch schemes:");
    let selected=match crate::profiles::prompt(){crate::profiles::Choice::Named(p)=>p,crate::profiles::Choice::Dynamic=>crate::profiles::dynamic_unavailable(),crate::profiles::Choice::Cancelled=>{eprintln!("No patch scheme selected; nothing written.");std::process::exit(2);}};
    let ask=|prompt:&str|->String{print!("{prompt}");let _=io::stdout().flush();let mut s=String::new();io::stdin().read_line(&mut s).expect("read input");s.trim().to_string()};
    let boot=ask("Kernel/boot image path: ");
    if boot.is_empty(){eprintln!("Kernel/boot image path cannot be empty.");std::process::exit(2);}
    let custom=ask("Customize authorization key? [y/N]: ");
    let key=if custom.eq_ignore_ascii_case("y")||custom.eq_ignore_ascii_case("yes"){
      let text=ask("Custom key (32 hex characters): ");
      Some(super::parse_key(&text).unwrap_or_else(|e|{eprintln!("Invalid custom key: {e}");std::process::exit(2)}))
    }else{None};
    let output=PathBuf::from("cairn-output");
    (PathBuf::from(boot),output,selected,key)
  } else {
    let bootpath=PathBuf::from(&args[if args.len()==2{0}else{1}]);
    let selected=match crate::profiles::select(profile,||{
      let b=fs::read(&bootpath).ok()?;let k=extract_kernel(&b).ok()?;Some(sha1_hex(&k.image))
    }){crate::profiles::Choice::Named(p)=>p,crate::profiles::Choice::Dynamic=>crate::profiles::dynamic_unavailable(),crate::profiles::Choice::Cancelled=>{eprintln!("No scheme selected; nothing written.");std::process::exit(2);}};
    let out=PathBuf::from(&args[if args.len()==2{1}else{2}]);(bootpath,out,selected,None)
  };
  let bootpath=bootpath.as_path();
  println!("[0/7] Selecting the patch scheme for this device.");
  println!("  scheme: {}",selected.name);
 if !selected.bootstrap_ready{
  let missing=selected.missing_targets();
  eprintln!("Refusing to build for {}: this profile still lacks verified kernel addresses ({}).",selected.name,missing.join(", "));
  eprintln!("The device is recognised, but patching with an unresolved branch target would fault inside the kernel instead of failing safely here.");
  std::process::exit(3);
 }
  let legacy=!interactive&&args.len()==3;
  fs::create_dir_all(&out).expect("create output directory");
  println!("[1/7] Validating the exact stock boot and ARM64 kernel profile.");
  let boot=fs::read(bootpath).expect("read stock boot");let opened=extract_kernel(&boot).expect("open kernel");let stock=opened.image.as_slice();
  if legacy{let supplied=fs::read(&args[0]).expect("read supplied kernel");assert_eq!(supplied,stock,"legacy kernel must match the boot kernel exactly");}
 assert_eq!(boot.len(),96*1024*1024,"unsupported boot container size");assert_eq!(get32(&boot,40),4,"detached packaging currently requires boot v4");assert_eq!(get32(&boot,20),1584,"unsupported boot header size");assert_eq!(get32(&boot,12),0,"existing boot ramdisk is not supported; refuse destructive merge");assert_eq!(get32(&boot,1580),0,"boot v4 signature relocation is unsupported");
 let actual=sha1_hex(stock);
 if !selected.stock_sha1.iter().any(|s|*s==actual){eprintln!("Stock kernel sha1 {actual} is not accepted by scheme {}.",selected.name);std::process::exit(4);}
 if let Err(e)=crate::profiles::validate(selected,stock){eprintln!("Scheme {} does not fit this kernel: {e}",selected.name);std::process::exit(4);}
 let (bootstrap,auth_offset,boot_entry,artifact_hooks)=if (selected.name==crate::profiles::TURBO6_170 || selected.name==crate::profiles::ACE6_170) {
  assert_eq!(selected.base,layout17::BOOTSTRAP_BASE);
  assert_eq!(selected.boot_hook,(layout17::BOOT_HOOK.0,layout17::BOOT_HOOK.1));
  assert_eq!(layout17::CORE_SHA256,layout::CORE_SHA256);
  (BOOTSTRAP17,layout17::AUTH_OFFSET,layout17::BOOT_HOOK.2,layout17::HOOKS)
 }else{
  assert_eq!(selected.base,layout::BOOTSTRAP_BASE);
  (BOOTSTRAP,layout::AUTH_OFFSET,layout::BOOT_HOOK.2,layout::HOOKS)
 };
 assert_eq!(selected.hooks.len(),artifact_hooks.len());
 for (hook,embedded) in selected.hooks.iter().zip(artifact_hooks.iter()) {
  assert_eq!((hook.0,hook.1,hook.3),(embedded.0,embedded.1,embedded.3),"profile/embedded artifact mismatch");
 }
 let prepared=selected.pac_prepared;
 assert_eq!(stock.len(),selected.slot,"stock kernel length must equal the profile slot");
 assert_eq!(u64::from_le_bytes(stock[16..24].try_into().unwrap()),selected.runtime_image,"unexpected ARM64 runtime image size");
 println!("[2/7] Provisioning a private authorization key.");
 let key=match interactive_key{Some(k)=>k,None=>match env::var_os("CAIRN_KEY_FILE"){Some(p)=>parse_key(&fs::read_to_string(p).expect("read private key file")).expect("invalid key"),None=>random_key()}};
 println!("[3/7] Preparing the embedded detached core and compact bootstrap.");
 assert!(bootstrap.len()<=LIMIT);assert_eq!(CORE.len(),48+layout::CORE_BYTES);assert_eq!(&CORE[..4],&0x434c4431u32.to_le_bytes());assert_eq!(get32(CORE,4),1);assert_eq!(get32(CORE,8),layout::CORE_PROFILE);assert_eq!(&CORE[16..48],&layout::CORE_SHA256);assert_eq!(get32(CORE,12)as usize,layout::CORE_BYTES);
 let mut stub=bootstrap.to_vec();let ko=auth_offset;assert!(stub[ko..ko+16].iter().all(|b|*b==0));stub[ko..ko+16].copy_from_slice(&key);
 let mut kernel=stock.to_vec();prepare_pac_sites(&mut kernel,selected.pac_sites,prepared);assert!(kernel[selected.base..selected.base+LIMIT].iter().all(|b|*b==0),"bootstrap cave must be zero");kernel[selected.base..selected.base+stub.len()].copy_from_slice(&stub);
 println!("[4/7] Installing immutable gateways and the early initramfs loader hook.");
 
 for (i,&(offset,original,name,linked)) in selected.hooks.iter().enumerate(){
  let entry=artifact_hooks[i].2;
  assert_eq!(get32(&kernel,offset),original,"hook fingerprint {name} at {offset:#x}");
  put32(&mut kernel,offset,branch(offset,selected.base+entry,linked));
 }
 let (offset,original)=selected.boot_hook;let entry=boot_entry;
 assert_eq!(get32(&kernel,offset),original,"early boot hook fingerprint at {offset:#x}");
 put32(&mut kernel,offset,branch(offset,selected.base+entry,true));
 
 kernel.extend_from_slice(&[0;56]);let slot_end=selected.slot+24+56;assert_eq!(kernel.len(),selected.slot+56,"runtime tail must end at slot+56");assert!(slot_end<=selected.runtime_image as usize,"runtime state overlaps native BSS");
 println!("[5/7] Packaging the zero-key core in a root-owned initramfs entry.");
 let disk=ramdisk();let old_end=4096+align(stock.len(),4096);let new_end=4096+align(kernel.len(),4096);assert_eq!(old_end,new_end,"kernel page layout changed");let end=new_end.checked_add(align(disk.len(),4096)).expect("ramdisk overflow");assert!(end<=boot.len());
 let mut result=boot;reserve_ramdisk(&mut result,new_end,end);put32(&mut result,8,u32::try_from(kernel.len()).unwrap());put32(&mut result,12,u32::try_from(disk.len()).unwrap());result[4096..new_end].fill(0);result[4096..4096+kernel.len()].copy_from_slice(&kernel);result[new_end..end].fill(0);result[new_end..new_end+disk.len()].copy_from_slice(&disk);
 println!("[6/7] Writing matched kernel, boot, core, and ramdisk artifacts.");
 fs::write(out.join("kernel-V1.0.1.bin"),&kernel).expect("write kernel");fs::write(out.join("boot-V1.0.1.img"),&result).expect("write boot");fs::write(out.join("core.cld"),CORE).expect("write detached core");fs::write(out.join("cairn-ramdisk.cpio"),&disk).expect("write ramdisk");
 fs::write(out.join("VERSION"),format!("Cairn Pro 1.0.1 detached-core development candidate\nScheme: {}\nBootstrap bytes: {} / {}\nDetached core bytes: {}\nHook sites: {}\nLinux upstream version unchanged\nAVB not re-signed\nAVB metadata moved; signatures and signed size/hash descriptors are stale\nNo real-device validation\n",selected.name,stub.len(),LIMIT,layout::CORE_BYTES,artifact_hooks.len()+1)).unwrap();
 println!("[7/7] Build complete: scheme={}; bootstrap={} bytes; detached core={} bytes; boot={} bytes.",selected.name,stub.len(),layout::CORE_BYTES,result.len());
  println!("\n[+] Patch contents");
  println!("    [+] Device scheme: {}",selected.name);
  println!("    [+] Stock boot validated: yes");
  println!("    [+] Kernel hooks installed: {}",artifact_hooks.len()+1);
  println!("    [+] Bootstrap embedded: {} bytes",stub.len());
  println!("    [+] Detached core embedded: {} bytes",layout::CORE_BYTES);
  println!("    [+] Authorization key: custom/selected key applied");
  println!("    [+] Output boot image: {}",out.join("boot-V1.0.1.img").display());
  println!("WARNING: Experimental exact-profile output. AVB is not re-signed. Keep the image and authorization key private.");
  println!("Boot image: {}",out.join("boot-V1.0.1.img").display());println!("CAIRN_AUTH_KEY={}",key_hex(&key));
}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn detached_layout_is_bounded_and_zero_key(){assert!(BOOTSTRAP.len()<LIMIT);assert!(BOOTSTRAP[layout::AUTH_OFFSET..layout::AUTH_OFFSET+16].iter().all(|b|*b==0));assert_eq!(layout::HOOKS.len(),19);assert_eq!(CORE.len(),48+layout::CORE_BYTES);assert_eq!(layout::CORE_BYTES,65536);let mut addresses=std::collections::HashSet::new();for &(o,_,entry,_) in layout::HOOKS{assert!(addresses.insert(o));assert!(entry+4<=BOOTSTRAP.len());}assert!(addresses.insert(layout::BOOT_HOOK.0));}
 #[test]fn avb_metadata_moves_without_overwriting_payload(){let mut b=vec![0u8;32768];let f=b.len()-64;b[4096..4100].copy_from_slice(b"AVB0");b[8192..8196].copy_from_slice(b"AVB0");b[8200]=17;b[f..f+4].copy_from_slice(b"AVBf");b[f+4..f+8].copy_from_slice(&1u32.to_be_bytes());b[f+12..f+20].copy_from_slice(&8192u64.to_be_bytes());b[f+20..f+28].copy_from_slice(&8192u64.to_be_bytes());b[f+28..f+36].copy_from_slice(&256u64.to_be_bytes());reserve_ramdisk(&mut b,4096,12288);assert!(b[4096..12288].iter().all(|v|*v==0));assert_eq!(&b[12288..12292],b"AVB0");assert_eq!(&b[16384..16388],b"AVB0");assert_eq!(b[16392],17);assert_eq!(u64::from_be_bytes(b[f+20..f+28].try_into().unwrap()),16384);}
 #[test]fn cpio_contains_only_root_private_detached_core(){let d=ramdisk();assert_eq!(&d[..6],b"070701");assert!(d.windows(b"cairn/core.cld".len()).any(|w|w==b"cairn/core.cld"));assert!(d.windows(b"TRAILER!!!".len()).any(|w|w==b"TRAILER!!!"));assert_eq!(d.len()%512,0);let mut count=0;let mut off=0;loop{assert_eq!(&d[off..off+6],b"070701");let field=|i:usize|usize::from_str_radix(std::str::from_utf8(&d[off+6+i*8..off+14+i*8]).unwrap(),16).unwrap();let mode=field(1);assert_eq!(field(2),0);assert_eq!(field(3),0);let bytes=field(6);let names=field(11);let name=&d[off+110..off+110+names-1];let data=align(off+110+names,4);if name==b"TRAILER!!!"{break;}if name==b"cairn/core.cld"{assert_eq!(mode,0o100600);assert_eq!(&d[data..data+bytes],CORE);}else{assert_eq!(mode,0o040700);}count+=1;off=align(data+bytes,4);}assert_eq!(count,2);}
}
