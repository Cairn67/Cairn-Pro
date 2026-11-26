/* SPDX-License-Identifier: GPL-2.0-only */
// Host-side test for the core recovery path.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
static long status,load_rc;
static int statuses,loads,pauses,events,publish_after;
static long cairn_recovery_request(uint64_t k0,uint64_t k1,unsigned long op,unsigned long arg){
 assert(k0==11&&k1==22);
 if(op==9){assert(!arg);statuses++;if(publish_after&&statuses>=publish_after)return 65536;return status;}
 assert(op==8&&arg);loads++;return load_rc;
}
static void cairn_recovery_pause(void){pauses++;}
static void cairn_recovery_event(const char *n,long v){(void)v;assert(!strncmp(n,"CAIRN_LOADER_",13));events++;}
#include "core_recovery.h"
static unsigned char blob[64];
static void word(unsigned int off,unsigned int v){for(int i=0;i<4;i++)blob[off+i]=(unsigned char)(v>>(8*i));}
static void reset(long st,long rc,int after){status=st;load_rc=rc;publish_after=after;statuses=loads=pauses=events=0;memset(blob,0,sizeof(blob));word(0,0x434c4431);word(4,1);word(8,0x434e0001);word(12,16);}
static long go(void){return cairn_ensure_core(11,22,blob,sizeof(blob));}
int main(void){
 reset(65536,0,0);assert(!go()&&!loads&&!events);
 reset(-22,0,0);assert(!go()&&!loads); 
 reset(-1,0,0);assert(!go()&&!loads);
 reset(-38,0,0);assert(!go()&&!loads);
 reset(-13,0,0);assert(go()==-13&&!loads);
 reset(0,0,2);assert(!go()&&loads==1&&statuses==2&&!pauses);
 reset(0,-16,5);assert(!go()&&loads==1&&pauses==3);
 reset(0,-114,2);assert(!go()&&loads==1&&!pauses);
 reset(0,-16,0);assert(go()==-16&&loads==1&&pauses==100&&statuses==101);
 reset(0,0,0);assert(go()==-5&&loads==1&&statuses==2&&!pauses);
 reset(0,-74,0);assert(go()==-74&&loads==1&&statuses==1);
 reset(0,-22,0);assert(go()==-22&&loads==1);
 reset(0,0,0);blob[0]^=1;assert(go()==-22&&!loads);
 reset(0,0,0);word(4,2);assert(go()==-22&&!loads);
 reset(0,0,0);word(8,0);assert(go()==-22&&!loads);
 reset(0,0,0);word(12,17);assert(go()==-22&&!loads);
 reset(0,0,0);assert(cairn_ensure_core(11,22,NULL,64)==-22&&!loads);
 reset(0,0,0);assert(cairn_ensure_core(11,22,blob,47)==-22&&!loads);
 reset(0,0,0);assert(cairn_ensure_core(11,22,blob,48+1024UL*1024UL+1)==-22&&!loads);
 puts("APP_CORE_RECOVERY_UNIT_PASS cases=19 legacy=1 wrong_key=1 corruption=1 concurrent=1 timeout=1");
 return 0;
}
