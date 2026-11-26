#!/usr/bin/env python3
# Regenerates the assembled payload from its sources.
# SPDX-License-Identifier: GPL-3.0-only
"""Rebuild/check zero-key embedded payload from source. Not needed to run the Rust patcher."""
from pathlib import Path
import argparse, hashlib, json, re, subprocess

s=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--write', action='store_true', help='replace embedded payload/symbols after building')
a=parser.parse_args()
b=a.output.resolve()
b.mkdir(parents=True,exist_ok=True)
base=0x108daa0
capacity=0x2560
objects=[]
sources=[]
for name in ['payload','root_domain','path_adapter','path_vfs','path_open','path_enum','access_adapter','access_hooks','root_tty']:
    src=s/(name+'.S')
    obj=b/(name+'.o')
    subprocess.run(['aarch64-linux-gnu-as','-o',str(obj),str(src)],check=True)
    sources.append(src)
    objects.append(obj)
flags=['-Os','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-asynchronous-unwind-tables','-fno-unwind-tables','-mgeneral-regs-only','-mno-outline-atomics','-fno-pic','-ffunction-sections','-fdata-sections','-Wall','-Wextra','-Werror','-Wno-unused-function']
for name in ['path_backend','path_enum','access_backend','access_vfs']:
    src=s/(name+'.c')
    obj=b/(name+'_c.o')
    subprocess.run(['aarch64-linux-gnu-gcc',*flags,'-c',str(src),'-o',str(obj)],check=True)
    sources.append(src)
    objects.append(obj)
linker=b/'payload.ld'
linker.write_text('SECTIONS { . = 0x108daa0; .text : { *(.text .text.*) *(.rodata .rodata.*) } /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) } }\n')
elf=b/'payload.elf'
subprocess.run(['aarch64-linux-gnu-ld','-T',str(linker),'-o',str(elf),*map(str,objects)],check=True)
rawpath=b/'payload.raw.bin'
subprocess.run(['aarch64-linux-gnu-objcopy','-O','binary','--only-section=.text',str(elf),str(rawpath)],check=True)
nm=subprocess.check_output(['aarch64-linux-gnu-nm',str(elf)],text=True)
assert not any(len(p:=line.split())==2 and p[0]=='U' for line in nm.splitlines()),'unresolved external symbol'
sy={p[2]:int(p[0],16)-base for line in nm.splitlines() if len(p:=line.split())==3 and int(p[0],16)>=base}
raw=rawpath.read_bytes()
assert len(raw)<=capacity,('payload capacity exceeded',len(raw),capacity)
assert raw[sy['cairn_hash']:sy['cairn_hash']+16]==bytes(16),'authorization key must remain zero'
symbols='&[\n'+''.join('    ("%s", %d),\n'%(name,offset) for name,offset in sorted(sy.items()))+']\n'

sections=subprocess.check_output(['aarch64-linux-gnu-objdump','-h',str(elf)],text=True)
for name,size in re.findall(r'^\s*\d+\s+(\.bss|\.data)\s+([0-9a-f]+)',sections,re.M):
    assert int(size,16)==0,('unexpected writable payload section',name,size)
(b/'disassembly.txt').write_text(subprocess.check_output(['aarch64-linux-gnu-objdump','-d',str(elf)],text=True))
if a.write:
    (s/'embedded_payload.bin').write_bytes(raw)
    (s/'embedded_symbols.rs').write_text(symbols)
else:
    assert (s/'embedded_payload.bin').read_bytes()==raw,'embedded payload is stale; review then use --write'
    assert (s/'embedded_symbols.rs').read_text()==symbols,'embedded symbols are stale; review then use --write'
sources.extend([s/'access_rules.h',s/'access_runtime.h',s/'path_runtime.h',s.parent/'visibility/path_rules.h'])
manifest={'payload_bytes':len(raw),'capacity':capacity,'zero_key':True,'embedded_matches_source':True,'payload_sha256':hashlib.sha256(raw).hexdigest(),'source_sha256':{str(path.relative_to(s.parent)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sources}}
(b/'source-validation.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('EMBEDDED_SOURCE_CHECK_PASS bytes=%d capacity=%d zero_key=1 symbols=%d'%(len(raw),capacity,len(sy)))
