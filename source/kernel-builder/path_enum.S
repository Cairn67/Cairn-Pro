// SPDX-License-Identifier: GPL-2.0-only
// Directory enumeration hook for path visibility.
#include "layout.h"
.section .text
.balign 4
.global cp_iterate_entry
cp_iterate_entry:
 paciasp
 stp x29,x30,[sp,#-64]!
 stp x19,x20,[sp,#16]
 stp x0,x1,[sp,#32]
 mov x29,sp
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cp_iterate_continue
 ldr w20,[x9,#CA_CRED_UID]
 mov w10,#10000
 cmp w20,w10
 b.lo cp_iterate_continue
 adr x9,cp_slot_delta
 ldr x10,[x9]
 add x9,x9,x10
 ldar x19,[x9]
 cbz x19,cp_iterate_continue
 mov x0,x19
 mov w1,w20
 ldp x2,x3,[sp,#32]
 bl cp_iterate_apply
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#64
 autiasp
 ret
cp_iterate_continue:
 ldp x0,x1,[sp,#32]
 ldp x19,x20,[sp,#16]
 ldp x29,x30,[sp],#64
.global cp_iterate_tail
cp_iterate_tail:
 b 0

.global cp_original_iterate
cp_original_iterate:
 paciasp
.global cp_original_iterate_tail
cp_original_iterate_tail:
 b 0

.balign 4
.word 0xb1edbef0
.global cp_enum_actor
cp_enum_actor:
 b cp_enum_actor_impl
