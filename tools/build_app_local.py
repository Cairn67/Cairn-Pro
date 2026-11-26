from pathlib import Path
import subprocess,zipfile,shutil,xml.etree.ElementTree as ET,re,json,argparse,hashlib
ROOT=Path(__file__).resolve().parent.parent
A=ROOT/'source/app'
p=argparse.ArgumentParser(description='Build Cairn from local sources/dependencies; no downloads or signing')
p.add_argument('--output',type=Path,required=True)
p.add_argument('--shell-apk',type=Path)
p.add_argument('--shell-so',type=Path)
opts=p.parse_args()
if not opts.shell_apk and not opts.shell_so:
 raise SystemExit('Provide --shell-apk or --shell-so')
B=opts.output.resolve();B.mkdir(parents=True,exist_ok=True)
core=ROOT/'source/kernel-builder/detached_core.cld'
assert core.stat().st_size==65584,'Matched detached core is missing'
print('Bundled zero-key core SHA256='+hashlib.sha256(core.read_bytes()).hexdigest(),flush=True)
D=Path('/opt/cairn-build/deps');ANDROID=Path('/opt/cairn-sdk/android-35-ext15/android.jar')
def cmd(args):
 print('RUN',str(args[0]),flush=True)
 r=subprocess.run(list(map(str,args)))
 if r.returncode:raise RuntimeError('Command failed '+str(args[:3]))
def version(v):return tuple(int(x) if x.isdigit() else x for x in re.split('[.\\-]',v))
records=json.loads((D/'download-record.json').read_text());chosen={}
for g,a,v,e in records:
 if a not in chosen or version(v)>version(chosen[a][2]):chosen[a]=(g,a,v,e)
# JVM implementations replace metadata-only and older Java aliases.
for alias,impl in [('collection','collection-jvm'),('annotation','annotation-jvm'),('kotlinx-coroutines-core','kotlinx-coroutines-core-jvm')]:
 if impl in chosen:chosen.pop(alias,None)
# activity >=1.9 merged the legacy activity-ktx classes.
chosen.pop('activity-ktx',None)
chosen.pop('lifecycle-common-java8',None)
if 'lifecycle-common-jvm' in chosen:chosen.pop('lifecycle-common',None)
for name in list(chosen):
 if name+'-android' in chosen:chosen.pop(name,None)
libs=[];aars=[]
for g,a,v,e in chosen.values():
 if any(s in a for s in ['compiler','build-tools-api','daemon','script-runtime','kotlin-reflect']):continue
 p=D/(a+'-'+v+'.jar')
 if p.exists():libs.append(p)
 if e=='aar':aars.append(D/(a+'-'+v+'.aar'))
cp=':'.join(map(str,[ANDROID]+libs))
ui=B/'cairn-compose-ui.jar'
cmd(['java','-Xmx2g','-cp',str(D/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xplugin='+str(D/'kotlin-compose-compiler-plugin-embeddable-2.4.20.jar'),'-classpath',cp,'-d',ui]+list((A/'src/main/kotlin').rglob('*.kt')))
R=B/'resources';R.mkdir(exist_ok=True);archives=[];packages=[]
for p in aars:
 with zipfile.ZipFile(p) as z:
  if 'AndroidManifest.xml' not in z.namelist():continue
  pkg=ET.fromstring(z.read('AndroidManifest.xml')).get('package')
  if pkg:packages.append(pkg)
  dest=R/p.stem;dest.mkdir(exist_ok=True)
  for n in z.namelist():
   if n.startswith('res/') and not n.endswith('/'):
    q=dest/n;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(z.read(n))
  if (dest/'res').exists():
   out=dest/'compiled.zip';cmd(['aapt2','compile','--dir',dest/'res','-o',out]);archives.append(out)
appres=R/'app.zip';cmd(['aapt2','compile','--dir',A/'res','-o',appres]);archives.append(appres)
gen=B/'gen';gen.mkdir(exist_ok=True)
args=['aapt2','link','-I','/opt/cairn-sdk/android-33-ext5/android.jar','--manifest',A/'AndroidManifest.xml','--java',gen,'--extra-packages',':'.join(sorted(set(packages))),'--auto-add-overlay','-o',B/'resources.apk']
for p in archives:args+=['-R',p]
cmd(args)
classes=B/'classes';shutil.rmtree(classes,ignore_errors=True);classes.mkdir()
cmd(['javac','--release','17','-classpath',cp+':'+str(ui),'-d',classes]+list((A/'src').rglob('*.java'))+list(gen.rglob('*.java')))
merged=B/'program.jar';seen={}
with zipfile.ZipFile(merged,'w',zipfile.ZIP_DEFLATED) as out:
 for p in libs+[ui]:
  with zipfile.ZipFile(p) as z:
   for n in z.namelist():
    if not n.endswith('.class') or n.endswith('module-info.class'):continue
    b=z.read(n)
    if n in seen:
     if seen[n]!=b:raise RuntimeError('Conflicting class '+n+' in '+str(p))
     continue
    seen[n]=b;out.writestr(n,b)
 for p in classes.rglob('*.class'):out.write(p,p.relative_to(classes).as_posix())
dex=B/'dex';dex.mkdir(exist_ok=True)
cmd(['java','-Xmx2g','-cp','/opt/cairn-build/r8.jar','com.android.tools.r8.D8','--min-api','26','--lib',ANDROID,'--output',dex,merged])
native=B/'native';native.mkdir(exist_ok=True)
cmd(['aarch64-linux-gnu-gcc','-O2','-shared','-fPIC','-nostdlib','-fno-builtin','-fno-stack-protector','-Wl,--build-id=none','-o',native/'libcairn.so','-Wl,-z,noexecstack','-Wa,-I'+str(core.parent),A/'jni/cairn.c',A/'jni/jni_bridge.c',A/'jni/core_blob.S'])
daemon=A.parent/'daemon'
cmd(['aarch64-linux-gnu-gcc','-O2','-static','-nostdlib','-fno-builtin','-fno-stack-protector','-fno-tree-loop-distribute-patterns','-Wl,-e,_start','-Wl,--build-id=none','-o',native/'libcairnd.so',daemon/'cairn_entry.c',daemon/'cairnd.c'])
# External baseline supplies only libcairnsh.so; its source is not in this bundle.
# Java, Kotlin, JNI and cnd are compiled here; this output is unsigned.
if opts.shell_so:
 so=opts.shell_so.resolve()
 raw=so.read_bytes()
 if raw[:4] != b'\x7fELF' or raw[4] != 2 or raw[5] != 1:
  raise SystemExit('shell-so must be a 64-bit little-endian ELF')
 (native/'libcairnsh.so').write_bytes(raw)
else:
 with zipfile.ZipFile(opts.shell_apk.resolve()) as z:
  (native/'libcairnsh.so').write_bytes(z.read('lib/arm64-v8a/libcairnsh.so'))
unsigned=B/'cairn-shell-path-unsigned.apk';shutil.copyfile(B/'resources.apk',unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
 for p in native.glob('*.so'):z.write(p,'lib/arm64-v8a/'+p.name)
 for p in (A/'assets').rglob('*'):
  if p.is_file():z.write(p,'assets/'+p.relative_to(A/'assets').as_posix())
cmd(['zipalign','-f','4',unsigned,B/'cairn-shell-path-aligned.apk'])
print('BUILD SUCCESS unsigned APK',B/'cairn-shell-path-aligned.apk',flush=True)
