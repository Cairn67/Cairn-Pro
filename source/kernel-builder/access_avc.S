// SPDX-License-Identifier: GPL-2.0-only
// AVC hook entry points.
#include "layout.h"

.section .text
.balign 4
.global cav_avc_entry,cav_avcn_entry,cav_xperm_entry
.global cav_avc_tail,cav_avcn_tail,cav_xperm_tail
.macro cav_save
 sub sp,sp,#80
 stp x29,x30,[sp]
 stp x0,x1,[sp,#16]
 stp x2,x3,[sp,#32]
 stp x4,x5,[sp,#48]
 stp x6,x7,[sp,#64]
 mov x29,sp
.endm
.macro cav_restore
 ldp x0,x1,[sp,#16]
 ldp x2,x3,[sp,#32]
 ldp x4,x5,[sp,#48]
 ldp x6,x7,[sp,#64]
 ldp x29,x30,[sp]
 add sp,sp,#80
.endm
.macro cav_success
 ldp x29,x30,[sp]
 add sp,sp,#80
 autiasp
 mov w0,wzr
 ret
.endm
cav_avc_entry:
 cav_save
#ifdef CAIRN_REPLAY_SLOTS
 
 ldr x4,[sp,#56]  
 ldr w3,[sp,#40]  
 ldr w2,[sp,#24]  
 ldr w1,[sp,#16]  
#else
 
#endif
 mrs x5,sp_el0
 bl cav_avc_allowed
 cbz w0,cav_avc_native
 cav_success
cav_avc_native:
 cav_restore
#ifdef CAIRN_REPLAY_SLOTS
	
	.inst	CAIRN_REPLAY_5
#else
 sub sp,sp,#0x70
#endif
cav_avc_tail:
 b 0

cav_avcn_entry:
 cav_save
 
 mov x4,xzr
 mrs x5,sp_el0
 bl cav_avc_allowed
 cbz w0,cav_avcn_native
 
 ldr x0,[sp,#56]
 ldr w1,[sp,#40] 
 bl cav_fill_decision
 cav_success
cav_avcn_native:
 cav_restore
#ifdef CAIRN_REPLAY_SLOTS
	
	.inst	CAIRN_REPLAY_6
#else
 stp x29,x30,[sp,#-80]!
#endif
cav_avcn_tail:
 b 0

cav_xperm_entry:
 cav_save
 mrs x0,sp_el0
 bl cav_root_subject
 cbz w0,cav_xperm_native
 cav_success
cav_xperm_native:
 cav_restore
#ifdef CAIRN_REPLAY_SLOTS
	
	.inst	CAIRN_REPLAY_0
#else
 sub sp,sp,#0x150
#endif
cav_xperm_tail:
 b 0

.global cav_policy_sequence,cav_policy_seq_tail,cav_resolve_context,cav_context_tail
cav_policy_sequence:
cav_policy_seq_tail:
 b 0
cav_resolve_context:
cav_context_tail:
 b 0
.global cav_current_sid
cav_current_sid:
 mrs x9,sp_el0
 ldr x9,[x9,#CA_TASK_CRED]
 cbz x9,cav_no_sid
 ldr x9,[x9,#CA_CRED_SECURITY]
 cbz x9,cav_no_sid
 adr x10,cr_blob_delta
 ldr x11,[x10]
 add x10,x10,x11
 ldrsw x10,[x10]
 cmp x10,#0
 b.lt cav_no_sid
 cmp x10,#4096
 b.hi cav_no_sid
 add x9,x9,x10
 ldr w0,[x9,#4] 
 ret
cav_no_sid:
 mov w0,wzr
 ret
