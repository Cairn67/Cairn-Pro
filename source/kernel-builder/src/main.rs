// SPDX-License-Identifier: GPL-3.0-only
// Scope: this independent userspace Rust source only; payload.S is GPL-2.0-only.
// Producing a patched image does not relicense the kernel or discharge its source obligations.

// Command line entry point of the image patcher.
use std::{env,fs};
#[cfg(test)] use std::collections::HashMap;
#[cfg(test)] mod profile;
mod detached;
mod i18n;
mod ksyms;
mod btf;
mod sites;
mod kernel_version;
mod dynamic_plan;
mod replay;
mod frame;
mod avc_abi;
mod bridge;
mod prologue;
mod sha256;
mod fixups;
mod image;
mod payload_layout;
mod build;
mod placement;
mod kcompress;
mod interactive;
mod profiles;
#[cfg(test)] mod profile_tests;
#[cfg(test)] mod verify_symbols;
#[cfg(test)] mod diag_layouts;
#[cfg(test)] mod diag_sites;
#[cfg(test)] mod diag_symbols;
#[cfg(test)] mod diag_body;
#[cfg(test)] mod diag_stat_names;
#[cfg(test)] mod diag_symfail;
#[cfg(test)] mod payload_layout_tests;
#[cfg(test)] mod symbol_cross_check {
    
    #[test]
    fn matches_python_on_shipped_samples() {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let samples = root.join("../../../../research/three-device-dynamic");
        let mut checked = 0;
        for entry in std::fs::read_dir(&samples).expect("samples directory") {
            let dir = entry.expect("dir entry").path();
            let kernel = dir.join("kernel.bin");
            let names = dir.join("symbols.txt");
            if !kernel.is_file() || !names.is_file() {
                continue;
            }
            let expected: Vec<(u32, String)> = std::fs::read_to_string(&names)
                .expect("symbols.txt")
                .lines()
                .map(|l| {
                    let mut parts = l.splitn(3, ' ');
                    let addr = parts.next().unwrap();
                    let _kind = parts.next().unwrap();
                    let name = parts.next().unwrap();
                    (u32::from_str_radix(addr, 16).unwrap(), name.to_string())
                })
                .collect();
            let data = std::fs::read(&kernel).expect("kernel.bin");
            let got = crate::ksyms::discover(&data)
                .unwrap_or_else(|e| panic!("{}: {e}", dir.display()));
            assert_eq!(got.count, expected.len(), "{} count", dir.display());
            assert_eq!(expected.len(), got.addresses.len());
            for (i, ((addr, name), got_addr)) in
                expected.iter().zip(got.addresses.iter()).enumerate()
            {
                assert_eq!(*addr, *got_addr, "{} address #{i} {name}", dir.display());
                
                assert_eq!(*name, got.names[i][1..], "{} name #{i}", dir.display());
            }
            checked += 1;
        }
        assert!(checked >= 3, "expected at least 3 samples, saw {checked}");
    }
}
#[cfg(test)] use profile::{FIXUPS,HOOKS,RELATIVE_TARGETS,RUNTIME_BYTES};

#[cfg(test)] const BASE:usize=0x108daa0;
const LIMIT:usize=0x2560;
#[cfg(test)] const SLOT:usize=0x22f6a00;
#[cfg(test)] const RC:&[u8]=b"\non post-fs-data\n    exec u:r:init:s0 root root -- /system/bin/cnd --stage post-fs-data\n\non nonencrypted\n    exec_background u:r:init:s0 root root -- /system/bin/cnd --stage service\n\non property:vold.decrypt=trigger_restart_framework\n    exec_background u:r:init:s0 root root -- /system/bin/cnd --stage service\n\non property:sys.boot_completed=1\n    exec_background u:r:init:s0 root root -- /system/bin/cnd --stage boot-completed\n";
fn put32(b:&mut[u8],o:usize,v:u32){b.get_mut(o..o+4).expect("write bounds").copy_from_slice(&v.to_le_bytes());}
fn get32(b:&[u8],o:usize)->u32{u32::from_le_bytes(b[o..o+4].try_into().unwrap())}
fn branch(src:usize,dst:usize,link:bool)->u32{let d=dst as i64-src as i64;assert!(d%4==0&&(-0x8000000..0x8000000).contains(&d));(if link{0x94000000}else{0x14000000})|(((d>>2)as u32)&0x3ffffff)}

fn prepare_pac_sites(k:&mut[u8],sites:&[usize],already_prepared:bool){
 for(i,&o)in sites.iter().enumerate(){
  let expected=if already_prepared{0xd503201f}else if i%2==0{0xd503233f}else{0xd50323bf};
  assert_eq!(get32(k,o),expected,"PAC fingerprint at {o:#x}");
 }
 for &o in sites{put32(k,o,0xd503201f);}
}

fn take_profile_flag(args:Vec<String>)->(Option<String>,Vec<String>){
 let mut name=None;let mut rest=Vec::new();let mut it=args.into_iter();
 while let Some(a)=it.next(){
  if let Some(v)=a.strip_prefix("--profile="){name=Some(v.to_string());}
  else if a=="--profile"{match it.next(){Some(v)=>name=Some(v),None=>{eprintln!("--profile needs a value");}}}
  else if a=="--list-profiles"{
   for p in profiles::PROFILES{println!("{}",p.name);}
   println!("{} [实验/未就绪]", profiles::DYNAMIC);
   std::process::exit(0);
  }
  else{rest.push(a);}
 }
 (name,rest)
}
pub(crate) fn parse_key(s:&str)->Result<[u8;16],&'static str>{
 let s=s.trim();if s.len()!=32||!s.is_ascii(){return Err("key must contain 32 hex digits");}
 let mut key=[0;16];for(i,v)in key.iter_mut().enumerate(){*v=u8::from_str_radix(&s[i*2..i*2+2],16).map_err(|_|"invalid hex key")?;}
 if key.iter().all(|b|*b==0){return Err("zero placeholder key refused");}Ok(key)
}
pub(crate) fn random_key()->[u8;16]{
 use std::io::Read;
 let mut key=[0u8;16];
 let mut source=fs::File::open("/dev/urandom").expect("open system secure random source");
 loop{source.read_exact(&mut key).expect("read secure random key");if key.iter().any(|b|*b!=0){return key;}}
}
pub(crate) fn key_hex(key:&[u8;16])->String{key.iter().map(|b|format!("{b:02x}")).collect()}
fn extract_kernel(boot:&[u8])->Result<kcompress::Kernel,String>{
 if boot.len()<4096 || &boot[..8]!=b"ANDROID!"{return Err("invalid or truncated Android boot image".into());}
 if !matches!(get32(boot,40),3|4){return Err("only boot header v3/v4 is supported".into());}
 let size=get32(boot,8)as usize;
 let end=4096usize.checked_add(size).ok_or("kernel size overflow")?;
 let body=boot.get(4096..end).ok_or("truncated kernel section")?;
 
 kcompress::open(body)
}
fn main(){
 
 if env::args_os().len() > 1 {
  eprintln!("This build takes no arguments. Run it with no parameters and follow the prompts.");
  std::process::exit(2);
 }
 let code=interactive::run();
 std::process::exit(code);
}
#[cfg(test)]mod tests{use super::*;#[test]fn branches(){assert_eq!(branch(0x100,0x104,false),0x14000001);assert_eq!(branch(0x104,0x100,true),0x97ffffff);}#[test]fn endian(){let mut b=[0;4];put32(&mut b,0,0x12345678);assert_eq!(get32(&b,0),0x12345678);}}
#[cfg(test)]mod lifecycle_tests {
 use super::*;
 #[test]fn rc_matches_payload_source(){
  let asm=include_str!("../payload.S");
  
  let literal=asm.split("cairn_rc_add:").nth(1).unwrap().lines()
   .map(str::trim).find(|line|line.starts_with(".asciz")).unwrap();
  let value=literal.strip_prefix(".asciz").unwrap().trim();
  assert!(value.starts_with('"')&&value.ends_with('"'));
  let decoded=value[1..value.len()-1].replace("\\n","\n");
  assert_eq!(decoded.as_bytes(),RC);
  let symbols:&[(&str,usize)]=include!("../detached_symbols.rs");
  let offset=symbols.iter().find(|(name,_)|*name=="cairn_rc_add").unwrap().1;
  let payload=&include_bytes!("../detached_core.cld")[48..];
  assert_eq!(&payload[offset..offset+RC.len()],RC);
  assert_eq!(payload[offset+RC.len()],0);
  assert_eq!(String::from_utf8_lossy(RC).matches("on property:sys.boot_completed=1").count(),1);
 }
 #[test]fn invalid_keys(){assert!(parse_key("00000000000000000000000000000000").is_err());assert!(parse_key("bad").is_err());assert!(parse_key("11111111111111111111111111111111").is_ok());}
}

#[cfg(test)] mod random_key_tests {
 use super::*;
 #[test] fn random_key_format_and_roundtrip(){let k=random_key();let h=key_hex(&k);assert_eq!(h.len(),32);assert_eq!(parse_key(&h).unwrap(),k);}
 #[test] fn separate_random_keys(){assert_ne!(random_key(),random_key());}
}

fn sha1_hex(input:&[u8])->String{
 let mut h=[0x67452301u32,0xefcdab89,0x98badcfe,0x10325476,0xc3d2e1f0];
 let mut bytes=input.to_vec();bytes.push(0x80);
 while bytes.len()%64!=56{bytes.push(0);}
 bytes.extend_from_slice(&((input.len()as u64).wrapping_mul(8)).to_be_bytes());
 for block in bytes.chunks_exact(64){
  let mut w=[0u32;80];
  for i in 0..16{w[i]=u32::from_be_bytes(block[i*4..i*4+4].try_into().unwrap());}
  for i in 16..80{w[i]=(w[i-3]^w[i-8]^w[i-14]^w[i-16]).rotate_left(1);}
  let [mut a,mut b,mut c,mut d,mut e]=h;
  for (i,&word) in w.iter().enumerate(){
   let (f,k)=match i{0..=19=>((b&c)|(!b&d),0x5a827999u32),20..=39=>(b^c^d,0x6ed9eba1),40..=59=>((b&c)|(b&d)|(c&d),0x8f1bbcdc),_=>(b^c^d,0xca62c1d6)};
   let t=a.rotate_left(5).wrapping_add(f).wrapping_add(e).wrapping_add(k).wrapping_add(word);
   e=d;d=c;c=b.rotate_left(30);b=a;a=t;
  }
  for (dst,v) in h.iter_mut().zip([a,b,c,d,e]){*dst=dst.wrapping_add(v);}
 }
 h.iter().map(|v|format!("{v:08x}")).collect()
}
#[cfg(test)] mod sha1_tests{
 use super::*;
 #[test] fn standard_vectors(){
  assert_eq!(sha1_hex(b""),"da39a3ee5e6b4b0d3255bfef95601890afd80709");
  assert_eq!(sha1_hex(b"abc"),"a9993e364706816aba3e25717850c26c9cd0d89d");
  assert_eq!(sha1_hex(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),"84983e441c3bd26ebaae4aa1f95129e5e54670f1");
 }
}
