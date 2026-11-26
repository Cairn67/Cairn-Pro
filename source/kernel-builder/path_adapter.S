// SPDX-License-Identifier: GPL-2.0-only
// Adapter between path hooks and the visibility engine.
#include "layout.h"

.section .text
.balign 4
.global cp_kernel_apply
.global cp_kernel_runtime

cp_kernel_apply:
 stp x29,x30,[sp,#-32]!
 stp x19,x20,[sp,#16]
 mov x29,sp
 mov x19,x0
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cp_adapter_denied
 ldr w10,[x9,#CA_CRED_UID]
 cbnz w10,cp_adapter_denied
 ldr w10,[x9,#CA_CRED_EUID]
 cbnz w10,cp_adapter_denied
 bl cp_kernel_runtime
 cbz x0,cp_adapter_nomem
 mov w1,wzr
 mov w2,wzr
 mov w3,#1
 mov x4,x19
 mov x5,#8720
 bl cp_apply
 b cp_adapter_return
cp_adapter_denied:
 mov x0,#-1
 b cp_adapter_return
cp_adapter_nomem:
 mov x0,#-12
cp_adapter_return:
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#32
 ret

cp_kernel_runtime:
 stp x29,x30,[sp,#-48]!
 stp x19,x20,[sp,#16]
 str x21,[sp,#32]
 mov x29,sp
 adr x19,cp_slot_delta
 ldr x9,[x19]
 add x19,x19,x9
 ldar x0,[x19]
 cbnz x0,cp_runtime_return
 mov x0,#88
 bl cp_allocate
 cbz x0,cp_runtime_return
 mov x20,x0
 adr x9,cp_lock
 adr x10,cp_unlock
 stp x9,x10,[x20]
 adr x9,cp_allocate
 adr x10,cp_release
 stp x9,x10,[x20,#16]
 adr x9,cp_copy_user
 adr x10,cp_resolve
 stp x9,x10,[x20,#32]
 add x9,x20,#72
 str x9,[x20,#48]
 str xzr,[x20,#56]
 str xzr,[x20,#64]
 stp xzr,xzr,[x20,#72]
cp_runtime_publish:
 ldaxr x21,[x19]
 cbnz x21,cp_runtime_lost
 stlxr w9,x20,[x19]
 cbnz w9,cp_runtime_publish
 mov x0,x20
 b cp_runtime_return
cp_runtime_lost:
 clrex
 mov x0,x20
 bl cp_release
 mov x0,x21
cp_runtime_return:
 ldr x21,[sp,#32]
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#48
 ret
cp_lock:
.global cp_lock_target
cp_lock_target:
 b 0
cp_unlock:
 
.global cp_unlock_target
cp_unlock_target:
 b 0
cp_allocate:
 
 mov x1,#0xcc0
.global cp_alloc_target
cp_alloc_target:
 b 0
cp_release:
.global cp_free_target
cp_free_target:
 b 0
cp_resolve:
.global cp_resolve_target
cp_resolve_target:
 b 0
cp_copy_user:
 stp x29,x30,[sp,#-16]!
 mov x29,sp
 .inst 0xd500409f 
.global cp_copy_target
cp_copy_target:
 bl 0
 .inst 0xd500419f 
 ldp x29,x30,[sp],#16
 ret
.balign 8
.global cp_slot_delta
cp_slot_delta:
 .quad 0

.balign 4
.global cp_kernel_log
.global cp_log_target
cp_kernel_log:
cp_log_target:
 b 0
