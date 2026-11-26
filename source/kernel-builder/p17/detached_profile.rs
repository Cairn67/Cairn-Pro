// Example detached profile used as a generation sample.
pub const BOOTSTRAP_BASE:usize=0x10adaa0;
pub const AUTH_OFFSET:usize=240;
pub const BOOT_HOOK:(usize,u32,usize)=(0x1d21a50,0x978bcdba,272);
pub const CORE_PROFILE:u32=0x434e0001;
pub const CORE_BYTES:usize=65536;
pub const CORE_SHA256:[u8;32]=[174,169,6,113,129,43,221,242,183,189,152,12,91,162,122,17,18,147,165,4,114,49,121,191,187,54,22,150,253,35,225,179];
pub const HOOKS:&[(usize,u32,usize,bool)]=&[
 (0x641788,0xd10543ff,792,false),
 (0x648ae4,0x97ffe6cd,836,true),
 (0x648774,0xd503233f,876,false),
 (0x639f34,0xd503233f,920,false),
 (0x646e80,0xd10143ff,964,false),
 (0x64261c,0xd101c3ff,1008,false),
 (0x642468,0xa9bb7bfd,1052,false),
 (0xcf1b0,0xa9ba7bfd,0,false),
 (0x3e1744,0xd503233f,1140,false),
 (0x3e5b50,0xd101c3ff,1184,false),
 (0x3d845c,0xd10283ff,1228,false),
 (0x3d75f4,0xd101c3ff,1272,false),
 (0x63b618,0xd503233f,1316,false),
 (0x63c818,0xd503233f,1360,false),
 (0x3f5c40,0xd503233f,1404,false),
 (0x647240,0xd503233f,1448,false),
 (0x3e8f1c,0xd503233f,1492,false),
 (0x64900c,0x97ffe583,1536,true),
 (0x64d6b8,0x97ffd3d8,1576,true),
];
