pub const BOOTSTRAP_BASE:usize=0x108daa0;
pub const AUTH_OFFSET:usize=240;
pub const BOOT_HOOK:(usize,u32,usize)=(0x1ce1844,0x978cce3d,272);
pub const CORE_PROFILE:u32=0x434e0001;
pub const CORE_BYTES:usize=65536;
pub const CORE_SHA256:[u8;32]=[79,38,193,143,246,189,18,150,182,157,51,52,31,124,181,81,2,239,194,33,196,221,221,69,135,156,85,77,185,5,44,220];
pub const HOOKS:&[(usize,u32,usize,bool)]=&[
 (0x6368a4,0xd10543ff,792,false),
 (0x63da08,0x97ffe6d7,836,true),
 (0x63d698,0xd503233f,876,false),
 (0x62f03c,0xd503233f,920,false),
 (0x63bda4,0xd10143ff,964,false),
 (0x637568,0xd101c3ff,1008,false),
 (0x6373b4,0xa9bb7bfd,1052,false),
 (0xcdbc0,0xa9ba7bfd,0,false),
 (0x3dab80,0xd503233f,1140,false),
 (0x3def58,0xd101c3ff,1184,false),
 (0x3d1910,0xd10283ff,1228,false),
 (0x3d0aa8,0xd101c3ff,1272,false),
 (0x630720,0xd503233f,1316,false),
 (0x631920,0xd503233f,1360,false),
 (0x3eeff8,0xd503233f,1404,false),
 (0x63c164,0xd503233f,1448,false),
 (0x3e2324,0xd503233f,1492,false),
 (0x63df30,0x97ffe58d,1536,true),
 (0x6425dc,0x97ffd3e2,1576,true),
];
