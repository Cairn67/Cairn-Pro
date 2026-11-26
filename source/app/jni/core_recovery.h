/* SPDX-License-Identifier: GPL-2.0-only
 * Authenticated detached-core recovery, called in the forked child BEFORE root.
 * No kernel-memory writes or credential changes are performed directly here.
 * All loading goes through the existing bootstrap's digest-pinned operation 8.
 * The includer supplies request/pause/event callbacks for raw syscalls or tests.
 */
// Recovery path that restores a missing kernel payload from the bundled blob.
#ifndef CAIRN_APP_CORE_RECOVERY_H
#define CAIRN_APP_CORE_RECOVERY_H
static unsigned int cairn_core_u32(const unsigned char *p){
 return (unsigned int)p[0]|((unsigned int)p[1]<<8)|((unsigned int)p[2]<<16)|((unsigned int)p[3]<<24);
}
static long cairn_ensure_core(uint64_t k0,uint64_t k1,const unsigned char *blob,unsigned long bytes){
 long ready=cairn_recovery_request(k0,k1,9,0);
 
 if(ready==-22 || ready==-38 || ready==-1)return 0;
 if(ready>0)return 0;
 cairn_recovery_event("CAIRN_LOADER_STATUS=",ready);
 if(ready<0){cairn_recovery_event("CAIRN_LOADER_ERROR=",ready);return ready;}
 
 if(!blob || bytes<48 || bytes>48+1024UL*1024UL ||
    cairn_core_u32(blob)!=0x434c4431U || cairn_core_u32(blob+4)!=1 ||
    cairn_core_u32(blob+8)!=0x434e0001U || cairn_core_u32(blob+12)!=bytes-48){
  cairn_recovery_event("CAIRN_LOADER_ERROR=",-22);return -22;
 }
 long rc=cairn_recovery_request(k0,k1,8,(unsigned long)blob);
 cairn_recovery_event("CAIRN_LOADER_LOAD_RC=",rc);
 if(rc && rc!=-16 && rc!=-114){cairn_recovery_event("CAIRN_LOADER_ERROR=",rc);return rc;}
 
 for(unsigned int i=0;i<100;i++){
  ready=cairn_recovery_request(k0,k1,9,0);
  if(ready>0){cairn_recovery_event("CAIRN_LOADER_READY_BYTES=",ready);return 0;}
  if(ready<0){cairn_recovery_event("CAIRN_LOADER_ERROR=",ready);return ready;}
  if(!rc)break; 
  cairn_recovery_pause();
 }
 rc=rc?-16:-5;cairn_recovery_event("CAIRN_LOADER_ERROR=",rc);return rc;
}
#endif
