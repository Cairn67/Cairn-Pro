/* SPDX-License-Identifier: GPL-2.0-only
 * Host-only failure injection. Device layout and page protections require VM tests.
 */
// Host-side test for the loader.
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "format.h"
#include "generated.h"
#include "sha256.h"
const unsigned long cl_auth[2]={0x1111111111111111UL,0x1111111111111111UL};
static int alloc_fail,copy_fail,copy_calls,nx_fail,ro_fail,x_fail,allocated,released,cache_calls;
static void *allocation;
unsigned long cl_copy_user(void *dst,const void *src,unsigned long n){copy_calls++;if(copy_calls==copy_fail)return n;memcpy(dst,src,n);return 0;}
void *cl_allocate(unsigned long bytes){if(alloc_fail)return NULL;allocation=malloc(bytes);assert(allocation);allocated++;return allocation;}
void cl_free(void *p){assert(p==allocation);released++;free(p);allocation=NULL;}
int cl_ro(unsigned long p,int n){assert(p==(unsigned long)allocation&&n==CL_CORE_PAGES);return ro_fail?-22:0;}
int cl_x(unsigned long p,int n){assert(p==(unsigned long)allocation&&n==CL_CORE_PAGES);return x_fail?-22:0;}
int cl_nx(unsigned long p,int n){assert(p==(unsigned long)allocation&&n==CL_CORE_PAGES);return nx_fail?-22:0;}
void cl_cache(unsigned long start,unsigned long end){assert(start==(unsigned long)allocation&&end-start==CL_CORE_BYTES);cache_calls++;}
void *cl_file_open(const char *p,int f,unsigned short m){(void)p;(void)f;(void)m;return (void *)-2L;}
int cl_file_close(void *p,void *id){(void)p;(void)id;return 0;}
long cl_file_read(void *f,void *p,unsigned long n,long long *o){(void)f;(void)p;(void)n;(void)o;return -5;}
void *cl_buffer_alloc(unsigned long n,unsigned int g){(void)g;return malloc(n);}
void cl_buffer_free(void *p){free(p);}
int cl_print(const char *p,...){(void)p;return 0;}
long cl_load(const void *,unsigned long,struct cl_state *,unsigned long);
static void reset(void){alloc_fail=copy_fail=copy_calls=nx_fail=ro_fail=x_fail=allocated=released=cache_calls=0;allocation=NULL;}
static void failed(unsigned char *file,long code){struct cl_state state={0};long got=cl_load(file,CL_CORE_BYTES+sizeof(struct cl_header),&state,0xffffffc080000000UL);assert(got==code);assert(!state.base&&!state.busy&&!state.loads);assert(allocated==released);}
int main(int argc,char **argv){
 assert(argc==2);FILE *f=fopen(argv[1],"rb");assert(f);unsigned long size=CL_CORE_BYTES+sizeof(struct cl_header);unsigned char *file=malloc(size);assert(file);assert(fread(file,1,size,f)==size);assert(fgetc(f)==EOF);fclose(f);
 struct cl_header *h=(void *)file;unsigned char digest[32];cl_sha256((unsigned char *)"abc",3,digest);static const unsigned char abc[32]={0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad};assert(!memcmp(digest,abc,32));cl_sha256(file+sizeof(*h),CL_CORE_BYTES,digest);assert(!memcmp(digest,cl_digest,32));
 struct cl_state state={0};assert(cl_load(NULL,size,&state,0)==-22);assert(cl_load(file,size-1,&state,0)==-22);assert(cl_load((void *)0xffff000000000000UL,size,&state,0)==-22);
 state.busy=1;assert(cl_load(file,size,&state,0)==-16);state.busy=0;
 reset();copy_fail=1;failed(file,-14);
 reset();h->profile^=1;failed(file,-22);h->profile^=1;
 reset();h->digest[0]^=1;failed(file,-22);h->digest[0]^=1;
 reset();alloc_fail=1;failed(file,-12);
 reset();nx_fail=1;failed(file,-22);
 reset();copy_fail=2;failed(file,-14);
 reset();file[size-1]^=1;failed(file,-74);file[size-1]^=1;
 reset();ro_fail=1;failed(file,-22);
 reset();x_fail=1;failed(file,-22);
 reset();assert(!cl_load(file,size,&state,0xffffffc080000000UL));assert(state.base==(unsigned long)allocation&&state.bytes==CL_CORE_BYTES&&state.loads==1&&!state.busy&&cache_calls==1&&allocated==1&&!released);
 assert(!memcmp((unsigned char *)allocation+CL_KEY_OFFSET,cl_auth,16));for(unsigned int i=0;i<CL_BRIDGES;i++)assert(*(unsigned long *)((char *)allocation+cl_fixups[i].offset)==0xffffffc080000000UL+cl_fixups[i].bridge);
 assert(cl_load(file,size,&state,0)==-114);cl_free(allocation);free(file);puts("LOADER_UNIT_PASS sha256=1 bounded_input=1 profile_digest=1 copy_faults=1 alloc_failure=1 protection_failures=1 cleanup=1 relocation=1 one_shot=1");return 0;
}
