#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Offline exact-Rust-artifact regression using local private VM fixtures only.

Requires a workspace containing the device policy snapshot, the stock boot, the
legacy integration fixture and the Android shell fixture. No downloads, phone
commands, real authorization keys, or partition writes are performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import time

P = Path(__file__).resolve().parent
S = P.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--workspace', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--negative-boot', choices=['missing', 'corrupt'])
p.add_argument('--access-v2', action='store_true')
a = p.parse_args()
R = a.workspace.resolve()
O = a.output.resolve()
O.mkdir(parents=True, exist_ok=True)
A = R / 'private_artifacts'
F = O / 'root'

def run(cmd, log, env=None):
    z = subprocess.run([str(v) for v in cmd], capture_output=True, env=env)
    (O / log).write_bytes(z.stdout + z.stderr)
    if z.returncode:
        raise RuntimeError(f'{cmd[0]} failed ({z.returncode}):\n' + (z.stdout + z.stderr).decode(errors='replace')[-5000:])
    return z.stdout.decode(errors='replace')

def align(n, page=4096):
    return (n + page - 1) & ~(page - 1)

run(['python3', S / 'loader/rebuild.py', '--output', O / 'regenerated', '--check'], 'source-rebuild-check.log')
run(['cargo', 'build', '--release', '--manifest-path', S / 'Cargo.toml', '--target-dir', O / 'cargo-target'], 'builder-compile.log')

key = O / 'synthetic-test.key'
key.write_text('11111111111111111111111111111111\n')
key.chmod(0o600)
env = os.environ.copy()
env['CAIRN_KEY_FILE'] = str(key)
run([O / 'cargo-target/release/cairn-kernel-builder', A / 'boot-stock.img', O], 'builder.log', env=env)
boot = (O / 'boot-V1.0.1.img').read_bytes()
ksize, rsize = struct.unpack_from('<II', boot, 8)
start = 4096 + align(ksize)
kernel = boot[4096:4096 + ksize]
ramdisk = boot[start:start + rsize]
assert kernel == (O / 'kernel-V1.0.1.bin').read_bytes()
assert ramdisk == (O / 'cairn-ramdisk.cpio').read_bytes()
assert (O / 'core.cld').read_bytes() == (S / 'detached_core.cld').read_bytes()
core_bytes = len((O / 'core.cld').read_bytes()) - 48

for d in ['bin', 'dev/pts', 'proc', 'sys', 'tmp', 'data/adb/cairn', 'system/bin', 'system/lib64',
          'apex/com.android.runtime/bin', 'apex/com.android.runtime/lib64/bionic', 'linkerconfig']:
    (F / d).mkdir(parents=True, exist_ok=True)
shutil.copyfile('/bin/busybox', F / 'bin/busybox')
(F / 'bin/busybox').chmod(0o755)
ref = A / 'su-jobcontrol/android-shell'
for name, dests in {
    'sh': ['system/bin/sh'],
    'linker64': ['system/bin/linker64', 'apex/com.android.runtime/bin/linker64'],
    'libc.so': ['system/lib64/libc.so', 'apex/com.android.runtime/lib64/bionic/libc.so'],
    'libdl.so': ['system/lib64/libdl.so', 'apex/com.android.runtime/lib64/bionic/libdl.so'],
    'libm.so': ['system/lib64/libm.so', 'apex/com.android.runtime/lib64/bionic/libm.so'],
}.items():
    for dest in dests:
        shutil.copyfile(ref / name, F / dest)
        (F / dest).chmod(0o755)
for dest in ['system/lib64/ld-android.so', 'apex/com.android.runtime/lib64/bionic/ld-android.so']:
    v = F / dest
    if not v.exists() and not v.is_symlink():
        v.symlink_to('/system/bin/linker64')
for applet in ['printf', 'id', 'cat', 'echo', 'stty', 'ls', 'readlink', 'sleep', 'kill']:
    v = F / 'system/bin' / applet
    if not v.exists() and not v.is_symlink():
        v.symlink_to('/bin/busybox')
(F / 'linkerconfig/ld.config.txt').write_text('dir.system = /system/bin\n[system]\nnamespace.default.isolated = false\nnamespace.default.search.paths = /system/lib64\n')
shutil.copyfile(A / 'selinux-live-policy.bin', F / 'policy.bin')
D = S.parent / 'daemon'
run(['aarch64-linux-gnu-gcc', '-O2', '-static', '-nostdlib', '-fno-builtin', '-fno-stack-protector',
     '-fno-tree-loop-distribute-patterns', '-Wl,-e,_start', '-Wl,--build-id=none', D / 'cairn_entry.c', D / 'cairnd.c', '-o', F / 'bin/cnd'], 'cnd-build.log')
shutil.copyfile(F / 'bin/cnd', F / 'data/adb/cnd')
(F / 'bin/cnd').chmod(0o755)
(F / 'data/adb/cnd').chmod(0o700)

source = (A / 'pipe_auto_integration.c').read_text()
source = source.replace('../Cairn Pro/', str(S.parents[1]) + '/')
source = source.replace('pipe-auto-fix/tests.h', str(A / 'pipe-auto-fix/tests.h'))
source = source.replace('#include <sys/prctl.h>', '#include <sys/prctl.h>\n#include <sys/syscall.h>')
source = source.replace('return prctl(0x43414952UL,KEY,KEY,which,(unsigned long)arg);', 'return syscall(SYS_prctl,0x43414952UL,KEY,KEY,which,(unsigned long)arg);')
ready = '''static void loader_test(void){
 ck(op(9,NULL)==CORE_BYTES,"rust-boot-core-ready-before-manager");
 ck(op(9,(void *)2)==1,"rust-boot-core-loaded-once");
 ck(op(3,NULL)==0x43010001,"rust-boot-release-1.0.1");
 ck(op(10,NULL)==0x434c4f4b,"rust-boot-executes-last-core-page");
 puts("DETACHED_LOADER_SMOKE_PASS rust_artifact=1 boot_auto=1 gateways=19");
}
'''.replace('CORE_BYTES', str(core_bytes))
source = source.replace('int main(int argc,char **argv){', '#include "mmap_vm_tests.h"\n' + ready + '\nint main(int argc,char **argv){')
source = source.replace('o=mmap(NULL,4096', 'loader_test();\n o=mmap(NULL,4096')
source = source.replace('visibility_coexistence();', 'visibility_coexistence();mmap_tests(&t);')
source = source.replace('IPC=0 mmap=0', 'IPC=0 mmap=1 mprotect=1 existing_vma_revoke=0')
if a.access_v2:
    source = source.replace('#include "mmap_vm_tests.h"', '#include "mmap_vm_tests.h"\n#include "access_v2_vm_tests.h"')
    source = source.replace('visibility_coexistence();mmap_tests(&t);', 'visibility_coexistence();mmap_tests(&t);access_v2_tests();')

if a.negative_boot:
    
    source = '''#define _GNU_SOURCE
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
int main(void){
 setbuf(stdout,NULL);errno=0;
 long n=syscall(SYS_prctl,0x43414952UL,0x1111111111111111UL,0x1111111111111111UL,9UL,0UL);
 if(n!=0||prctl(PR_GET_DUMPABLE)<0){puts("VM_FAIL negative-loader-fallback");return 81;}
 puts("DETACHED_LOADER_NEGATIVE_PASS native_fallback=1 unpublished=1");return 0;
}
'''
(O / 'integration.c').write_text(source)
run(['aarch64-linux-gnu-gcc', '-static', '-O2', '-Wall', '-Wextra', '-Werror', '-Wno-unused-function', '-I', P, O / 'integration.c', '-o', F / 'bin/integration'], 'integration-build.log')
(F / 'init').write_text('''#!/bin/busybox sh
/bin/busybox --install -s /bin
mount -t proc proc /proc
mount -t sysfs sysfs /sys
mount -t devtmpfs devtmpfs /dev
mkdir -p /dev/pts
mount -t devpts devpts /dev/pts
chmod 0755 /dev /dev/pts
ln -sf pts/ptmx /dev/ptmx
/bin/integration
echo ACCESS_RUNNER_EXIT=$?
poweroff -f
''')
(F / 'init').chmod(0o755)

if a.negative_boot == 'missing':
    ramdisk = b''
elif a.negative_boot == 'corrupt':
    ramdisk = bytearray(ramdisk)
    offset = ramdisk.index((S / 'detached_core.cld').read_bytes())
    ramdisk[offset + 48 + 128] ^= 1
    ramdisk = bytes(ramdisk)
with (O / 'rootfs.cpio').open('wb') as out:
    listing = subprocess.Popen(['find', '.', '-print0'], cwd=F, stdout=subprocess.PIPE)
    z = subprocess.run(['cpio', '--null', '-o', '--format=newc', '--owner=0:0'], cwd=F, stdin=listing.stdout, stdout=out, stderr=subprocess.PIPE)
    assert listing.wait() == 0 and z.returncode == 0, z.stderr
(O / 'Image').write_bytes(kernel)
(O / 'initramfs.cpio').write_bytes(ramdisk + (O / 'rootfs.cpio').read_bytes())
start_time = time.monotonic()
timed_out = False
with (O / 'serial.log').open('w') as out:
    try:
        z = subprocess.run(['qemu-system-aarch64', '-M', 'virt', '-cpu', 'max', '-smp', '2', '-m', '768', '-accel', 'tcg', '-nographic',
                            '-net', 'none', '-no-reboot', '-kernel', O / 'Image', '-initrd', O / 'initramfs.cpio', '-append',
                            'console=ttyAMA0 earlycon=pl011,0x9000000 rdinit=/init nokaslr panic=-1 enforcing=1'], stdout=out, stderr=subprocess.STDOUT, timeout=90)
        code = z.returncode
    except subprocess.TimeoutExpired:
        timed_out = True
        code = None
log = (O / 'serial.log').read_text(errors='replace')
markers = ['DETACHED_LOADER_NEGATIVE_PASS'] if a.negative_boot else ['DETACHED_LOADER_SMOKE_PASS', 'MMAP_AUTHORIZATION_VM_PASS', 'ACCESS_KERNEL_VM_PASS']
if a.access_v2 and not a.negative_boot:
    markers += ['ACCESS_V2_KERNEL_VM_PASS', 'SCOPED_PTY_VM_PASS', 'SCOPED_SOCKET_VM_PASS']
expected_boot_error = {'missing': -2, 'corrupt': -74}.get(a.negative_boot)
if expected_boot_error is not None:
    markers.append(f'CairnLoader boot result={expected_boot_error} core_bytes=0')
passed = not timed_out and code == 0 and 'ACCESS_RUNNER_EXIT=0' in log and all(v in log for v in markers) and not any(v in log for v in ['VM_FAIL', 'Kernel panic', 'Oops:'])
report = {'passed': passed, 'timeout': timed_out, 'exit': code, 'seconds': round(time.monotonic() - start_time, 2),
          'phone_tested': False, 'production_key_used': False, 'avb_resigned': False, 'exact_rust_boot_kernel_tested': True,
          'access_v2_tested': bool(a.access_v2 and not a.negative_boot), 'negative_boot': a.negative_boot, 'expected_boot_error': expected_boot_error, 'selinux_policy_loaded_in_vm': not bool(a.negative_boot), 'core_bytes': core_bytes, 'bootstrap_bytes': (S / 'detached_bootstrap.bin').stat().st_size,
          'policy_sha256': hashlib.sha256((A / 'selinux-live-policy.bin').read_bytes()).hexdigest(),
          'sha256': {name: hashlib.sha256((O / name).read_bytes()).hexdigest() for name in ['kernel-V1.0.1.bin', 'boot-V1.0.1.img', 'core.cld', 'cairn-ramdisk.cpio', 'initramfs.cpio']}}
for marker, field in [('ACCESS_KERNEL_VM_PASS ', 'checks_and_capabilities'), ('MMAP_AUTHORIZATION_VM_PASS ', 'mmap_checks')]:
    line = next((v for v in log.splitlines() if v.startswith(marker)), '')
    report[field] = {k: int(v) for k, v in re.findall(r'(\w+)=(\d+)', line)}
(O / 'validation.json').write_text(json.dumps(report, indent=2) + '\n')
print(log[-15000:])
print(json.dumps(report, indent=2))
raise SystemExit(0 if passed else 1)
