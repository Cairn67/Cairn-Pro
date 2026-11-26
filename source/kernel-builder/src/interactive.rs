// SPDX-License-Identifier: GPL-3.0-only
// The user-facing driver: no command line arguments, ever.
//

// Identifies the image from its contents and drives the interactive flow.
use super::*;
use crate::btf;
use crate::i18n::{self, Lang, Strings};
use crate::ksyms;
use crate::sites;
use std::path::Path;

const EXIT_CANCELLED: i32 = 2;
const EXIT_UNUSABLE: i32 = 4;
const EXIT_NOT_DYNAMIC_READY: i32 = 6;

const OUTPUT_ROOT: &str = "/storage/emulated/0/OPPO/适配";

fn ask(prompt: &str) -> Option<String> {
    use std::io::{self, Write};
    print!("{prompt}");
    let _ = io::stdout().flush();
    let mut line = String::new();
    match io::stdin().read_line(&mut line) {
        Ok(0) => None,
        Ok(_) => Some(line.trim().to_string()),
        Err(_) => None,
    }
}

fn pause(s: &Strings) {
    
    let _ = ask(s.press_enter);
}

fn choose_language() -> Option<(Lang, &'static Strings)> {
    println!("Cairn Pro 1.0.1");
    println!();
    println!("1. 简体中文");
    println!("2. 繁體中文");
    println!("3. English");
    println!();
    for _ in 0..5 {
        let answer = ask("> ")?;
        if answer.is_empty() {
            
            return None;
        }
        if let Some(lang) = i18n::parse_lang(&answer) {
            return Some((lang, i18n::strings(lang)));
        }
        println!("1 / 2 / 3");
    }
    None
}

struct Analysis {
    version: crate::kernel_version::KernelVersion,
    placement: crate::placement::Placement,
    kernel_len: usize,
    kernel_sha1: String,
    symbol_count: usize,
    layout: btf::Layout,
    sites: Vec<sites::Site>,
    known_profile: Option<&'static profiles::Profile>,
}

enum AnalysisError {
    Version(String),
    Placement(String),
    NotAndroid,
    Header(u32),
    NotArm64,
    Symbols(ksyms::SymError),
    Btf(btf::BtfError),
    Site(String, sites::LocateError),
}

fn analyze(kernel: &[u8]) -> Result<Analysis, AnalysisError> {
    let version = crate::kernel_version::detect(kernel).map_err(AnalysisError::Version)?;
    let sym = ksyms::discover(kernel).map_err(AnalysisError::Symbols)?;
    let layout = btf::read_layout(kernel).map_err(AnalysisError::Btf)?;
    let resolved = version.strategy.resolve(kernel, &sym)
        .map_err(|(n, e)| AnalysisError::Site(n, e))?;
    let placement = crate::placement::derive(kernel, &sym, 0x2560)
        .map_err(|e| AnalysisError::Placement(e.to_string()))?;
    let sha1 = sha1_hex(kernel);
    let known_profile = profiles::by_sha1(&sha1);
    Ok(Analysis {
        version,
        placement,
        kernel_len: kernel.len(),
        kernel_sha1: sha1,
        symbol_count: sym.count,
        layout,
        sites: resolved,
        known_profile,
    })
}

mod payload_layout {
    include!("../detached_layout.rs");
}
use payload_layout::PAYLOAD_LAYOUT;

fn payload_diffs(layout: &btf::Layout) -> Vec<(String, u64, u64)> {
    let mut out = Vec::new();
    for (s, f, want) in PAYLOAD_LAYOUT {
        match layout.get(s, f) {
            Some(got) if got == *want => {}
            Some(got) => out.push((format!("{s}.{f}"), *want, got)),
            None => out.push((format!("{s}.{f}"), *want, u64::MAX)),
        }
    }
    out
}

#[cfg(test)]
pub fn payload_layout_for_tests() -> &'static [(&'static str, &'static str, u64)] {
    PAYLOAD_LAYOUT
}

fn task_cred_offset() -> u64 {
    PAYLOAD_LAYOUT
        .iter()
        .find(|(s, f, _)| *s == "task_struct" && *f == "cred")
        .map(|(_, _, v)| *v)
        .unwrap_or(u64::MAX)
}

fn report(s: &Strings, a: &Analysis) {
    println!();
    println!("{}", s.summary_header);
    println!("  release     {}", a.version.release);
    println!("  strategy    {}", a.version.strategy.name());
    println!("  kernel      {} bytes", a.kernel_len);
    println!("  sha1        {}", a.kernel_sha1);
    println!("  symbols     {}", a.symbol_count);
    println!("  sites       {} / {}", a.sites.len(), sites::HOOK_SITES.len() + 1);
    println!("  candidate   cave={:#x}..{:#x} slot={:#x} state={:#x}",
        a.placement.base, a.placement.cave_end, a.placement.slot, a.placement.state);
    match a.known_profile {
        Some(p) => println!("  {}", format_args!("{} {}", s.identified, p.name)),
        None => println!("  {}", s.unknown_image),
    }
    println!();
    println!("  {:<28} {:>6}  {:>6}", "field", "core", "kernel");
    for (name, want, got) in payload_diffs(&a.layout) {
        let shown = if got == u64::MAX { "n/a".to_string() } else { got.to_string() };
        println!("  {name:<28} {want:>6}  {shown:>6}");
    }
    println!();
}

fn device_dir(name: &str) -> String {
    
    let safe: String = name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' })
        .collect();
    format!("{OUTPUT_ROOT}/{safe}")
}

pub fn run() -> i32 {
    use std::io::IsTerminal;
    let Some((lang, s)) = choose_language() else {
        
        return EXIT_CANCELLED;
    };
    let _ = lang;
    if !std::io::stdin().is_terminal() {
        println!("{}", s.need_tty);
        return EXIT_CANCELLED;
    }

    println!();
    println!("{}", s.title);
    println!();

    let boot_path = loop {
        let Some(answer) = ask(s.ask_image) else {
            println!("{}", s.cancelled);
            return EXIT_CANCELLED;
        };
        if answer.is_empty() {
            println!("{}", s.image_empty);
            return EXIT_CANCELLED;
        }
        let path = std::path::PathBuf::from(&answer);
        if !path.is_file() {
            println!("{}", s.not_android);
            continue;
        }
        break path;
    };

    println!();
    println!("{}", s.scanning);
    let boot = match fs::read(&boot_path) {
        Ok(b) => b,
        Err(e) => {
            println!("{e}");
            pause(s);
            return EXIT_UNUSABLE;
        }
    };
    if boot.len() < 4096 || &boot[..8] != b"ANDROID!" {
        println!("{}", s.not_android);
        pause(s);
        return EXIT_UNUSABLE;
    }
    let header_version = get32(&boot, 40);
    if !matches!(header_version, 3 | 4) {
        println!("{} (v{header_version})", s.header_unsupported);
        pause(s);
        return EXIT_UNUSABLE;
    }
    
    let boot_buf = boot;
    let (kernel, compression) = match extract_kernel(&boot_buf) {
        Ok(k) => (k.image, k.compression),
        Err(e) => {
            
            println!("{} ({e})", s.kernel_not_arm64);
            pause(s);
            return EXIT_UNUSABLE;
        }
    };
    let kernel = kernel.as_slice();

    let analysis = match analyze(kernel) {
        Ok(a) => a,
        Err(AnalysisError::Version(e)) => {
            println!("{} ({e})", s.refuse_incomplete);
            pause(s);
            return EXIT_NOT_DYNAMIC_READY;
        }
        Err(AnalysisError::Placement(e)) => {
            println!("{} ({e})", s.refuse_incomplete);
            pause(s);
            return EXIT_NOT_DYNAMIC_READY;
        }
        Err(AnalysisError::Symbols(e)) => {
            println!("{} ({e})", s.no_symbols);
            pause(s);
            return EXIT_UNUSABLE;
        }
        Err(AnalysisError::Btf(e)) => {
            println!("{} ({e})", s.no_symbols);
            pause(s);
            return EXIT_UNUSABLE;
        }
        Err(AnalysisError::Site(name, e)) => {
            println!("{} ({name}: {e})", s.refuse_incomplete);
            pause(s);
            return EXIT_UNUSABLE;
        }
        Err(AnalysisError::NotAndroid) | Err(AnalysisError::Header(_)) | Err(AnalysisError::NotArm64) => {
            println!("{}", s.not_android);
            pause(s);
            return EXIT_UNUSABLE;
        }
    };

    println!("{} {}", s.symbols_found, analysis.symbol_count);
    println!("{}", s.resolving_hooks);
    report(s, &analysis);

    let diffs = payload_diffs(&analysis.layout);
    let Some(profile) = analysis.known_profile else {
        
        match ksyms::discover(kernel)
            .map_err(|e| e.to_string())
            .and_then(|symbols| crate::dynamic_plan::derive(kernel, &symbols)) {
            Ok(plan) => {
                println!(
                    "  native plan: {} sites={} mmap_file=x{} layout_moved={} fixups={} targets={} cave={:#x}..{:#x}",
                    plan.version.strategy.name(),
                    plan.sites.len(),
                    plan.mmap_file_register,
                    plan.layout.iter().filter(|o| o.moved()).count(),
                    plan.fixups.fixups.len(),
                    plan.fixups.targets.len(),
                    plan.placement.base,
                    plan.placement.cave_end,
                );
                
                let source = Path::new(env!("CARGO_MANIFEST_DIR"));
                let out = crate::build::out_dir(OUTPUT_ROOT, &plan.version.release);
                let staging = out.join("build");
                
                let supplied = std::env::var_os("CAIRN_KEY_FILE").and_then(|p| {
                    std::fs::read_to_string(p)
                        .ok()
                        .and_then(|t| crate::parse_key(&t).ok())
                });
                println!("{}", s.building);
                match crate::build::run(kernel, &plan, source, &staging, supplied.as_ref()) {
                    Ok((report, package, bootstrap)) => {
                        let (hooks, boot) = match crate::build::image_sites(&report, &plan) {
                            Ok(v) => v,
                            Err(e) => {
                                println!("{} ({e})", s.refuse_incomplete);
                                pause(s);
                                return EXIT_NOT_DYNAMIC_READY;
                            }
                        };
                        let patched = match crate::image::apply(
                            kernel,
                            &bootstrap,
                            &hooks,
                            &boot,
                            &plan.placement,
                        ) {
                            Ok(v) => v,
                            Err(e) => {
                                println!("{} ({e})", s.refuse_incomplete);
                                pause(s);
                                return EXIT_NOT_DYNAMIC_READY;
                            }
                        };
                        let _ = fs::create_dir_all(&out);
                        if let Err(e) = crate::build::write_metadata(&report, &plan, &staging) {
                            println!("{} ({e})", s.refuse_incomplete);
                            pause(s);
                            return EXIT_NOT_DYNAMIC_READY;
                        }
                        
                        let wrapped = match crate::kcompress::close(&patched.image, compression) {
                            Ok(v) => v,
                            Err(e) => {
                                println!("{} ({e})", s.refuse_incomplete);
                                pause(s);
                                return EXIT_NOT_DYNAMIC_READY;
                            }
                        };
                        let mut boot_img = boot_buf.clone();
                        if let Err(e) = crate::build::replace_kernel(
                            &mut boot_img,
                            &wrapped,
                            &patched.image,
                        ) {
                            println!("{} ({e})", s.refuse_incomplete);
                            pause(s);
                            return EXIT_NOT_DYNAMIC_READY;
                        }
                        let files: [(&str, &[u8]); 4] = [
                            ("kernel-patched.bin", &patched.image),
                            ("boot-patched.img", &boot_img),
                            ("core.cld", &package),
                            ("bootstrap.bin", &bootstrap),
                        ];
                        for (name, bytes) in files {
                            if let Err(e) = fs::write(out.join(name), bytes) {
                                println!("{e}");
                                pause(s);
                                return EXIT_UNUSABLE;
                            }
                        }
                        let status = format!(
                            "{{\n  \"release\": \"{}\",\n  \"kernel_sha1\": \"{}\",\n  \"kernel_compression\": \"{}\",\n  \"output_kernel_bytes\": {},\n  \"output_kernel_sha256\": \"{}\",\n  \"output_boot_bytes\": {},\n  \"output_boot_sha256\": \"{}\",\n  \"core_sha256\": \"{}\",\n  \"bootstrap_bytes\": {},\n  \"bootstrap_capacity\": {},\n  \"hook_branches\": {},\n  \"changed_bytes\": {},\n  \"changed_bytes_in_cave\": {},\n  \"changed_site_words\": {},\n  \"layout_moved\": {},\n  \"posture\": \"experimental-qemu-only\",\n  \"kernel_patch_validated\": true,\n  \"avb_resigned\": false,\n  \"real_device_validated\": false\n}}\n",
                            plan.version.release,
                            sha1_hex(kernel),
                            compression.name(),
                            patched.image.len(),
                            crate::sha256::hex(&crate::sha256::digest(&patched.image)),
                            boot_img.len(),
                            crate::sha256::hex(&crate::sha256::digest(&boot_img)),
                            report.core_sha256,
                            report.bootstrap_bytes,
                            report.bootstrap_capacity,
                            patched.hook_branches,
                            patched.changed_bytes,
                            patched.changed_bytes_in_cave,
                            patched.changed_site_words,
                            plan.layout.iter().filter(|o| o.moved()).count(),
                        );
                        if let Err(e) = fs::write(out.join("verification-status.json"), status) {
                            println!("{e}");
                            pause(s);
                            return EXIT_UNUSABLE;
                        }
                        println!("{}", s.build_done);
                        println!("{}{}", s.output_dir, out.display());
                        
                        let key_hex = crate::key_hex(&report.auth_key);
                        println!("CAIRN_AUTH_KEY={key_hex}");
                        let _ = fs::write(
                            out.join("auth-key.txt"),
                            format!(
                                "Cairn Pro 1.0.1 authorization key\n\
                                 Enter this 32-hex-digit value in the app when it asks for the key.\n\
                                 This file is private: anyone holding it can authorize the kernel.\n\
                                 CAIRN_AUTH_KEY={key_hex}\n"
                            ),
                        );
                        println!("{}", s.warn_avb);
                        pause(s);
                        return 0;
                    }
                    Err(reason) => {
                        
                        println!("{} ({reason})", s.refuse_incomplete);
                        pause(s);
                        return EXIT_NOT_DYNAMIC_READY;
                    }
                }
            }
            Err(reason) => println!("  native plan refused: {reason}"),
        }
        println!("{}", s.unknown_image);
        println!("{}", s.refuse_incomplete);
        pause(s);
        return EXIT_NOT_DYNAMIC_READY;
    };
    if !diffs.is_empty() {
        println!("{}", s.refuse_incomplete);
        println!(
            "  core was assembled for task_struct.cred={} etc.; this kernel differs in {} field(s).",
            task_cred_offset(),
            diffs.len()
        );
        pause(s);
        return EXIT_NOT_DYNAMIC_READY;
    }
    if !profile.is_usable() {
        println!("{}", s.refuse_profile_targets);
        pause(s);
        return EXIT_UNUSABLE;
    }

    let out = std::path::PathBuf::from(device_dir(profile.name));
    println!("{}", s.building);
    let _ = fs::create_dir_all(&out);
    let boot_arg = boot_path.display().to_string();
    let out_arg = out.display().to_string();
    
    detached::run(Some(profile.name), vec![boot_arg, out_arg]);

    println!("{}", s.build_done);
    println!("{}{}", s.output_dir, out.display());
    println!("{}", s.warn_avb);
    pause(s);
    0
}
