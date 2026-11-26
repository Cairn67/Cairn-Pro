// SPDX-License-Identifier: GPL-2.0-only
// Adapter between the access hooks and the rule engine.
#include "layout.h"

.section .text
.balign 4
.global ca_kernel_apply
.global ca_kernel_runtime

ca_kernel_apply:
 stp x29,x30,[sp,#-32]!
 stp x19,x20,[sp,#16]
 mov x29,sp
 mov x19,x0
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,ca_adapter_denied
 ldr w10,[x9,#CA_CRED_UID]
 cbnz w10,ca_adapter_denied
 ldr w10,[x9,#CA_CRED_EUID]
 cbnz w10,ca_adapter_denied
 bl ca_kernel_runtime
 cbz x0,ca_adapter_nomem
 mov w1,wzr
 mov w2,wzr
 mov w3,#1
 mov x4,x19
 
 bl ca_replace
 b ca_adapter_return
ca_adapter_denied:
 mov x0,#-1
 b ca_adapter_return
ca_adapter_nomem:
 mov x0,#-12
ca_adapter_return:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#32
 ret

ca_kernel_runtime:
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 str x21,[sp,#32]
 mov x29,sp
 adr x19,ca_slot_delta
 ldr x9,[x19]
 add x19,x19,x9
 ldar x0,[x19]
 cbnz x0,ca_runtime_return
 mov x0,#80
 bl ca_allocate
 cbz x0,ca_runtime_return
 mov x20,x0
 adr x9,ca_lock
 adr x10,ca_unlock
 stp x9,x10,[x20]
 adr x9,ca_allocate
 adr x10,ca_release
 stp x9,x10,[x20,#16]
 adr x9,ca_copy_user
 str x9,[x20,#32]
 add x9,x20,#72
 str x9,[x20,#40]
 str xzr,[x20,#48]
 adr x9,ca_resolve
 str x9,[x20,#56]
 str xzr,[x20,#64]
 str xzr,[x20,#72]
ca_runtime_publish:
 ldaxr x21,[x19]
 cbnz x21,ca_runtime_lost
 stlxr w9,x20,[x19]
 cbnz w9,ca_runtime_publish
 mov x0,x20
 b ca_runtime_return
ca_runtime_lost:
 clrex
 mov x0,x20
 bl ca_release
 mov x0,x21
ca_runtime_return:
 ldr x21,[sp,#32]
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 ret
ca_lock:
.global ca_lock_target
ca_lock_target:
 b 0
ca_unlock:
 
.global ca_unlock_target
ca_unlock_target:
 b 0
ca_allocate:
 
 mov x1,#0xcc0
.global ca_alloc_target
ca_alloc_target:
 b 0
ca_release:
.global ca_free_target
ca_free_target:
 b 0
ca_resolve:
.global ca_resolve_target
ca_resolve_target:
 b 0
ca_copy_user:
 stp x29,x30,[sp,#-16]!
 mov x29,sp
 .inst 0xd500409f 
.global ca_copy_target
ca_copy_target:
 bl 0
 .inst 0xd500419f 
 ldp x29,x30,[sp],#16
 ret
.balign 8
.global ca_slot_delta
ca_slot_delta:
 .quad 0

.global cav_kernel_apply
cav_kernel_apply:
 stp x29,x30,[sp,#-32]!
 stp x19,x20,[sp,#16]
 mov x29,sp
 mov x19,x0
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,ca_adapter_denied
 ldr w10,[x9,#CA_CRED_UID]
 cbnz w10,ca_adapter_denied
 ldr w10,[x9,#CA_CRED_EUID]
 cbnz w10,ca_adapter_denied
 bl ca_kernel_runtime
 cbz x0,ca_adapter_nomem
 mov w1,wzr
 mov w2,wzr
 mov w3,#1
 mov x4,x19
 bl ca_replace_v2
 b ca_adapter_return
