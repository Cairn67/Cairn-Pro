// SPDX-License-Identifier: GPL-3.0-only
// Minimal BTF reader for locating struct field offsets inside a stock kernel.
//

// Reads kernel BTF metadata when it is available.
#[derive(Debug, PartialEq, Eq)]
pub enum BtfError {
    NotFound,
    BadHeader(&'static str),
    Truncated,
    NoSuchStruct(String),
    NoSuchField(String, String),
    Ambiguous(String),
    TypeCycle,
}

impl core::fmt::Display for BtfError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            BtfError::NotFound => write!(f, "no BTF section in this kernel"),
            BtfError::BadHeader(m) => write!(f, "bad BTF header: {m}"),
            BtfError::Truncated => write!(f, "BTF data is truncated"),
            BtfError::NoSuchStruct(s) => write!(f, "struct {s} is not described by BTF"),
            BtfError::NoSuchField(s, m) => write!(f, "struct {s} has no member {m}"),
            BtfError::Ambiguous(m) => write!(f, "ambiguous BTF lookup: {m}"),
            BtfError::TypeCycle => write!(f, "BTF type graph contains a cycle"),
        }
    }
}

const BTF_MAGIC: u16 = 0xeb9f;
const KIND_UNKN: u32 = 0;
const KIND_INT: u32 = 1;
const KIND_PTR: u32 = 2;
const KIND_ARRAY: u32 = 3;
const KIND_STRUCT: u32 = 4;
const KIND_UNION: u32 = 5;
const KIND_ENUM: u32 = 6;
const KIND_FWD: u32 = 7;
const KIND_TYPEDEF: u32 = 8;
const KIND_VOLATILE: u32 = 9;
const KIND_CONST: u32 = 10;
const KIND_RESTRICT: u32 = 11;
const KIND_FUNC: u32 = 12;
const KIND_FUNC_PROTO: u32 = 13;
const KIND_VAR: u32 = 14;
const KIND_DATASEC: u32 = 15;
const KIND_FLOAT: u32 = 16;
const KIND_DECL_TAG: u32 = 17;
const KIND_TYPE_TAG: u32 = 18;
const KIND_ENUM64: u32 = 19;

#[derive(Debug, Clone)]
enum Type {
    Int { size: u32 },
    Ptr { target: u32 },
    Array { elem: u32, index: u32 },
    Struct { size: u32, members: Vec<Member> },
    Union { size: u32, members: Vec<Member> },
    Enum { size: u32 },
    Fwd,
    Typedef { target: u32 },
    Qual { target: u32 },
    Func { proto: u32 },
    FuncProto,
    Var { ty: u32 },
    DataSec { size: u32 },
    Float { size: u32 },
    DeclTag { ty: u32 },
    TypeTag { ty: u32 },
    Enum64 { size: u32 },
}

#[derive(Debug, Clone)]
struct Member {
    name: String,
    ty: u32,
    
    bit_offset: u32,
    
    bitfield: u8,
}

pub struct Btf<'a> {
    types: Vec<Option<Type>>,
    
    names: Vec<String>,
    #[allow(dead_code)]
    strings: &'a [u8],
    
    pub offset: usize,
}

fn u16_at(d: &[u8], o: usize) -> Option<u16> {
    d.get(o..o + 2).map(|s| u16::from_le_bytes([s[0], s[1]]))
}

fn u32_at(d: &[u8], o: usize) -> Option<u32> {
    d.get(o..o + 4)
        .map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]))
}

pub fn locate(d: &[u8]) -> Result<(usize, usize), BtfError> {
    let magic = BTF_MAGIC.to_le_bytes();
    let mut at = 0usize;
    while at + 24 <= d.len() {
        let Some(p) = d[at..].windows(2).position(|w| w == magic).map(|p| p + at) else {
            break;
        };
        at = p + 2;
        if let Ok(end) = blob_end(d, p) {
            return Ok((p, end));
        }
    }
    Err(BtfError::NotFound)
}

fn blob_end(d: &[u8], start: usize) -> Result<usize, BtfError> {
    if start + 24 > d.len() {
        return Err(BtfError::Truncated);
    }
    let hdr_len = u32_at(d, start + 4).ok_or(BtfError::Truncated)? as usize;
    let type_len = u32_at(d, start + 12).ok_or(BtfError::Truncated)? as usize;
    let str_off = u32_at(d, start + 16).ok_or(BtfError::Truncated)? as usize;
    let str_len = u32_at(d, start + 20).ok_or(BtfError::Truncated)? as usize;
    
    if hdr_len != 24 {
        return Err(BtfError::BadHeader("unexpected header length"));
    }
    if !(1024..=64 * 1024 * 1024).contains(&type_len) {
        return Err(BtfError::BadHeader("implausible type section"));
    }
    if !(1024..=64 * 1024 * 1024).contains(&str_len) {
        return Err(BtfError::BadHeader("implausible string section"));
    }
    
    if str_off != type_len {
        return Err(BtfError::BadHeader("string section does not follow types"));
    }
    let end = start
        .checked_add(hdr_len)
        .and_then(|v| v.checked_add(type_len))
        .and_then(|v| v.checked_add(str_len))
        .ok_or(BtfError::Truncated)?;
    if end > d.len() {
        return Err(BtfError::Truncated);
    }
    if d.get(start + hdr_len + str_off) != Some(&0u8) {
        return Err(BtfError::BadHeader("string section does not start at an empty name"));
    }
    Ok(end)
}

impl<'a> Btf<'a> {
    pub fn parse(d: &'a [u8], start: usize, end: usize) -> Result<Self, BtfError> {
        if end <= start + 24 || end > d.len() {
            return Err(BtfError::Truncated);
        }
        if u16_at(d, start) != Some(BTF_MAGIC) {
            return Err(BtfError::BadHeader("wrong magic"));
        }
        let hdr_len = u32_at(d, start + 4).ok_or(BtfError::Truncated)? as usize;
        let type_len = u32_at(d, start + 12).ok_or(BtfError::Truncated)? as usize;
        let str_off = u32_at(d, start + 16).ok_or(BtfError::Truncated)? as usize;
        let str_len = u32_at(d, start + 20).ok_or(BtfError::Truncated)? as usize;
        let types_at = start + hdr_len;
        let strings_at = types_at + str_off;
        if strings_at + str_len > end {
            return Err(BtfError::Truncated);
        }
        let strings = &d[strings_at..strings_at + str_len];
        
        let mut types: Vec<Option<Type>> = vec![None];
        let mut names: Vec<String> = vec![String::new()];
        let mut at = types_at;
        let limit = types_at + type_len;
        while at < limit {
            let name_off = u32_at(d, at).ok_or(BtfError::Truncated)? as usize;
            let info = u32_at(d, at + 4).ok_or(BtfError::Truncated)?;
            let size_or_type = u32_at(d, at + 8).ok_or(BtfError::Truncated)?;
            let kind = (info >> 24) & 0x1f;
            let vlen = (info & 0xffff) as usize;
            let kflag = info >> 31 != 0;
            let name = cstr(strings, name_off).ok_or(BtfError::Truncated)?;
            let rec = match kind {
                KIND_UNKN => Type::Fwd, 
                KIND_INT => Type::Int { size: size_or_type },
                KIND_PTR => Type::Ptr { target: size_or_type },
                KIND_ARRAY => {
                    let elem = u32_at(d, at + 12).ok_or(BtfError::Truncated)?;
                    let index = u32_at(d, at + 16).ok_or(BtfError::Truncated)?;
                    Type::Array { elem, index }
                }
                KIND_STRUCT | KIND_UNION => {
                    let mut members = Vec::with_capacity(vlen);
                    for i in 0..vlen {
                        let m = at + 12 + i * 12;
                        let mn = u32_at(d, m).ok_or(BtfError::Truncated)? as usize;
                        let mt = u32_at(d, m + 4).ok_or(BtfError::Truncated)?;
                        let mo = u32_at(d, m + 8).ok_or(BtfError::Truncated)?;
                        let (bit_offset, bitfield) = if kflag {
                            (mo & 0xffffff, (mo >> 24) as u8)
                        } else {
                            (mo, 0)
                        };
                        members.push(Member {
                            name: cstr(strings, mn)
                                .ok_or(BtfError::Truncated)?
                                .to_string(),
                            ty: mt,
                            bit_offset,
                            bitfield,
                        });
                    }
                    if kind == KIND_STRUCT {
                        Type::Struct { size: size_or_type, members }
                    } else {
                        Type::Union { size: size_or_type, members }
                    }
                }
                KIND_ENUM => Type::Enum { size: size_or_type },
                KIND_FWD => Type::Fwd,
                KIND_TYPEDEF => Type::Typedef { target: size_or_type },
                KIND_VOLATILE | KIND_CONST | KIND_RESTRICT | KIND_TYPE_TAG => {
                    Type::Qual { target: size_or_type }
                }
                KIND_FUNC => Type::Func { proto: size_or_type },
                KIND_FUNC_PROTO => Type::FuncProto,
                KIND_VAR => Type::Var { ty: size_or_type },
                KIND_DATASEC => Type::DataSec { size: size_or_type },
                KIND_FLOAT => Type::Float { size: size_or_type },
                KIND_DECL_TAG => Type::DeclTag { ty: size_or_type },
                KIND_ENUM64 => Type::Enum64 { size: size_or_type },
                other => return Err(BtfError::BadHeader(kind_name(other))),
            };
            
            let payload = match kind {
                KIND_INT => 4,
                KIND_VAR => 4,
                KIND_DECL_TAG => 4,
                KIND_STRUCT | KIND_UNION => vlen * 12,
                KIND_ENUM => vlen * 8,
                KIND_ENUM64 => vlen * 12,
                KIND_FUNC_PROTO => vlen * 8,
                KIND_DATASEC => vlen * 12,
                KIND_ARRAY => 12,
                _ => 0,
            };
            types.push(Some(rec));
            names.push(name.to_string());
            at += 12 + payload;
            if at > limit {
                return Err(BtfError::Truncated);
            }
        }
        Ok(Btf { types, names, strings, offset: start })
    }

    fn type_of(&self, id: u32) -> Option<&Type> {
        if id == 0 {
            None
        } else {
            self.types.get(id as usize - 1)?.as_ref()
        }
    }

    fn resolve(&self, id: u32) -> Result<&Type, BtfError> {
        let mut cur = id;
        for _ in 0..64 {
            let t = self.type_of(cur).ok_or(BtfError::TypeCycle)?;
            match t {
                Type::Typedef { target } | Type::Qual { target } => cur = *target,
                _ => return Ok(t),
            }
        }
        Err(BtfError::TypeCycle)
    }

    pub fn size_of(&self, id: u32) -> Result<u64, BtfError> {
        match self.resolve(id)? {
            Type::Int { size }
            | Type::Struct { size, .. }
            | Type::Union { size, .. }
            | Type::Enum { size }
            | Type::DataSec { size }
            | Type::Float { size }
            | Type::Enum64 { size } => Ok(u64::from(*size)),
            other => Err(BtfError::Ambiguous(format!("type {id} has no direct size ({other:?})"))),
        }
    }

    fn struct_ids_named(&self, want: &str) -> Vec<u32> {
        let mut out = Vec::new();
        for (i, slot) in self.types.iter().enumerate() {
            let Some(t) = slot else { continue };
            if matches!(t, Type::Struct { .. } | Type::Union { .. }) {
                if self.record_name(i as u32 + 1).as_deref() == Some(want) {
                    out.push(i as u32 + 1);
                }
            }
        }
        out
    }

    fn record_name(&self, id: u32) -> Option<String> {
        
        self.names.get(id as usize - 1).cloned()
    }

    pub fn field(&self, struct_name: &str, member: &str) -> Result<u64, BtfError> {
        let ids = self.struct_ids_named(struct_name);
        if ids.is_empty() {
            return Err(BtfError::NoSuchStruct(struct_name.to_string()));
        }
        let mut found: Option<u64> = None;
        for id in ids {
            if let Some(off) = self.lookup(id, member, 0)? {
                match found {
                    None => found = Some(off),
                    Some(prev) if prev != off => {
                        return Err(BtfError::Ambiguous(format!(
                            "{struct_name}.{member} differs between duplicate BTF records \
                             ({prev} vs {off})"
                        )))
                    }
                    Some(_) => {}
                }
            }
        }
        found.ok_or_else(|| BtfError::NoSuchField(struct_name.to_string(), member.to_string()))
    }

    fn lookup(&self, id: u32, member: &str, depth: u32) -> Result<Option<u64>, BtfError> {
        if depth > 16 {
            return Err(BtfError::TypeCycle);
        }
        let (members, size) = match self.resolve(id)? {
            Type::Struct { size, members } | Type::Union { size, members } => (members, *size),
            _ => return Ok(None),
        };
        let _ = size;
        let mut hit: Option<u64> = None;
        for m in members {
            if m.name == member {
                let byte = u64::from(m.bit_offset / 8);
                if m.bitfield != 0 {
                    return Err(BtfError::Ambiguous(format!(
                        "{member} is a bitfield; byte offset is not a whole address"
                    )));
                }
                match hit {
                    None => hit = Some(byte),
                    Some(prev) if prev == byte => {}
                    Some(_) => {
                        return Err(BtfError::Ambiguous(format!(
                            "{member} appears twice in one record"
                        )))
                    }
                }
                continue;
            }
            
            if m.name.is_empty() {
                if let Some(nested) = self.lookup(m.ty, member, depth + 1)? {
                    let abs = u64::from(m.bit_offset / 8) + nested;
                    match hit {
                        None => hit = Some(abs),
                        Some(prev) if prev == abs => {}
                        Some(_) => {
                            return Err(BtfError::Ambiguous(format!(
                                "{member} appears in more than one anonymous member"
                            )))
                        }
                    }
                }
            }
        }
        Ok(hit)
    }
}

fn cstr(d: &[u8], off: usize) -> Option<&str> {
    let end = d.get(off..)?.iter().position(|b| *b == 0)? + off;
    core::str::from_utf8(&d[off..end]).ok()
}

fn kind_name(k: u32) -> &'static str {
    match k {
        KIND_INT => "int",
        KIND_PTR => "ptr",
        KIND_ARRAY => "array",
        KIND_STRUCT => "struct",
        KIND_UNION => "union",
        KIND_ENUM => "enum",
        KIND_FWD => "fwd",
        KIND_TYPEDEF => "typedef",
        KIND_VOLATILE => "volatile",
        KIND_CONST => "const",
        KIND_RESTRICT => "restrict",
        KIND_FUNC => "func",
        KIND_FUNC_PROTO => "func_proto",
        KIND_VAR => "var",
        KIND_DATASEC => "datasec",
        KIND_FLOAT => "float",
        KIND_DECL_TAG => "decl_tag",
        KIND_TYPE_TAG => "type_tag",
        KIND_ENUM64 => "enum64",
        _ => "unknown kind",
    }
}

pub const REQUIRED: &[(&str, &str)] = &[
    ("task_struct", "cred"),
    ("task_struct", "nameidata"),
    ("cred", "uid"),
    ("cred", "euid"),
    ("cred", "fsuid"),
    ("cred", "cap_effective"),
    ("cred", "security"),
    ("file", "f_path"),
    ("file", "f_inode"),
    ("file", "f_mode"),
    ("inode", "i_security"),
    ("dentry", "d_inode"),
    ("nameidata", "path"),
    ("nameidata", "flags"),
    
    ("path", "dentry"),
    
    ("dentry", "d_name"),
    ("dentry", "d_iname"),
    ("qstr", "len"),
    ("qstr", "name"),
    
    ("common_audit_data", "u"),
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Layout {
    pub offsets: Vec<(&'static str, &'static str, u64)>,
}

impl Layout {
    pub fn get(&self, structure: &str, field: &str) -> Option<u64> {
        self.offsets
            .iter()
            .find(|(s, f, _)| *s == structure && *f == field)
            .map(|(_, _, v)| *v)
    }
}

pub fn read_layout(kernel: &[u8]) -> Result<Layout, BtfError> {
    let (start, end) = locate(kernel)?;
    let btf = Btf::parse(kernel, start, end)?;
    let mut offsets = Vec::with_capacity(REQUIRED.len());
    for (structure, field) in REQUIRED {
        offsets.push((*structure, *field, btf.field(structure, field)?));
    }
    Ok(Layout { offsets })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(dir: &str) -> Option<Vec<u8>> {
        let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"));
        let path = root.join("../../../../research/three-device-dynamic").join(dir).join("kernel.bin");
        std::fs::read(path).ok()
    }

    #[test]
    fn locates_btf_in_every_shipped_sample() {
        for dir in ["1ce5a4f8b813ed21", "2178360d572d1f63", "888332748bd958be"] {
            let Some(kernel) = sample(dir) else { continue };
            let (start, end) = locate(&kernel).unwrap_or_else(|e| panic!("{dir}: {e}"));
            assert!(end > start);
            let btf = Btf::parse(&kernel, start, end).unwrap_or_else(|e| panic!("{dir}: {e}"));
            let layout = read_layout(&kernel).unwrap_or_else(|e| panic!("{dir}: {e}"));
            assert_eq!(layout.offsets.len(), REQUIRED.len(), "{dir}");
            
            let expect: &[(&str, u64)] = match dir {
                "1ce5a4f8b813ed21" => &[
                    ("TASK_CRED", 2104), ("TASK_NAMEIDATA", 2136), ("CRED_UID", 4),
                    ("CRED_EUID", 20), ("CRED_FSUID", 28), ("CRED_CAP_EFFECTIVE", 56),
                    ("CRED_SECURITY", 120), ("FILE_F_PATH", 16), ("FILE_F_INODE", 32),
                    ("FILE_F_MODE", 68), ("INODE_I_SECURITY", 56), ("DENTRY_D_INODE", 48),
                    ("ND_PATH", 0), ("ND_FLAGS", 56),
                ],
                "2178360d572d1f63" => &[
                    ("TASK_CRED", 2080), ("TASK_NAMEIDATA", 2112), ("CRED_UID", 8),
                    ("CRED_EUID", 24), ("CRED_FSUID", 32), ("CRED_CAP_EFFECTIVE", 64),
                    ("CRED_SECURITY", 128), ("FILE_F_PATH", 168), ("FILE_F_INODE", 184),
                    ("FILE_F_MODE", 20), ("INODE_I_SECURITY", 56), ("DENTRY_D_INODE", 48),
                    ("ND_PATH", 0), ("ND_FLAGS", 56),
                ],
                _ => &[
                    ("TASK_CRED", 2304), ("TASK_NAMEIDATA", 2336), ("CRED_UID", 8),
                    ("CRED_EUID", 24), ("CRED_FSUID", 32), ("CRED_CAP_EFFECTIVE", 64),
                    ("CRED_SECURITY", 128), ("FILE_F_PATH", 64), ("FILE_F_INODE", 40),
                    ("FILE_F_MODE", 12), ("INODE_I_SECURITY", 56), ("DENTRY_D_INODE", 48),
                    ("ND_PATH", 0), ("ND_FLAGS", 56),
                ],
            };
            for ((structure, field, got), (label, want)) in
                layout.offsets.iter().zip(expect.iter())
            {
                assert_eq!(got, want, "{dir} {label} ({structure}.{field})");
            }
            
            let _ = &btf;
        }
    }

    #[test]
    fn rejects_non_btf_input() {
        let blank = vec![0u8; 4096];
        assert_eq!(locate(&blank), Err(BtfError::NotFound));
    }

    #[test]
    fn reports_missing_struct_and_field() {
        let Some(kernel) = sample("888332748bd958be") else { return };
        let (start, end) = locate(&kernel).unwrap();
        let btf = Btf::parse(&kernel, start, end).unwrap();
        assert!(matches!(
            btf.field("task_struct", "no_such_member_at_all"),
            Err(BtfError::NoSuchField(_, _))
        ));
        assert!(matches!(
            btf.field("no_such_struct_at_all", "x"),
            Err(BtfError::NoSuchStruct(_))
        ));
    }
}
