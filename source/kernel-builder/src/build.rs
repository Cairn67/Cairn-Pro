// SPDX-License-Identifier: GPL-3.0-only
//! Assemble the detached core and bootstrap for one target kernel.
//!

// Assembles payloads and links them into the patched image.
use crate::dynamic_plan::Plan;
use crate::{fixups, image, payload_layout, replay, sha256};
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::process::Command;

const GCC: &str = "aarch64-linux-gnu-gcc";
const AS: &str = "aarch64-linux-gnu-as";
const LD: &str = "aarch64-linux-gnu-ld";
const OBJCOPY: &str = "aarch64-linux-gnu-objcopy";
const NM: &str = "aarch64-linux-gnu-nm";
const OBJDUMP: &str = "aarch64-linux-gnu-objdump";

const CORE_SIZE: usize = 65536;

const FAR_PROBE_PAGE: usize = 4096;

const ASSEMBLY: &[&str] = &[
    "payload", "root_domain", "path_adapter", "path_vfs", "path_open", "path_enum",
    "access_adapter", "access_hooks", "access_avc", "root_tty",
];

fn payload_for(strategy: crate::kernel_version::Strategy) -> &'static str {
    match strategy {
        crate::kernel_version::Strategy::Linux61 => "payload_android14-6.1",
        crate::kernel_version::Strategy::Linux66 => "payload_android15-6.6",
        crate::kernel_version::Strategy::Linux612 => "payload_android16-6.12",
    }
}
const C_SOURCES: &[&str] = &["path_backend", "path_enum", "access_backend", "access_vfs", "access_avc"];

const PROLOGUE_SPANS: &[(&str, &str, &str)] = &[
    ("CAIRN_PROLOGUE_STAT", "cairn_stat", "cairn_stat_ret"),
    ("CAIRN_PROLOGUE_VREAD", "cairn_vread", "cairn_vr_j"),
    ("CAIRN_PROLOGUE_FACCESS", "cairn_faccess", "cairn_fac_j"),
];

const PROLOGUE_FRAMES: &[(&str, u32)] = &[
    ("CAIRN_PROLOGUE_STAT", 0x80),
    ("CAIRN_PROLOGUE_STAT", 0x70),
];

fn prologue_frame_check(span: &[u32], family_hint: &str) -> Result<u32, String> {
    if span.is_empty() {
        return Err("PROLOGUE_SPAN_EMPTY".into());
    }
    
    let mut frame: Option<u32> = None;
    for (i, word) in span.iter().enumerate() {
        
        if word & 0xffc0_03ff == 0xd100_03ff {
            let value = (word >> 10) & 0xfff;
            if value == 0 {
                return Err(format!("PROLOGUE_SPAN_BAD_FRAME: `sub sp, sp, #0` at word {i}"));
            }
            if frame.is_some() {
                return Err(format!(
                    "PROLOGUE_SPAN_TWO_SUB_SP: a second stack adjustment at word {i}"
                ));
            }
            frame = Some(value);
        }
    }
    let Some(frame) = frame else {
        
        if let Some(&first) = span.first() {
            
            if first != 0xd503_233f && first != 0xd503_245f {
                let sets_fp = span.iter().any(|w| w & 0xffc0_03ff == 0x9100_03ff && (w & 0x1f) == 29);
                
                let frame_less = span.iter().all(|w| crate::replay::is_inline_safe(*w))
                    && !span.iter().any(|w| {
                        w & 0x1f00_0000 == 0x1100_0000
                            && (w & 0x1f) == 31
                            && (w >> 5) & 0x1f == 31
                    });
                if sets_fp || frame_less {
                    if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
                        eprintln!(
                            "TRACE prologue ({family_hint}) is frame-less: {} words, first {first:#010x}",
                            span.len()
                        );
                    }
                    return Ok(0);
                }
            }
        }
        return Err("PROLOGUE_SPAN_NO_SUB_SP: no `sub sp, sp, #imm` anywhere in the span".into());
    };
    
    if frame % 16 != 0 {
        return Err(format!(
            "PROLOGUE_SPAN_MISALIGNED_FRAME: sub sp,#{frame:#x} is not a multiple of 16"
        ));
    }
    
    if span.iter().any(|w| w & 0xffc0_03ff == 0x9100_03ff && (w >> 5) & 0x1f == 31) {
        return Err("PROLOGUE_SPAN_LOOKS_LIKE_EPILOGUE: span restores sp".into());
    }
    
    let sets_fp = span.iter().any(|w| w & 0xffc0_03ff == 0x9100_03ff && (w >> 0) & 0x1f == 29);
    
    if !sets_fp && std::env::var_os("CAIRN_TRACE_SITES").is_some() {
        eprintln!(
            "TRACE prologue frame={frame:#x} ({family_hint}) leaves `add x29` to the \
             target: span={} words",
            span.len()
        );
    }
    if !PROLOGUE_FRAMES.iter().any(|(_, f)| *f == frame)
        && std::env::var_os("CAIRN_TRACE_SITES").is_some()
    {
        eprintln!(
            "TRACE prologue frame={frame:#x} is not one of the known references \
             ({family_hint}); using the image's span"
        );
    }
    Ok(frame)
}

fn prologue_span(
    kernel: &[u8],
    plan: &Plan,
    hook: &str,
    continuation: &str,
) -> Result<Vec<u32>, String> {
    let site = plan
        .sites
        .iter()
        .find(|s| s.name == hook)
        .ok_or_else(|| format!("PROLOGUE_HOOK_ABSENT {hook}"))?;
    let end = plan
        .fixups
        .fixups
        .iter()
        .find(|f| f.name == continuation)
        .ok_or_else(|| format!("PROLOGUE_CONTINUATION_ABSENT {continuation}"))?
        .target;
    if end <= site.offset || (end - site.offset) % 4 != 0 {
        return Err(format!(
            "PROLOGUE_SPAN_INVALID {hook}: {:#x}..{end:#x}",
            site.offset
        ));
    }
    let mut words = Vec::new();
    for at in (site.offset..end).step_by(4) {
        let word = u32::from_le_bytes(
            kernel
                .get(at..at + 4)
                .ok_or_else(|| format!("PROLOGUE_OUTSIDE_IMAGE {hook} at {at:#x}"))?
                .try_into()
                .unwrap(),
        );
        if replay::is_pc_relative(word) {
            return Err(format!(
                "PROLOGUE_PC_RELATIVE {hook} at {at:#x}: {word:#010x}"
            ));
        }
        words.push(word);
    }
    Ok(words)
}

#[derive(Debug, Clone)]
pub struct Report {
    pub bootstrap_base: usize,
    pub bootstrap_bytes: usize,
    pub bootstrap_capacity: usize,
    pub core_bytes: usize,
    pub core_sha256: String,
    pub core_symbols: Vec<(String, usize)>,
    pub bootstrap_symbols: Vec<(String, usize)>,
    pub hooks: Vec<HookEntry>,
    pub fixups: Vec<fixups::Fixup>,
    pub relatives: Vec<fixups::Relative>,
    pub targets: Vec<fixups::KernelTarget>,
    pub layout: Vec<payload_layout::Offset>,
    pub replays_inlined: usize,
    pub replays_branched: usize,
    pub mmap_file_register: u8,
    pub prctl_locals: u64,
    
    pub auth_offset: usize,
    
    pub auth_key: [u8; 16],
    
    pub boot_hook: (usize, u32, usize),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HookEntry {
    pub name: &'static str,
    pub offset: usize,
    pub word: u32,
    pub gateway: usize,
    pub linked: bool,
}

fn tool(cmd: &[String]) -> Result<String, String> {
    let out = Command::new(&cmd[0])
        .args(&cmd[1..])
        .output()
        .map_err(|e| format!("TOOLCHAIN_UNAVAILABLE {}: {e}", cmd[0]))?;
    let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
    text.push_str(&String::from_utf8_lossy(&out.stderr));
    if !out.status.success() {
        return Err(format!("TOOL_FAILED {}: {text}", cmd.join(" ")));
    }
    Ok(text)
}

fn run_tool(program: &str, args: &[&str]) -> Result<String, String> {
    let mut cmd = vec![program.to_string()];
    cmd.extend(args.iter().map(|s| s.to_string()));
    tool(&cmd)
}

fn symbols(elf: &Path, base: usize) -> Result<Vec<(String, usize)>, String> {
    let text = run_tool(NM, &[&elf.display().to_string()])?;
    let mut out = Vec::new();
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        let (Some(addr), Some(kind), Some(name)) = (parts.next(), parts.next(), parts.next()) else {
            continue;
        };
        if kind == "U" || kind == "u" {
            return Err(format!("UNRESOLVED_SYMBOL {name} in {}", elf.display()));
        }
        let Ok(value) = usize::from_str_radix(addr, 16) else { continue };
        if value < base {
            continue;
        }
        out.push((name.to_string(), value - base));
    }
    Ok(out)
}

fn lookup(symbols: &[(String, usize)], name: &str) -> Result<usize, String> {
    symbols
        .iter()
        .find(|(n, _)| n == name)
        .map(|(_, o)| *o)
        .ok_or_else(|| format!("MISSING_SYMBOL {name}"))
}

fn link(
    build: &Path,
    name: &str,
    objects: &[PathBuf],
    base: usize,
    core_size: usize,
    assignments: &[(String, usize)],
) -> Result<(Vec<u8>, Vec<(String, usize)>), String> {
    let script = build.join(format!("{name}.ld"));
    let mut text = String::new();
    if name == "bootstrap" {
        for (symbol, value) in assignments {
            let _ = writeln!(text, "{symbol} = {value:#x};");
        }
    }
    let _ = write!(text, "SECTIONS {{ . = {base:#x}; .text : {{ *(.text .text.*) *(.rodata .rodata.*) ");
    if name == "core" {
        let _ = write!(text, " . = {:#x}; *(.cl_tail) ", core_size - FAR_PROBE_PAGE);
    }
    text.push_str("} /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) } }\n");
    std::fs::write(&script, text).map_err(|e| e.to_string())?;

    let elf = build.join(format!("{name}.elf"));
    let mut args = vec!["-T".to_string(), script.display().to_string(), "-o".to_string(), elf.display().to_string()];
    args.extend(objects.iter().map(|p| p.display().to_string()));
    tool(&{
        let mut cmd = vec![LD.to_string()];
        cmd.extend(args);
        cmd
    })?;

    let raw = build.join(format!("{name}.bin"));
    run_tool(OBJCOPY, &["-O", "binary", "--only-section=.text", &elf.display().to_string(), &raw.display().to_string()])?;

    let heads = run_tool(OBJDUMP, &["-h", &elf.display().to_string()])?;
    for line in heads.lines() {
        let cols: Vec<&str> = line.split_whitespace().collect();
        if cols.len() >= 3 && (cols[1] == ".bss" || cols[1] == ".data") {
            if cols[2].parse::<u64>().unwrap_or(0) != 0 {
                return Err(format!("SECTION_NOT_EMPTY {} {} bytes", name, cols[2]));
            }
        }
    }

    let symbols = symbols(&elf, base)?;
    let bytes = std::fs::read(&raw).map_err(|e| e.to_string())?;
    Ok((bytes, symbols))
}

fn put32(b: &mut [u8], at: usize, v: u32) {
    b[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

fn put64(b: &mut [u8], at: usize, v: u64) {
    b[at..at + 8].copy_from_slice(&v.to_le_bytes());
}

fn transform(name: &str, source: &str, hooks: &[HookEntry], fixup_names: &[&str], prctl_code: u32) -> String {
    let mut text = source.to_string();
    if name == "payload" {
        
        text = text.replace(
            "    cbnz x23, cairn_prctl_bad",
            "    cmp x23,#10\n    b.eq cl_far_probe\n    cbnz x23, cairn_prctl_bad",
        );
        text = format!(".global cairn_prctl_out\n{text}");
        let _ = prctl_code;
    }
    let mut prefix = String::new();
    for hook in hooks {
        if text.contains(&format!("{}:", hook.name)) {
            let _ = writeln!(prefix, ".global {}", hook.name);
        }
    }
    prefix.push('\n');
    text = format!("{prefix}{text}");

    for (i, label) in fixup_names.iter().enumerate() {
        text = replace_placeholder_branch(&text, label, i);
    }
    text
}

fn replace_placeholder_branch(text: &str, label: &str, index: usize) -> String {
    let lines: Vec<&str> = text.split('\n').collect();
    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    let mut i = 0;
    while i < lines.len() {
        out.push(lines[i].to_string());
        if lines[i].trim() == format!("{label}:") {
            let mut j = i + 1;
            while j < lines.len() && lines[j].trim().is_empty() {
                out.push(lines[j].to_string());
                j += 1;
            }
            if j < lines.len() {
                
                let body = lines[j].trim();
                let normalised = body.split_whitespace().collect::<Vec<_>>().join(" ");
                let replaced = if normalised == "b 0" {
                    Some(format!("b cl_core_bridge_{index}"))
                } else if normalised == "bl 0" {
                    Some(format!("bl cl_core_bridge_{index}"))
                } else {
                    None
                };
                if let Some(word) = replaced {
                    out.push(word);
                    i = j + 1;
                    continue;
                }
            }
            i = j;
            continue;
        }
        i += 1;
    }
    out.join("\n")
}

fn destination(target: usize, native: &mut Vec<(String, usize)>) -> String {
    let name = format!("cl_kaddr_{target:x}");
    if !native.iter().any(|(n, _)| *n == name) {
        native.push((name.clone(), target));
    }
    name
}

pub fn core_package(core: &[u8]) -> Vec<u8> {
    
    let mut out = Vec::with_capacity(48 + core.len());
    for v in [0x434c_4431u32, 1, 0x434e_0001, core.len() as u32] {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&sha256::digest(core));
    out.extend_from_slice(core);
    out
}

pub fn run(
    kernel: &[u8],
    plan: &Plan,
    source: &Path,
    build: &Path,
    key_override: Option<&[u8; 16]>,
) -> Result<(Report, Vec<u8>, Vec<u8>), String> {
    std::fs::create_dir_all(build).map_err(|e| e.to_string())?;
    let placement = &plan.placement;

    let template = std::fs::read_to_string(source.join("layout.h")).map_err(|e| e.to_string())?;
    let header = payload_layout::render_header(&template, &plan.layout)?;
    let written = payload_layout::read_header(&header)?;
    for (name, value) in &written {
        let want = plan.layout.iter().find(|o| o.name == *name).unwrap().value;
        if *value != want {
            return Err(format!("LAYOUT_HEADER_DISAGREES {name}: wrote {value}, plan {want}"));
        }
    }
    std::fs::write(build.join("layout.h"), &header).map_err(|e| e.to_string())?;

    let mut replays_inlined = 0usize;
    let mut replays_branched = 0usize;
    let mut flags: Vec<String> = [
        "-Os", "-ffreestanding", "-fno-builtin", "-fno-stack-protector",
        "-fno-asynchronous-unwind-tables", "-fno-unwind-tables", "-mgeneral-regs-only",
        "-mno-outline-atomics", "-fno-pic", "-mbranch-protection=bti", "-Wall", "-Wextra",
        "-Werror", "-Wno-unused-function",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    flags.push("-DCAIRN_REPLAY_SLOTS".into());
    flags.push(format!("-DCAIRN_MMAP_FILE_SRC={}", plan.mmap_file_register));
    
    const CAIRN_SAVED_BLOCK: u32 = 96;
    let depth = plan.prctl_frame.locals;
    
    let locals = depth.saturating_sub(CAIRN_SAVED_BLOCK);
    flags.push(format!("-DCAIRN_PRCTL_LOCALS={locals}"));
    
    flags.push(format!("-DCAIRN_PRCTL_FRAME_DEPTH={depth}"));
    flags.push(format!(
        "-DCAIRN_PRCTL_FP_OFFSET=#{}",
        plan.prctl_frame.frame_pointer
    ));
    
    if plan.prctl_frame.needs_replay {
        let words = prologue_span(kernel, plan, "cairn_prctl", "cairn_prctl_tail")?;
        let mut text = String::new();
        for (j, word) in words.iter().enumerate() {
            if j > 0 {
                text.push(',');
            }
            let _ = write!(text, "{word:#x}");
        }
        flags.push(format!("-DCAIRN_PRCTL_REPLAY={text}"));
        
        flags.push(format!("-DCAIRN_PRCTL_REPLAY_SCRATCH={}", CAIRN_SAVED_BLOCK + 16));
    }

    for (flag, hook, continuation) in PROLOGUE_SPANS {
        let words = prologue_span(kernel, plan, hook, continuation)?;
        
        if *flag == "CAIRN_PROLOGUE_STAT" {
            let family = plan.version.strategy.name();
            let frame = prologue_frame_check(&words, family)?;
            
            let stat_site = plan.sites.iter().find(|s| s.name == "cairn_stat");
            let syscall_shape = stat_site
                .map(|s| s.decided == "syscall after-call")
                .unwrap_or(false);
            if syscall_shape {
                flags.push("-DCAIRN_STAT_SYSCALL".into());
            }
            if std::env::var_os("CAIRN_TRACE_SITES").is_some() {
                eprintln!(
                    "TRACE prologue {flag} family={family} words={} frame={frame:#x} \
                     shape={} span={:?}",
                    words.len(),
                    if syscall_shape { "syscall" } else { "filename" },
                    words.iter().map(|w| format!("{w:#010x}")).collect::<Vec<_>>()
                );
            }
        }
        let mut text = String::new();
        for (j, word) in words.iter().enumerate() {
            if j > 0 {
                text.push(',');
            }
            let _ = write!(text, "{word:#x}");
        }
        flags.push(format!("-D{flag}={text}"));
    }

    let hook_sites = plan
        .sites
        .split_last()
        .map(|(_, rest)| rest)
        .ok_or("SEQUENCE_EMPTY no boot site")?;
    let mut hooks: Vec<HookEntry> = Vec::with_capacity(hook_sites.len());
    for (i, site) in hook_sites.iter().enumerate() {
        let original = u32::from_le_bytes(
            kernel
                .get(site.offset..site.offset + 4)
                .ok_or("site outside Image")?
                .try_into()
                .unwrap(),
        );
        if original != site.word {
            return Err(format!("SITE_CHANGED {}", site.name));
        }
        let derived = replay::derive(original, site.offset)?;
        let linked = matches!(derived, replay::Replay::Branch { .. });
        if linked {
            replays_branched += 1;
        } else {
            
            if let replay::Replay::Inline(encoded) = derived {
                if encoded != original {
                    return Err(format!("REPLAY_ENCODE_MISMATCH {} {original:#x}", site.name));
                }
            }
            flags.push(format!("-DCAIRN_REPLAY_{i}={original:#x}"));
            replays_inlined += 1;
        }
        hooks.push(HookEntry {
            name: site.name,
            offset: site.offset,
            word: original,
            gateway: 0,
            linked,
        });
    }

    let fixup_names: Vec<&str> = plan.fixups.fixups.iter().map(|f| f.name).collect();
    let mut objects: Vec<PathBuf> = Vec::new();
    
    let payload_name = payload_for(plan.version.strategy);
    for name in ASSEMBLY {
        let file = if *name == "payload" { payload_name } else { name };
        let text = std::fs::read_to_string(source.join(format!("{file}.S")))
            .map_err(|e| format!("read {file}.S: {e}"))?;
        let text = transform(name, &text, &hooks, &fixup_names, 0);
        let asm = build.join(format!("{name}.S"));
        std::fs::write(&asm, &text).map_err(|e| e.to_string())?;
        let pre = build.join(format!("{name}.i"));
        let mut args = vec!["-E".to_string(), "-x".to_string(), "assembler-with-cpp".to_string()];
        args.extend(flags.iter().cloned());
        args.push("-I".into());
        args.push(build.display().to_string());
        args.push("-I".into());
        args.push(source.display().to_string());
        args.push("-o".into());
        args.push(pre.display().to_string());
        args.push(asm.display().to_string());
        tool(&{
            let mut cmd = vec![GCC.to_string()];
            cmd.extend(args);
            cmd
        })?;
        let obj = build.join(format!("{name}.o"));
        run_tool(AS, &["-o", &obj.display().to_string(), &pre.display().to_string()])?;
        objects.push(obj);
    }

    let mut bridges = String::from(
        "\n.section .text\n.balign 8\n.section .cl_tail,\"ax\"\n.global cl_far_probe\ncl_far_probe:\n mov w0,#0x4f4b\n movk w0,#0x434c,lsl #16\n b cairn_prctl_out\n.section .text\n",
    );
    for (i, hook) in hooks.iter().enumerate() {
        let _ = write!(bridges, "cl_core_entry_{i}:\n bti jc\n b {}\n", hook.name);
    }
    for i in 0..plan.fixups.fixups.len() {
        let _ = write!(
            bridges,
            "\n.balign 8\n.global cl_core_bridge_{i}\ncl_core_bridge_{i}:\n bti jc\n ldr x16,cl_core_target_{i}\n br x16\n.balign 8\ncl_core_target_{i}:\n .quad 0\n"
        );
    }
    let bridge_asm = build.join("core-bridges.S");
    std::fs::write(&bridge_asm, &bridges).map_err(|e| e.to_string())?;
    let bridge_obj = build.join("core-bridges.o");
    run_tool(AS, &["-o", &bridge_obj.display().to_string(), &bridge_asm.display().to_string()])?;
    objects.push(bridge_obj);

    for name in C_SOURCES {
        let obj = build.join(format!("{name}_c.o"));
        let mut args: Vec<String> = flags.clone();
        args.push("-I".into());
        args.push(build.display().to_string());
        args.push("-I".into());
        args.push(source.display().to_string());
        args.push("-c".into());
        args.push(source.join(format!("{name}.c")).display().to_string());
        args.push("-o".into());
        args.push(obj.display().to_string());
        tool(&{
            let mut cmd = vec![GCC.to_string()];
            cmd.extend(args);
            cmd
        })?;
        objects.push(obj);
    }

    let (mut core, core_symbols) = link(build, "core", &objects, 0, CORE_SIZE, &[])?;
    let core_actual = core.len();
    if core.len() > CORE_SIZE {
        return Err(format!("CORE_TOO_LARGE {} > {CORE_SIZE}", core.len()));
    }
    core.resize(CORE_SIZE, 0);
    
    let key_offset = lookup(&core_symbols, "cairn_hash")?;
    if core[key_offset..key_offset + 16].iter().any(|&b| b != 0) {
        return Err("CORE_KEY_SLOT_NOT_ZERO".into());
    }
    let digest = sha256::digest(&core);
    let package = core_package(&core);

    std::fs::write(build.join("core.bin"), &core).map_err(|e| e.to_string())?;
    std::fs::write(build.join("core.cld"), &package).map_err(|e| e.to_string())?;

    let mut native: Vec<(String, usize)> = Vec::new();
    let mut gateways = String::from(".section .text\n");
    for (i, fixup) in plan.fixups.fixups.iter().enumerate() {
        let _ = write!(
            gateways,
            "\n.balign 4\ncl_kernel_bridge_{i}:\n bti jc\n b {}\n",
            destination(fixup.target, &mut native)
        );
    }
    for (i, hook) in hooks.iter_mut().enumerate() {
        let gateway = if hook.name == "cairn_prctl" {
            "cl_gateway_prctl".to_string()
        } else {
            format!("cl_gate_{i}")
        };
        let entry = lookup(&core_symbols, &format!("cl_core_entry_{i}"))?;
        let _ = write!(
            gateways,
            "\n.balign 4\n.global {gateway}\n{gateway}:\n adr x16,cl_state_delta\n ldr x17,[x16]\n add x16,x16,x17\n add x16,x16,#8\n ldar x16,[x16]\n cbz x16,cl_fallback_{i}\n mov x17,#{entry}\n add x16,x16,x17\n br x16\ncl_fallback_{i}:\n"
        );
        let target = if hook.linked {
            
            let delta = (((hook.word & 0x03ff_ffff) as i64) << 2) as i32;
            let signed = if delta & (1 << 27) != 0 { delta - (1 << 28) } else { delta };
            let to = (hook.offset as i64 + signed as i64) as usize;
            format!(" b {}\n", destination(to, &mut native))
        } else {
            format!(" .inst {:#x}\n b {}\n", hook.word, destination(hook.offset + 4, &mut native))
        };
        gateways.push_str(&target);
        hook.gateway = 0;
    }
    for target in &plan.fixups.targets {
        
        if target.name == "cl_allocate" && plan.fixups.alloc_family == crate::fixups::AllocFamily::Execmem {
            let _ = write!(
                gateways,
                "\n.global {}\n{}:\n mov x1, x0\n mov w0, #{}\n b {}\n",
                target.name,
                target.name,
                crate::fixups::EXECMEM_MODULE_TEXT,
                destination(target.target, &mut native)
            );
            continue;
        }
        let _ = write!(
            gateways,
            "\n.global {}\n{}:\n b {}\n",
            target.name,
            target.name,
            destination(target.target, &mut native)
        );
    }
    let request = core.len() + 48;
    let _ = write!(
        gateways,
        "\n.balign 8\n.global cl_state_delta\ncl_state_delta:\n .quad 0\n.global cl_request_bytes\ncl_request_bytes:\n .word {request}\n"
    );
    let gateway_asm = build.join("gateways.S");
    std::fs::write(&gateway_asm, &gateways).map_err(|e| e.to_string())?;
    let gateway_obj = build.join("gateways.o");
    run_tool(AS, &["-o", &gateway_obj.display().to_string(), &gateway_asm.display().to_string()])?;

    for name in ["gateway.S", "boot.S", "loader.c", "format.h", "sha256.h"] {
        std::fs::copy(source.join("loader").join(name), build.join(name)).map_err(|e| e.to_string())?;
    }
    let gateway_loader_obj = build.join("gateway.o");
    let boot_obj = build.join("boot.o");
    run_tool(AS, &["-o", &gateway_loader_obj.display().to_string(), &build.join("gateway.S").display().to_string()])?;
    run_tool(AS, &["-o", &boot_obj.display().to_string(), &build.join("boot.S").display().to_string()])?;

    let mut relatives: Vec<&fixups::Relative> = plan
        .fixups
        .relatives
        .iter()
        .filter(|r| !r.name.ends_with("_slot_delta"))
        .collect();
    relatives.extend(plan.fixups.relatives.iter().filter(|r| r.name.ends_with("_slot_delta")));

    let native_map: Vec<(String, usize)> = native.clone();
    let mut previous: Vec<(String, usize)> = Vec::new();
    let mut bootstrap = Vec::new();
    let mut bootstrap_symbols = Vec::new();
    let mut converged = false;
    for _ in 0..4 {
        let mut generated = String::from("/* Generated build-specific relocations; no authorization key. */\n");
        let _ = write!(
            generated,
            "#define CL_CORE_BYTES {CORE_SIZE}U\n#define CL_CORE_PAGES {}U\n#define CL_KEY_OFFSET {key_offset}\n#define CL_BRIDGES {}\n#define CL_RELATIVES {}\n",
            CORE_SIZE / 4096,
            plan.fixups.fixups.len(),
            relatives.len()
        );
        let _ = write!(
            generated,
            "static const unsigned char cl_digest[32]={{{}}};\n",
            digest.iter().map(|b| b.to_string()).collect::<Vec<_>>().join(",")
        );
        let mut fixup_table: Vec<String> = Vec::new();
        for (i, _) in plan.fixups.fixups.iter().enumerate() {
            let offset = lookup(&core_symbols, &format!("cl_core_target_{i}"))?;
            let bridge = lookup(&bootstrap_symbols, &format!("cl_kernel_bridge_{i}")).unwrap_or(0);
            fixup_table.push(format!("{{{offset},{}}}", placement.base + bridge));
        }
        let _ = write!(generated, "static const struct cl_fixup cl_fixups[]={{{}}};\n", fixup_table.join(","));
        let mut relative_table: Vec<String> = Vec::new();
        for relative in &relatives {
            let offset = lookup(&core_symbols, relative.name)?;
            relative_table.push(format!("{{{offset},{}}}", relative.target));
        }
        let _ = write!(
            generated,
            "static const struct cl_relative cl_relatives[]={{{}}};\n",
            relative_table.join(",")
        );
        std::fs::write(build.join("generated.h"), &generated).map_err(|e| e.to_string())?;

        let loader_obj = build.join("loader.o");
        let mut args: Vec<String> = flags.clone();
        args.push("-c".into());
        args.push(build.join("loader.c").display().to_string());
        args.push("-o".into());
        args.push(loader_obj.display().to_string());
        tool(&{
            let mut cmd = vec![GCC.to_string()];
            cmd.extend(args);
            cmd
        })?;

        let linked = link(
            build,
            "bootstrap",
            &[gateway_loader_obj.clone(), boot_obj.clone(), gateway_obj.clone(), loader_obj],
            placement.base,
            CORE_SIZE,
            &native_map,
        )?;
        if linked.1 == previous {
            bootstrap = linked.0;
            bootstrap_symbols = linked.1;
            converged = true;
            break;
        }
        previous = linked.1.clone();
        bootstrap = linked.0;
        bootstrap_symbols = linked.1;
    }
    if !converged {
        return Err("BOOTSTRAP_LAYOUT_NOT_CONVERGED".into());
    }
    if bootstrap.len() > placement.capacity {
        return Err(format!("BOOTSTRAP_TOO_LARGE {} > {}", bootstrap.len(), placement.capacity));
    }
    
    let mut bootstrap = bootstrap;
    let state_at = lookup(&bootstrap_symbols, "cl_state_delta")?;
    let anchor_at = lookup(&bootstrap_symbols, "cl_anchor")?;
    let kernel_at = lookup(&bootstrap_symbols, "cl_kernel_delta")?;
    let state = placement.state as i64 - (placement.base + state_at) as i64;
    put64(&mut bootstrap, state_at, state as u64);
    put64(&mut bootstrap, kernel_at, (placement.base + anchor_at) as u64);

    let auth_at = lookup(&bootstrap_symbols, "cl_auth")?;
    if bootstrap.get(auth_at..auth_at + 16).map(|s| s.iter().all(|b| *b == 0)).unwrap_or(false) {
        let key = match key_override {
            Some(k) => *k,
            None => crate::random_key(),
        };
        bootstrap[auth_at..auth_at + 16].copy_from_slice(&key);
    }
    let auth_key = <[u8; 16]>::try_from(&bootstrap[auth_at..auth_at + 16])
        .map_err(|_| "AUTH_SLOT_TRUNCATED".to_string())?;
    std::fs::write(build.join("bootstrap.bin"), &bootstrap).map_err(|e| e.to_string())?;

    let hooks_report: Vec<HookEntry> = hooks
        .iter()
        .map(|h| {
            let entry = lookup(
                &bootstrap_symbols,
                if h.name == "cairn_prctl" {
                    "cl_gateway_prctl"
                } else {
                    
                    ""
                },
            );
            let gateway = match entry {
                Ok(v) => v,
                Err(_) => 0,
            };
            HookEntry { gateway, ..h.clone() }
        })
        .collect();

    let auth_offset = lookup(&bootstrap_symbols, "cl_auth")?;
    let boot_entry = lookup(&bootstrap_symbols, "cl_boot_entry")?;
    let boot_site = plan.sites.last().ok_or("missing boot site")?;
    let report = Report {
        bootstrap_base: placement.base,
        bootstrap_bytes: bootstrap.len(),
        bootstrap_capacity: placement.capacity,
        core_bytes: core.len(),
        core_sha256: sha256::hex(&digest),
        core_symbols,
        bootstrap_symbols,
        hooks: hooks_report,
        fixups: plan.fixups.fixups.clone(),
        relatives: relatives.into_iter().cloned().collect(),
        targets: plan.fixups.targets.clone(),
        layout: plan.layout.clone(),
        replays_inlined,
        replays_branched,
        mmap_file_register: plan.mmap_file_register,
        prctl_locals: u64::from(plan.prctl_frame.locals),
        auth_offset,
        auth_key,
        boot_hook: (boot_site.offset, boot_site.word, boot_entry),
    };
    let _ = core_actual;
    Ok((report, package, bootstrap))
}

pub fn write_metadata(report: &Report, plan: &Plan, build: &Path) -> Result<(), String> {
    let mut text = String::new();
    let _ = writeln!(text, "pub const BOOTSTRAP_BASE:usize={:#x};", report.bootstrap_base);
    let _ = writeln!(text, "pub const AUTH_OFFSET:usize={};", report.auth_offset);
    let _ = writeln!(
        text,
        "pub const BOOT_HOOK:(usize,u32,usize)=({:#x},{:#x},{});",
        report.boot_hook.0, report.boot_hook.1, report.boot_hook.2
    );
    let _ = writeln!(text, "pub const CORE_PROFILE:u32=0x434e0001;");
    let _ = writeln!(text, "pub const CORE_BYTES:usize={};", report.core_bytes);
    let _ = writeln!(
        text,
        "pub const CORE_SHA256:[u8;32]=[{}];",
        sha256::digest(&std::fs::read(build.join("core.bin")).map_err(|e| e.to_string())?)
            .iter()
            .map(|b| b.to_string())
            .collect::<Vec<_>>()
            .join(",")
    );
    let _ = writeln!(text, "pub const HOOKS:&[(usize,u32,usize,bool)]=&[");
    let (sites, _) = image_sites(report, plan)?;
    for site in &sites {
        let _ = writeln!(
            text,
            " ({:#x},{:#x},{},{}),",
            site.offset, site.word, site.entry, site.linked
        );
    }
    let _ = writeln!(text, "];");
    std::fs::write(build.join("detached_profile.rs"), text).map_err(|e| e.to_string())?;

    let mut symbols = String::from("&[\n");
    for (name, offset) in &report.core_symbols {
        let _ = writeln!(symbols, " ({name:?},{offset}),");
    }
    symbols.push_str("]\n");
    std::fs::write(build.join("detached_symbols.rs"), symbols).map_err(|e| e.to_string())?;

    let mut layout = String::from(
        "// The struct offsets this payload was assembled against, as (struct, member, offset).\n\
         pub const PAYLOAD_LAYOUT:&[(&str,&str,u64)]=&[\n",
    );
    for (name, structure, member) in payload_layout::FIELDS {
        let offset = report
            .layout
            .iter()
            .find(|o| o.name == *name)
            .ok_or_else(|| format!("LAYOUT_ENTRY_MISSING {name}"))?;
        let _ = writeln!(layout, " ({structure:?},{member:?},{}),", offset.value);
    }
    layout.push_str("];\n");
    std::fs::write(build.join("detached_layout.rs"), layout).map_err(|e| e.to_string())?;
    Ok(())
}

pub fn image_sites(report: &Report, plan: &Plan) -> Result<(Vec<image::Site>, image::Site), String> {
    let mut sites = Vec::with_capacity(report.hooks.len());
    for (i, hook) in report.hooks.iter().enumerate() {
        let entry = if hook.name == "cairn_prctl" {
            
            lookup(&report.bootstrap_symbols, "cl_prctl_entry")?
        } else {
            lookup(&report.bootstrap_symbols, &format!("cl_gate_{i}"))?
        };
        sites.push(image::Site {
            name: hook.name,
            offset: hook.offset,
            word: hook.word,
            entry,
            linked: hook.linked,
        });
    }
    let boot = plan.sites.last().ok_or("missing boot site")?;
    let boot_entry = lookup(&report.bootstrap_symbols, "cl_boot_entry")?;
    let word = boot.word;
    Ok((
        sites,
        image::Site { name: boot.name, offset: boot.offset, word, entry: boot_entry, linked: true },
    ))
}

pub fn replace_kernel(boot: &mut Vec<u8>, wrapped: &[u8], image: &[u8]) -> Result<(), String> {
    if boot.len() < 4096 || &boot[..8] != b"ANDROID!" {
        return Err("BOOT_NOT_ANDROID".into());
    }
    if !crate::kcompress::is_arm64_image(image) {
        return Err("PATCHED_KERNEL_NOT_ARM64".into());
    }
    let header = |at: usize| -> Result<usize, String> {
        Ok(u32::from_le_bytes(
            boot.get(at..at + 4).ok_or("BOOT_TRUNCATED")?.try_into().unwrap(),
        ) as usize)
    };
    let old_size = header(8)?;
    
    let ramdisk_size = header(12)?;
    let vendor_size = if boot.len() >= 0x1c { header(0x18)? } else { 0 };

    let old_end = 4096usize
        .checked_add(old_size)
        .ok_or("BOOT_KERNEL_SIZE_OVERFLOW")?;
    if old_end > boot.len() {
        return Err("BOOT_KERNEL_OUT_OF_RANGE".into());
    }
    let old_next = align_up(old_end, 4096);

    let mut tail: Vec<u8> = Vec::new();
    let mut cursor = old_next;
    for size in [ramdisk_size, vendor_size] {
        if size == 0 {
            continue;
        }
        let end = cursor.checked_add(size).ok_or("BOOT_SECTION_OVERFLOW")?;
        let section = boot.get(cursor..end).ok_or("BOOT_SECTION_OUT_OF_RANGE")?;
        let padded = align_up(size, 4096);
        tail.extend_from_slice(section);
        tail.resize(tail.len() + (padded - size), 0);
        cursor = align_up(end, 4096);
    }
    let old_tail_start = old_next;

    let new_end = 4096usize
        .checked_add(wrapped.len())
        .ok_or("BOOT_KERNEL_SIZE_OVERFLOW")?;
    let new_next = align_up(new_end, 4096);
    let moved_to = new_next;

    if moved_to + tail.len() > boot.len() {
        return Err(format!(
            "BOOT_SECTIONS_DO_NOT_FIT kernel_end={new_next:#x} tail={:#x} buffer={:#x}",
            tail.len(),
            boot.len()
        ));
    }

    let footer = boot.len() - 64;
    let has_footer = &boot[footer..footer + 4] == b"AVBf";
    let saved_footer: Vec<u8> = if has_footer { boot[footer..].to_vec() } else { Vec::new() };

    boot[4096..footer].fill(0);
    boot[4096..new_end].copy_from_slice(wrapped);
    boot[moved_to..moved_to + tail.len()].copy_from_slice(&tail);

    if has_footer {
        boot[footer..].copy_from_slice(&saved_footer);
        let delta = moved_to as i64 - old_tail_start as i64;
        if delta != 0 && !tail.is_empty() {
            relocate_avb_footer(boot, delta)?;
        }
    }

    boot[8..12].copy_from_slice(
        &u32::try_from(wrapped.len()).map_err(|_| "KERNEL_TOO_LARGE")?.to_le_bytes(),
    );
    Ok(())
}

fn align_up(value: usize, alignment: usize) -> usize {
    value
        .checked_add(alignment - 1)
        .map(|v| v & !(alignment - 1))
        .unwrap_or(value)
}

fn trailing_end(boot: &[u8]) -> usize {
    match boot.iter().rposition(|b| *b != 0) {
        Some(last) => last + 1,
        None => 0,
    }
}

fn relocate_avb_footer(boot: &mut [u8], delta: i64) -> Result<(), String> {
    if boot.len() < 64 {
        return Err("BOOT_TOO_SMALL_FOR_FOOTER".into());
    }
    let footer = boot.len() - 64;
    if &boot[footer..footer + 4] != b"AVBf" {
        
        return Ok(());
    }
    let mut fix = |at: usize, label: &str| -> Result<(), String> {
        let old = u64::from_be_bytes(
            boot[at..at + 8].try_into().map_err(|_| format!("AVB_{label}_TRUNCATED"))?,
        );
        let moved = i64::try_from(old)
            .map_err(|_| format!("AVB_{label}_OUT_OF_RANGE"))?
            .checked_add(delta)
            .ok_or(format!("AVB_{label}_OVERFLOW"))?;
        let moved = u64::try_from(moved).map_err(|_| format!("AVB_{label}_NEGATIVE"))?;
        boot[at..at + 8].copy_from_slice(&moved.to_be_bytes());
        Ok(())
    };
    fix(footer + 12, "ORIGINAL")?;
    fix(footer + 20, "VBMETA")?;
    Ok(())
}

pub fn out_dir(root: &str, release: &str) -> PathBuf {
    let safe: String = release
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' })
        .collect();
    Path::new(root).join(safe)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_branch_keeps_b_or_bl() {
        let text = "foo:\n b 0\nbar:\n bl 0\n";
        let out = replace_placeholder_branch(text, "foo", 3);
        assert!(out.contains("b cl_core_bridge_3"), "{out}");
        assert!(out.contains("bl 0"), "other labels must be untouched: {out}");
        let out = replace_placeholder_branch(text, "bar", 7);
        assert!(out.contains("bl cl_core_bridge_7"), "{out}");
        assert!(out.contains("b 0"), "{out}");
    }

    #[test]
    fn placeholder_branch_skips_blank_lines_and_misses_cleanly() {
        let text = "foo:\n\n\n b 0\n";
        let out = replace_placeholder_branch(text, "foo", 1);
        assert!(out.contains("b cl_core_bridge_1"), "{out}");
        let none = replace_placeholder_branch("foo:\n nop\n", "foo", 1);
        assert_eq!(none, "foo:\n nop\n");
    }

    #[test]
    fn transform_exports_only_present_labels() {
        let hooks = vec![
            HookEntry { name: "cairn_avc", offset: 0, word: 0, gateway: 0, linked: false },
            HookEntry { name: "not_here", offset: 0, word: 0, gateway: 0, linked: false },
        ];
        let out = transform("payload", "cairn_avc:\n nop\n", &hooks, &[], 0);
        assert!(out.contains(".global cairn_avc"));
        assert!(!out.contains(".global not_here"));
        assert!(out.contains(".global cairn_prctl_out"));
    }

    #[test]
    fn core_package_header_matches_the_loader_format() {
        let core = vec![0u8; 32];
        let package = core_package(&core);
        assert_eq!(package.len(), 48 + 32);
        assert_eq!(&package[..4], &0x434c_4431u32.to_le_bytes());
        assert_eq!(u32::from_le_bytes(package[4..8].try_into().unwrap()), 1);
        assert_eq!(&package[12..16], &32u32.to_le_bytes());
        assert_eq!(&package[16..48], &sha256::digest(&core));
        assert_eq!(&package[48..], &core[..]);
    }
    fn sample(name: &str) -> Option<Vec<u8>> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        std::fs::read(root.join(name).join("kernel.bin")).ok()
    }

    #[test]
    fn prologue_spans_come_from_the_target_image() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        let mut seen: Vec<(String, Vec<u32>)> = Vec::new();
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63"] {
            let Ok(kernel) = std::fs::read(root.join(dir).join("kernel.bin")) else {
                println!("PROLOGUE_SPAN_SKIPPED no {dir} sample");
                continue;
            };
            let symbols = crate::ksyms::discover(&kernel).expect("kallsyms");
            let plan = crate::dynamic_plan::derive(&kernel, &symbols).expect("plan");
            for (flag, hook, continuation) in PROLOGUE_SPANS {
                let words = prologue_span(&kernel, &plan, hook, continuation)
                    .unwrap_or_else(|e| panic!("{dir} {flag}: {e}"));
                assert!(!words.is_empty(), "{dir} {flag}: empty span");
                
                let site = plan.sites.iter().find(|s| s.name == *hook).unwrap();
                for (i, word) in words.iter().enumerate() {
                    let at = site.offset + i * 4;
                    let actual = u32::from_le_bytes(kernel[at..at + 4].try_into().unwrap());
                    assert_eq!(*word, actual, "{dir} {flag} word {i} at {at:#x}");
                }
                println!("PROLOGUE_SPAN {dir} {flag} words={} first={:#010x}", words.len(), words[0]);
                seen.push((format!("{flag}:{dir}"), words));
            }
        }
        
        let a = seen.iter().find(|(k, _)| k == "CAIRN_PROLOGUE_STAT:1ce5a4f8b813ed21");
        let b = seen.iter().find(|(k, _)| k == "CAIRN_PROLOGUE_STAT:2178360d572d1f63");
        if let (Some((_, a)), Some((_, b))) = (a, b) {
            assert_ne!(a, b, "vfs_statx prologue must differ between 6.1 and 6.6");
        }
    }

    #[test]
    fn builds_a_patched_image_for_the_6_1_sample() {
        let Some(kernel) = sample("1ce5a4f8b813ed21") else {
            println!("NATIVE_BUILD_SKIPPED no 6.1 sample");
            return;
        };
        let symbols = crate::ksyms::discover(&kernel).expect("kallsyms");
        let plan = crate::dynamic_plan::derive(&kernel, &symbols).expect("plan");
        let source = Path::new(env!("CARGO_MANIFEST_DIR"));
        let build = std::env::temp_dir().join("cairn-native-build-6.1");
        let _ = std::fs::remove_dir_all(&build);
        let (report, package, bootstrap) = run(&kernel, &plan, source, &build, Some(&[0x11u8;16])).expect("build");
        println!(
            "NATIVE_BUILD 6.1 core={} digest={} bootstrap={}/{} inlined={} branched={}",
            report.core_bytes,
            &report.core_sha256[..16],
            report.bootstrap_bytes,
            report.bootstrap_capacity,
            report.replays_inlined,
            report.replays_branched
        );
        
        assert_eq!(package.len(), 48 + report.core_bytes);
        assert_eq!(&package[..4], &0x434c_4431u32.to_le_bytes());
        assert_eq!(
            sha256::hex(&sha256::digest(&package[48..])),
            report.core_sha256,
            "package body must be the reported core"
        );
        assert!(
            report.replays_inlined + report.replays_branched == plan.sites.len() - 1,
            "every hook site needs one replay form, boot site excluded: {} + {} vs {}",
            report.replays_inlined,
            report.replays_branched,
            plan.sites.len() - 1
        );

        let (sites, boot) = image_sites(&report, &plan).expect("image sites");
        let patched = image::apply(&kernel, &bootstrap, &sites, &boot, &plan.placement).expect("apply");
        println!(
            "NATIVE_IMAGE 6.1 bytes={} changed={} in_cave={} site_words={} hooks={}",
            patched.image.len(),
            patched.changed_bytes,
            patched.changed_bytes_in_cave,
            patched.changed_site_words,
            patched.hook_branches
        );
        assert_eq!(patched.changed_bytes, patched.changed_bytes_in_cave + patched.changed_site_words);
        assert!(patched.changed_bytes_in_cave > 0, "bootstrap must have landed in the cave");
        assert_eq!(patched.hook_branches, sites.len() + 1);
        assert!(patched.changed_site_words > 0, "hook sites must have moved");
        let at = std::env::temp_dir().join("cairn-native-build-6.1/kernel-patched.bin");
        std::fs::write(&at, &patched.image).expect("write image");
        write_metadata(&report, &plan, &build).expect("metadata");
        println!("NATIVE_IMAGE_PATH {}", at.display());
    }

    #[test]
    fn builds_every_sample_the_planner_accepts() {
        for (name, want_hooks) in [
            ("1ce5a4f8b813ed21", 19usize),
            ("2178360d572d1f63", 19),
            ("888332748bd958be", 19),
        ] {
            let Some(kernel) = sample(name) else {
                println!("NATIVE_SAMPLE_SKIPPED {name}");
                continue;
            };
            let symbols = match crate::ksyms::discover(&kernel) {
                Ok(s) => s,
                Err(e) => {
                    println!("NATIVE_SAMPLE_REFUSED {name}: kallsyms {e}");
                    continue;
                }
            };
            let plan = match crate::dynamic_plan::derive(&kernel, &symbols) {
                Ok(p) => p,
                Err(e) => {
                    println!("NATIVE_SAMPLE_REFUSED {name}: plan {e}");
                    continue;
                }
            };
            let source = Path::new(env!("CARGO_MANIFEST_DIR"));
            let build = std::env::temp_dir().join(format!("cairn-native-build-{name}"));
            let _ = std::fs::remove_dir_all(&build);
            let (report, package, bootstrap) = match run(&kernel, &plan, source, &build, Some(&[0x11u8;16])) {
                Ok(v) => v,
                Err(e) => {
                    println!("NATIVE_SAMPLE_BUILD_REFUSED {name}: {e}");
                    continue;
                }
            };
            let (sites, boot) = image_sites(&report, &plan).expect("image sites");
            let patched = image::apply(&kernel, &bootstrap, &sites, &boot, &plan.placement)
                .expect("apply");
            
            std::fs::write(build.join("kernel-patched.bin"), &patched.image)
                .expect("write image");
            write_metadata(&report, &plan, &build).expect("metadata");
            println!(
                "NATIVE_SAMPLE {name} release={} hooks={} core={} bootstrap={} changed={} in_cave={} sites={}",
                plan.version.release,
                sites.len(),
                report.core_bytes,
                report.bootstrap_bytes,
                patched.changed_bytes,
                patched.changed_bytes_in_cave,
                patched.changed_site_words
            );
            assert_eq!(sites.len(), want_hooks, "{name} hook count");
            assert_eq!(package.len(), 48 + report.core_bytes);
            assert_eq!(patched.hook_branches, sites.len() + 1);
            assert!(
                patched.changed_bytes == patched.changed_bytes_in_cave + patched.changed_site_words,
                "{name} footprint must partition"
            );
        }
    }
}
