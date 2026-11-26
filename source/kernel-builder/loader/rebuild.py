#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Regenerate detached artifacts in isolation; check or replace them only explicitly."""
from pathlib import Path
import argparse,hashlib,json,re,struct,subprocess,sys,importlib.util
P=Path(__file__).resolve().parent;S=P.parent

sys.path.insert(0,str(next(q for q in P.parents if (q/'adapt'/'replay_encoding.py').is_file())/'adapt'))
import replay_encoding
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--core-size',type=int,default=65536)
p.add_argument('--layout',type=Path,help='JSON {CA_*_NAME: offset} retargeting the payload for another kernel. Keys are the names in layout.h.')
p.add_argument('--symbols',type=Path,help='JSON {symbol: address} giving the kernel functions the payload calls. Keys are the KERNEL_TARGETS names; anything omitted keeps its reference address.')
p.add_argument('--retarget',type=Path,metavar='KERNEL',help='Resolve every KERNEL_TARGETS entry from this uncompressed ARM64 Image using the validated generic kallsyms layout scanner. Fails if any symbol cannot be resolved.')
p.add_argument('--sites',type=Path,help='JSON site manifest from the dynamic locator: {sites:[[offset,word,name,linked],...]} with the boot site last. Only valid with --retarget; replaces the static hook table so every gateway fallback replays the target kernel instructions and continuation.')
p.add_argument('--fixup-plan',type=Path,help='JSON plan from adapt/resolve_target_fixups.py: fixups[].target and relatives[].target. Only valid with --retarget. Slot relatives are rewritten to the retargeted SLOT; any other unresolved relative refuses the build.')
p.add_argument('--replay-slots',action='store_true',help='Emit cl_replay_<hook> slots holding each target cover word and compile the payload to load them. Required for a cross-kernel retarget; a static build keeps its 6.6 replay constants so the real-device-validated artifact stays byte-identical.')
mode=p.add_mutually_exclusive_group();mode.add_argument('--check',action='store_true',help='Fail if rebuilt artifacts differ from the active embedded set');mode.add_argument('--write',action='store_true',help='Explicitly replace the four active detached artifacts after regeneration')
a=p.parse_args()
if a.retarget and a.write:p.error('--retarget artifacts are incomplete experiments and cannot be embedded with --write')
B=a.output.resolve();B.mkdir(parents=True,exist_ok=True)

LAYOUT_NAMES=['CA_TASK_CRED','CA_TASK_NAMEIDATA','CA_CRED_UID','CA_CRED_EUID','CA_CRED_FSUID','CA_CRED_CAP_EFF','CA_CRED_SECURITY','CA_FILE_F_PATH','CA_FILE_F_INODE','CA_FILE_F_MODE','CA_INODE_SECURITY','CA_DENTRY_INODE','CA_NAMEI_PATH','CA_NAMEI_FLAGS','CA_PATH_DENTRY','CA_AUDIT_U_FILE']
if a.layout:
 import re as _re
 text=(S/'layout.h').read_text()
 default={n:int(v) for n,v in _re.findall(r'^#define\s+('+'|'.join(LAYOUT_NAMES)+r')\s+(\d+)',text,_re.M)}
 assert len(default)==len(LAYOUT_NAMES),('layout.h must define all names',len(default))
 over={k:int(v) for k,v in json.loads(a.layout.read_text()).items()}
 unknown=sorted(set(over)-set(LAYOUT_NAMES))
 assert not unknown,('unknown layout names',unknown)
 merged={**default,**over}

 assert merged['CA_CRED_EUID']-merged['CA_CRED_UID']==16,('cred pair loads need euid-uid==16',merged['CA_CRED_UID'],merged['CA_CRED_EUID'])
 assert merged['CA_CRED_FSUID']-merged['CA_CRED_EUID']==8,('cred pair loads need fsuid-euid==8',merged['CA_CRED_EUID'],merged['CA_CRED_FSUID'])
 assert merged['CA_CRED_UID']%4==0 and merged['CA_CRED_EUID']%4==0,('cred fields must stay u32 aligned',merged['CA_CRED_UID'],merged['CA_CRED_EUID'])
 header=(S/'layout.h').read_text()
 for n,v in merged.items():header=_re.sub(r'(^#define\s+'+n+r'\s+)\d+',lambda m:m[1]+str(v),header,flags=_re.M)
 (B/'layout.h').write_text(header)
 print('LAYOUT_OVERRIDES',len(over),'of',len(LAYOUT_NAMES),json.dumps({k:v for k,v in over.items() if v!=default[k]}))

KERNEL_TARGETS=[
 ('cl_allocate',('module_alloc',),0,0x2fa64),
 ('cl_free',('module_memfree',),0,0x185d34),
 ('cl_ro',('set_memory_ro',),0,0x3d128),
 ('cl_x',('set_memory_x',),0,0x3d3b4),
 ('cl_nx',('set_memory_nx',),0,0x3d38c),
 ('cl_cache',('caches_clean_inval_pou',),0,0x38cdc),
 ('cl_native_copy',('__arch_copy_from_user',),0,0x101ebc0),
 ('cl_wait_initramfs',('wait_for_initramfs',),0,0x15138),
 ('cl_file_open',('filp_open',),0,0x3cfee4),
 ('cl_file_close',('filp_close',),0,0x3d013c),
 ('cl_file_read',('kernel_read',),0,0x3d1858),
 ('cl_buffer_alloc',('__kmalloc','__kmalloc_noprof'),0,0x3218ec),
 ('cl_buffer_free',('kfree',),0,0x321c2c),
 ('cl_print',('_printk',),0,0x104da58)]
BASE=0x108daa0;LIMIT=0x2560;SLOT=0x22f6a00;STATE=SLOT+24
def retarget_kernel_bytes(path):
 data=path.read_bytes()
 if data[:8]==b'ANDROID!':
  if len(data)<4096:raise SystemExit('RETARGET_INPUT_INVALID truncated Android boot header')
  _version=struct.unpack_from('<I',data,40)[0]
  if _version!=4:raise SystemExit('RETARGET_INPUT_UNSUPPORTED boot header version %d; expected v4'%_version)
  _header=struct.unpack_from('<I',data,20)[0]
  if _header!=1584:raise SystemExit('RETARGET_INPUT_UNSUPPORTED boot v4 header size %d'%_header)
  _size=struct.unpack_from('<I',data,8)[0]
  if not _size or _size>len(data)-4096:raise SystemExit('RETARGET_INPUT_INVALID truncated boot kernel section')
  data=data[4096:4096+_size]
 if len(data)<64 or data[56:60]!=b'ARM\x64':
  raise SystemExit('RETARGET_INPUT_UNSUPPORTED expected uncompressed ARM64 Image or Android boot v4 containing one')
 return data

def resolve_targets(path,targets):
 _root=next(p for p in P.parents if (p/'adapt'/'discover_symbols.py').is_file())
 sys.path.insert(0,str(_root/'adapt'))
 _spec=importlib.util.spec_from_file_location('_cairn_discover_symbols',str(_root/'adapt'/'discover_symbols.py'))
 _ds=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(_ds)
 _data=retarget_kernel_bytes(path)
 try:_meta,_rows=_ds.discover(_data,seconds=600)
 except (ValueError,RuntimeError) as _e:raise SystemExit('RETARGET_KALLSYMS_FAILED '+str(_e))
 _syms={_n[1:]:_addr for _addr,_n in _rows}
 _resolved=[]
 for _name,_aliases,_offset,_reference in targets:
  for _alias in _aliases:
   if _alias in _syms:
    _resolved.append((_name,_aliases,_offset,_syms[_alias]+_offset));break
  else:raise SystemExit('RETARGET_UNRESOLVED %s tried=%s'%(_name,','.join(_aliases)))
 from placement import derive,PlacementError
 try:_placement=derive(_data,_rows,LIMIT)
 except PlacementError as _e:raise SystemExit('RETARGET_PLACEMENT_FAILED '+str(_e))
 return _resolved,_meta,_placement
if a.retarget:
 
 KERNEL_TARGETS,_retarget_meta,_placement=resolve_targets(a.retarget,KERNEL_TARGETS)
 BASE=_placement['base'];SLOT=_placement['slot'];STATE=_placement['state']
 print('RETARGET_PLACEMENT',json.dumps(_placement,sort_keys=True))
 print('RETARGET_SYMBOLS',len(KERNEL_TARGETS),'from',a.retarget.name,'family',_retarget_meta['family'])

KERNEL_TARGET_NAMES=[n for n,_,_,_ in KERNEL_TARGETS]
if a.symbols:
 
 _over={k:int(v) for k,v in json.loads(a.symbols.read_text()).items()}
 _unknown=sorted(set(_over)-set(KERNEL_TARGET_NAMES))
 assert not _unknown,('unknown symbol names',_unknown)
 KERNEL_TARGETS=[(n,s,o,_over.get(n,v)) for n,s,o,v in KERNEL_TARGETS]
 print('SYMBOL_OVERRIDES',len(_over),'of',len(KERNEL_TARGET_NAMES),json.dumps({k:hex(v) for k,v in sorted(_over.items())}))

assert not subprocess.run([sys.executable,S/'check_offsets.py'],capture_output=True).returncode,'naked struct offset in the payload sources; run check_offsets.py for the list'
assert a.core_size%4096==0 and 16384<=a.core_size<=1024*1024
def _prctl_local_frame(kernel_path):
 """Locals size of __arm64_sys_prctl, read from its own prologue.

 A rule, not an address: skip the BTI/PACIASP, walk the callee-saved stores, and
 take the first plain `sub sp,sp,#imm`. Return None when the shape does not match
 so the caller refuses instead of carrying a wrong frame size.
 """
 data=kernel_path.read_bytes()
 start=None
 for line in SYMS.read_text().splitlines():
  p=line.split()
  if len(p)>=3 and p[2]=='__arm64_sys_prctl':
   start=int(p[0],16);break
 if start is None:return None
 for i in range(0,64):
  w=struct.unpack_from('<I',data,start+i*4)[0]
  if w in (0xd503233f,0xd503201f,0xd503245f,0xd503249f):continue
  if (w&0xff80001f)==0xd100001f and ((w>>5)&0x1f)==31 and (w&0x1f)==31:
   
   if not (w>>30)&1:return None
   imm12=(w>>10)&0xfff
   if not imm12:return None
   return (imm12<<12) if (w>>22)&1 else imm12
  if (w&0xffc003e0) in (0xa98003e0,0xa90003e0):
   continue
 return None

SYMS=Path('/data/user/0/com.ai.assistance.operit/files/workspace/b11c730b-d984-4280-bec2-41256e7dfdc0/Cairn-1.6.0/research/three-device-dynamic/1ce5a4f8b813ed21/symbols.txt')
profile=(S/'src/profile.rs').read_text()
hooks=[(int(x,16),int(y,16),n,z=='true') for x,y,n,z in re.findall(r'\(0x([0-9a-f]+),0x([0-9a-f]+),"([^"]+)",(true|false)\)',profile)]
fixups=[(n,int(x,16),z=='true') for n,x,z in re.findall(r'\("([^"]+)",0x([0-9a-f]+),(true|false)\)',profile)]
rels=[(n,int(x,16)) for n,x in re.findall(r'\("([^"]+)",0x([0-9a-f]+)\)',profile) if n.endswith('delta')]

boot_site=None

SLOT_OFFSETS={'cairn_wl_slot_delta':0,'cp_slot_delta':8,'ca_slot_delta':16}
rels=[(n,SLOT+SLOT_OFFSETS[n] if t is None and n in SLOT_OFFSETS else t) for n,t in rels]
if a.sites:
 if not a.retarget:p.error('--sites requires --retarget; refusing to apply a dynamic table to a static profile build')
 man=json.loads(a.sites.read_text())['sites']
 if len(man)!=len(hooks)+1:raise SystemExit('SITES_INVALID expected %d sites (hooks+boot), got %d'%(len(hooks)+1,len(man)))
 sites=[(int(o),int(w),str(n),bool(l)) for o,w,n,l in man]
 
 if [s[2] for s in sites[:-1]]!=[h[2] for h in hooks]:raise SystemExit('SITES_INVALID hook names/order differ from the reference profile')
 for o,w,n,l in sites[:-1]:
  if l!=[h for h in hooks if h[2]==n][0][3]:raise SystemExit('SITES_INVALID %s linking flag differs from the reference profile'%n)
 hooks=[(o,w,n,l) for o,w,n,l in sites[:-1]]
 boot_site=(sites[-1][0],sites[-1][1])
 print('SITES_APPLIED',len(hooks),'boot',hex(boot_site[0]),'from',a.sites.name)
if a.fixup_plan:
 if not a.retarget:p.error('--fixup-plan requires --retarget')
 plan=json.loads(a.fixup_plan.read_text())
 by_name={}
 for e in plan.get('fixups',[]):
  if e.get('target') is None:raise SystemExit('FIXUP_UNRESOLVED %s'%e.get('name'))
  by_name[e['name']]=int(e['target'])
 if sorted(by_name)!=sorted(n for n,_,_ in fixups):raise SystemExit('FIXUP_PLAN_INCOMPLETE have=%d need=%d'%(len(by_name),len(fixups)))
 fixups=[(n,by_name[n],l) for n,_,l in fixups]
 rel_by={e['name']:(None if e.get('target') is None else int(e['target'])) for e in plan.get('relatives',[])}
 resolved=[];deferred=[]
 for n,_ in rels:
  if n.endswith('_slot_delta'):deferred.append(n);continue
  t=rel_by.get(n)
  if t is None:raise SystemExit('RELATIVE_UNRESOLVED %s (no target in plan)'%n)
  resolved.append((n,t))
 rels=resolved+[(n,SLOT+SLOT_OFFSETS[n]) for n in deferred]  
 print('FIXUPS_APPLIED',len(fixups),'RELATIVES_APPLIED',len(resolved),'SLOT_DEFERRED',len(deferred))
flags=['-Os','-ffreestanding','-fno-builtin','-fno-stack-protector','-fno-asynchronous-unwind-tables','-fno-unwind-tables','-mgeneral-regs-only','-mno-outline-atomics','-fno-pic','-mbranch-protection=bti','-Wall','-Wextra','-Werror','-Wno-unused-function']

def run(cmd):
 z=subprocess.run(list(map(str,cmd)),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if z.returncode:raise RuntimeError(str(cmd)+'\n'+z.stderr.decode(errors='replace'))
native={}
def destination(target):
 name='cl_kaddr_'+format(target,'x');native[name]=target;return name
def link(name,objects,base):
 ld=B/(name+'.ld');assignments=''.join('%s = %s;\n'%(n,hex(t)) for n,t in native.items()) if name=='bootstrap' else ''
 ld.write_text(assignments+'SECTIONS { . = '+hex(base)+'; .text : { *(.text .text.*) *(.rodata .rodata.*) '+(' . = '+hex(a.core_size-4096)+'; *(.cl_tail)' if name=='core' else '')+' } /DISCARD/ : { *(.comment) *(.note*) *(.eh_frame*) } }\n')
 elf=B/(name+'.elf');run(['aarch64-linux-gnu-ld','-T',ld,'-o',elf,*objects]);raw=B/(name+'.bin');run(['aarch64-linux-gnu-objcopy','-O','binary','--only-section=.text',elf,raw])
 nm=subprocess.check_output(['aarch64-linux-gnu-nm',str(elf)],text=True);assert not any(len(v:=l.split())==2 and v[0]=='U' for l in nm.splitlines())
 sy={v[2]:int(v[0],16)-base for l in nm.splitlines() if len(v:=l.split())==3 and int(v[0],16)>=base}
 sec=subprocess.check_output(['aarch64-linux-gnu-objdump','-h',str(elf)],text=True)
 for n,size in re.findall(r'^\s*\d+\s+(\.bss|\.data)\s+([0-9a-f]+)',sec,re.M):assert int(size,16)==0,(n,size)
 (B/(name+'-disassembly.txt')).write_text(subprocess.check_output(['aarch64-linux-gnu-objdump','-d',str(elf)],text=True));return raw.read_bytes(),sy

_replay_slots=0

if a.replay_slots:
 flags+=['-DCAIRN_REPLAY_SLOTS']
if a.replay_slots:
 if not a.retarget:
  raise SystemExit('--replay-slots requires --retarget: a static build keeps its 6.6 constants and artifacts byte-identical')
 for _i,(_addr,_word,_name,_linked) in enumerate(hooks):
  _sh=replay_encoding.decode_covered(_word)
  if _sh['shape']=='branch':
   
   print('REPLAY_BRANCH hook=%d %s %#x handled by the kernel bridge'%(_i,_name,_word))
   continue
  if _sh['shape']=='unknown':
   raise SystemExit('REPLAY_UNSUPPORTED hook=%d %s %#x'%(_i,_name,_word))
  if replay_encoding.replay(_word)!=_word:
   raise SystemExit('REPLAY_ENCODE_MISMATCH %s %#x'%(_name,_word))
  flags.append('-DCAIRN_REPLAY_%d=%#x'%(_i,_word))
  _replay_slots+=1
print('REPLAY_SLOTS %d'%_replay_slots)

if a.replay_slots:
 _prctl=_prctl_local_frame(a.retarget)
 if _prctl is None:
  raise SystemExit('PRCTL_LOCALS_UNRESOLVED %s'%a.retarget)
 flags.append('-DCAIRN_PRCTL_LOCALS=#%d'%_prctl)
 print('PRCTL_LOCALS %d (%#x)'%(_prctl,_prctl))
asm=['payload','root_domain','path_adapter','path_vfs','path_open','path_enum','access_adapter','access_hooks','access_avc','root_tty'];objs=[]
for n in asm:
 text=(S/(n+'.S')).read_text()
 if n=='payload':text=text.replace('    cbnz x23, cairn_prctl_bad','    cmp x23,#10\n    b.eq cl_far_probe\n    cbnz x23, cairn_prctl_bad')
 text=('.global cairn_prctl_out\n' if n=='payload' else '')+text
 text='\n'.join('.global '+name for _,_,name,_ in hooks if name+':' in text)+'\n'+text
 for i,(label,_,_) in enumerate(fixups):
  text=re.sub(r'('+re.escape(label)+r':\s*\n\s*)(b|bl)\s+0\b',lambda m:m[1]+m[2]+' cl_core_bridge_'+str(i),text)

 q=B/(n+'.S');q.write_text(text+'\n');i=B/(n+'.i')
 
 run(['aarch64-linux-gnu-gcc','-E','-x','assembler-with-cpp',*flags,'-I',B,'-I',S,'-o',i,q])
 o=B/(n+'.o');run(['aarch64-linux-gnu-as','-o',o,i]);objs.append(o)
entry='\n.section .text\n.balign 8\n'

entry+='\n.section .cl_tail,\"ax\"\n.global cl_far_probe\ncl_far_probe:\n mov w0,#0x4f4b\n movk w0,#0x434c,lsl #16\n b cairn_prctl_out\n.section .text\n'

for i,(_,_,n,_) in enumerate(hooks):entry+='cl_core_entry_%d:\n bti jc\n b %s\n'%(i,n)
for i in range(len(fixups)):entry+='\n.balign 8\n.global cl_core_bridge_%d\ncl_core_bridge_%d:\n bti jc\n ldr x16,cl_core_target_%d\n br x16\n.balign 8\ncl_core_target_%d:\n .quad 0\n'%(i,i,i,i)
q=B/'core-bridges.S';q.write_text(entry);o=B/'core-bridges.o';run(['aarch64-linux-gnu-as','-o',o,q]);objs.append(o)
for n in ['path_backend','path_enum','access_backend','access_vfs','access_avc']:

 o=B/(n+'_c.o');run(['aarch64-linux-gnu-gcc',*flags,'-I',B,'-I',S,'-c',S/(n+'.c'),'-o',o]);objs.append(o)
core,cs=link('core',objs,0);core_actual=len(core);assert len(core)<=a.core_size;core+=bytes(a.core_size-len(core));assert core[cs['cairn_hash']:cs['cairn_hash']+16]==bytes(16)
digest=hashlib.sha256(core).digest();(B/'core.bin').write_bytes(core);(B/'core.cld').write_bytes(struct.pack('<IIII',0x434c4431,1,0x434e0001,len(core))+digest+core)

gw='.section .text\n'
for i,(_,target,_) in enumerate(fixups):gw+='\n.balign 4\ncl_kernel_bridge_%d:\n bti jc\n b %s\n'%(i,destination(target))
for i,(addr,word,n,linking) in enumerate(hooks):
 name='cl_gateway_prctl' if n=='cairn_prctl' else 'cl_gate_'+str(i)
 gw+='\n.balign 4\n.global %s\n%s:\n adr x16,cl_state_delta\n ldr x17,[x16]\n add x16,x16,x17\n add x16,x16,#8\n ldar x16,[x16]\n cbz x16,cl_fallback_%d\n mov x17,#%d\n add x16,x16,x17\n br x16\ncl_fallback_%d:\n'%(name,name,i,cs['cl_core_entry_'+str(i)],i)
 if linking:
  delta=(word&0x3ffffff)<<2
  if delta&(1<<27):delta-=1<<28
  gw+=' b '+destination(addr+delta)+'\n'
 else:gw+=' .inst '+hex(word)+'\n b '+destination(addr+4)+'\n'
for name,_symbols,_offset,target in KERNEL_TARGETS:
 gw+='\n.global '+name+'\n'+name+':\n b '+destination(target)+'\n'
gw+='\n.balign 8\n.global cl_state_delta\ncl_state_delta:\n .quad 0\n.global cl_request_bytes\ncl_request_bytes:\n .word '+str(len(core)+48)+'\n'
q=B/'gateways.S';q.write_text(gw);go=B/'gateways.o';run(['aarch64-linux-gnu-as','-o',go,q])
def generate(bs):
 text='/* Generated build-specific relocations; no authorization key. */\n#define CL_CORE_BYTES %dU\n#define CL_CORE_PAGES %d\n#define CL_KEY_OFFSET %d\n#define CL_BRIDGES %d\n#define CL_RELATIVES %d\n'%(len(core),len(core)//4096,cs['cairn_hash'],len(fixups),len(rels))
 text+='static const unsigned char cl_digest[32]={'+','.join(map(str,digest))+'};\n'
 text+='static const struct cl_fixup cl_fixups[]={'+','.join('{%d,%d}'%(cs['cl_core_target_'+str(i)],BASE+bs.get('cl_kernel_bridge_'+str(i),0)) for i in range(len(fixups)))+'};\n'
 text+='static const struct cl_relative cl_relatives[]={'+','.join('{%d,%d}'%(cs[n],target) for n,target in rels)+'};\n'
 (B/'generated.h').write_text(text)
run(['aarch64-linux-gnu-as','-o',B/'gateway.o',P/'gateway.S'])
run(['aarch64-linux-gnu-as','-o',B/'boot.o',P/'boot.S'])

for name in ['loader.c','format.h','sha256.h']:(B/name).write_bytes((P/name).read_bytes())
bs={}
for _ in range(3):
 generate(bs);run(['aarch64-linux-gnu-gcc',*flags,'-c',B/'loader.c','-o',B/'loader.o']);boot,ns=link('bootstrap',[B/'gateway.o',B/'boot.o',go,B/'loader.o'],BASE)
 if ns==bs:break
 bs=ns
else:raise AssertionError('bootstrap layout did not converge')
assert len(boot)<=LIMIT,(len(boot),LIMIT)
boot=bytearray(boot);struct.pack_into('<q',boot,bs['cl_state_delta'],STATE-(BASE+bs['cl_state_delta']));struct.pack_into('<Q',boot,bs['cl_kernel_delta'],BASE+bs['cl_anchor']);(B/'bootstrap.bin').write_bytes(boot)

BOOT_SITE=boot_site or (0x1ce1844,0x978cce3d)
report={'bootstrap_base':BASE,'slot':SLOT,'placement':_placement if a.retarget else None,'incomplete_retarget':bool(a.retarget),'prototype':True,'production_updated':False,'core_bytes':len(core),'core_actual_code_bytes':core_actual,'bootstrap_bytes':len(boot),'bootstrap_capacity':LIMIT,'core_sha256':digest.hex(),'core_symbols':cs,'bootstrap_symbols':bs,'hooks':hooks,'state_offset':STATE,'runtime_bytes':56,'relatives':rels,'fixups':fixups,'source_files':{str(v.relative_to(S)):hashlib.sha256(v.read_bytes()).hexdigest() for v in P.glob('*') if v.is_file() and v.suffix in ['.S','.c','.h','.py']}}

metadata='pub const BOOTSTRAP_BASE:usize=%s;\npub const AUTH_OFFSET:usize=%d;\npub const BOOT_HOOK:(usize,u32,usize)=(0x%x,0x%x,%d);\npub const CORE_PROFILE:u32=0x434e0001;\npub const CORE_BYTES:usize=%d;\npub const CORE_SHA256:[u8;32]=[%s];\npub const HOOKS:&[(usize,u32,usize,bool)]=&[\n'%(hex(BASE),bs['cl_auth'],BOOT_SITE[0],BOOT_SITE[1],bs['cl_boot_entry'],len(core),','.join(map(str,digest)))
for i,(addr,word,label,linked) in enumerate(hooks):metadata+=' (%s,%s,%d,%s),\n'%(hex(addr),hex(word),bs['cl_prctl_entry' if label=='cairn_prctl' else 'cl_gate_'+str(i)],str(linked).lower())
metadata+='];\n'
(B/'detached_profile.rs').write_text(metadata)
(B/'detached_symbols.rs').write_text('&[\n'+''.join(' (%s,%d),\n'%(json.dumps(n),o) for n,o in sorted(cs.items()))+']\n')

layout_text=(B/'layout.h').read_text() if (B/'layout.h').is_file() else (S/'layout.h').read_text()
layout_vals={n:int(v) for n,v in re.findall(r'^#define\s+('+'|'.join(LAYOUT_NAMES)+r')\s+(\d+)',layout_text,re.M)}
assert len(layout_vals)==len(LAYOUT_NAMES),('layout.h incomplete',sorted(set(LAYOUT_NAMES)-set(layout_vals)))
LAYOUT_FIELDS=[('CA_TASK_CRED','task_struct','cred'),('CA_TASK_NAMEIDATA','task_struct','nameidata'),
 ('CA_CRED_UID','cred','uid'),('CA_CRED_EUID','cred','euid'),('CA_CRED_FSUID','cred','fsuid'),
 ('CA_CRED_CAP_EFF','cred','cap_effective'),('CA_CRED_SECURITY','cred','security'),
 ('CA_FILE_F_PATH','file','f_path'),('CA_FILE_F_INODE','file','f_inode'),('CA_FILE_F_MODE','file','f_mode'),
 ('CA_INODE_SECURITY','inode','i_security'),('CA_DENTRY_INODE','dentry','d_inode'),
 ('CA_NAMEI_PATH','nameidata','path'),('CA_NAMEI_FLAGS','nameidata','flags'),
 ('CA_PATH_DENTRY','path','dentry'),('CA_AUDIT_U_FILE','common_audit_data','u')]
assert sorted(n for n,_,_ in LAYOUT_FIELDS)==sorted(LAYOUT_NAMES),'LAYOUT_FIELDS must cover layout.h exactly'
(B/'detached_layout.rs').write_text('// Generated by loader/rebuild.py: the struct offsets this payload was\n'
 '// assembled against, as (struct, member, offset). Derived from layout.h so a\n'
 '// retargeted payload reports its own layout rather than the 6.6 reference.\n'
 'pub const PAYLOAD_LAYOUT:&[(&str,&str,u64)]=&[\n'
 +''.join(' (%s,%s,%d),\n'%(json.dumps(s),json.dumps(f),layout_vals[n]) for n,s,f in LAYOUT_FIELDS)+'];\n')
(B/'build.json').write_text(json.dumps(report,indent=2)+'\n');print('DETACHED_BUILD_PASS core=%d bootstrap=%d/%d gateways=%d'%(len(core),len(boot),LIMIT,len(hooks)))
artifacts={'bootstrap.bin':'detached_bootstrap.bin','core.cld':'detached_core.cld','detached_profile.rs':'detached_profile.rs','detached_symbols.rs':'detached_symbols.rs','detached_layout.rs':'detached_layout.rs'}
if a.check:
 mismatches=[dst for src,dst in artifacts.items() if not (S/dst).is_file() or (B/src).read_bytes()!=(S/dst).read_bytes()]
 if mismatches:raise SystemExit('DETACHED_REBUILD_MISMATCH '+', '.join(mismatches))
 print('DETACHED_REBUILD_MATCH artifacts=%d'%len(artifacts))
if a.write:
 for src,dst in artifacts.items():(S/dst).write_bytes((B/src).read_bytes())
 print('DETACHED_EMBEDDED_UPDATED artifacts=%d zero_key=1'%len(artifacts))

