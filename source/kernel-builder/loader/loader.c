/* SPDX-License-Identifier: GPL-2.0-only
 * Detached core loader: authenticated, fixed-profile, fail-closed and one-shot.
 * No live text patching: immutable gateways publish one acquire/release pointer.
 */
// Loader that prepares and jumps into the payload.
#include "format.h"
#include "generated.h"
#include "sha256.h"
extern unsigned long cl_copy_user(void *,const void *,unsigned long);
extern void *cl_allocate(unsigned long);
extern void cl_free(void *);
extern int cl_ro(unsigned long,int);
extern int cl_x(unsigned long,int);
extern int cl_nx(unsigned long,int);
extern void cl_cache(unsigned long,unsigned long);
extern const unsigned long cl_auth[2];
static unsigned long cl_copy(void *out,const void *in,unsigned long n,int kernel){if(!kernel)return cl_copy_user(out,in,n);for(unsigned long i=0;i<n;i++)((unsigned char *)out)[i]=((const unsigned char *)in)[i];return 0;}
static int cl_equal(const void *a,const void *b,unsigned int n){const unsigned char *x=a,*y=b;unsigned int v=0;for(unsigned int i=0;i<n;i++)v|=x[i]^y[i];return !v;}
static long cl_load_source(const void *user,unsigned long bytes,struct cl_state *s,unsigned long kernel,int from_kernel){
 unsigned long addr=(unsigned long)user;
 if(bytes!=sizeof(struct cl_header)+CL_CORE_BYTES||bytes>CL_LIMIT+sizeof(struct cl_header)||!addr||(!from_kernel&&(addr>=(1UL<<48)||bytes>(1UL<<48)-addr)))return -22;
 if(__atomic_load_n(&s->base,__ATOMIC_ACQUIRE))return -114;
 unsigned int expected=0;if(!__atomic_compare_exchange_n(&s->busy,&expected,1,0,__ATOMIC_ACQUIRE,__ATOMIC_RELAXED))return -16;
 long error=-114;unsigned char *code=(void *)0;struct cl_header head;
 if(__atomic_load_n(&s->base,__ATOMIC_ACQUIRE))goto done;
 error=-14;
 if(cl_copy(&head,user,sizeof(head),from_kernel))goto done;
 error=-22;if(head.magic!=CL_MAGIC||head.abi!=CL_ABI||head.profile!=CL_PROFILE||head.bytes!=CL_CORE_BYTES||!cl_equal(head.digest,cl_digest,32))goto done;
 error=-12;code=cl_allocate(CL_CORE_BYTES);if(!code)goto done;
 error=cl_nx((unsigned long)code,CL_CORE_PAGES);if(error)goto done;
 error=-14;if(cl_copy(code,(const unsigned char *)user+sizeof(head),CL_CORE_BYTES,from_kernel))goto done;
 unsigned char digest[32];cl_sha256(code,CL_CORE_BYTES,digest);
 error=-74;if(!cl_equal(digest,cl_digest,32))goto done;
 error=-22;for(unsigned int i=0;i<16;i++)if(code[CL_KEY_OFFSET+i])goto done;
 for(unsigned int i=0;i<16;i++)code[CL_KEY_OFFSET+i]=((const unsigned char *)cl_auth)[i];
 for(unsigned int i=0;i<CL_BRIDGES;i++)*(unsigned long *)(code+cl_fixups[i].offset)=kernel+cl_fixups[i].bridge;
 for(unsigned int i=0;i<CL_RELATIVES;i++)*(unsigned long *)(code+cl_relatives[i].offset)=kernel+cl_relatives[i].target-((unsigned long)code+cl_relatives[i].offset);
 cl_cache((unsigned long)code,(unsigned long)code+CL_CORE_BYTES);
 error=cl_ro((unsigned long)code,CL_CORE_PAGES);if(error)goto done;
 error=cl_x((unsigned long)code,CL_CORE_PAGES);if(error)goto done;
 
 s->bytes=CL_CORE_BYTES;s->loads++;s->last_error=0;
 __atomic_store_n(&s->base,(unsigned long)code,__ATOMIC_RELEASE);code=(void *)0;error=0;
 done:if(code)cl_free(code);if(error)s->last_error=(int)error;__atomic_store_n(&s->busy,0,__ATOMIC_RELEASE);return error;
}

long cl_load(const void *user,unsigned long bytes,struct cl_state *s,unsigned long kernel){return cl_load_source(user,bytes,s,kernel,0);}
extern void *cl_file_open(const char *,int,unsigned short);
extern int cl_file_close(void *,void *);
extern long cl_file_read(void *,void *,unsigned long,long long *);
extern void *cl_buffer_alloc(unsigned long,unsigned int);
extern void cl_buffer_free(void *);
extern int cl_print(const char *,...);
void cl_boot_load(struct cl_state *s,unsigned long kernel){
 void *file=cl_file_open("/cairn/core.cld",0x20800,0);
 long error=(long)file;
 if((unsigned long)file>=(unsigned long)-4095)goto log;
 unsigned long bytes=sizeof(struct cl_header)+CL_CORE_BYTES,done=0;
 unsigned char *buffer=cl_buffer_alloc(bytes,0xcc0);error=-12;if(!buffer)goto close;
 long long position=0;
 while(done<bytes){long count=cl_file_read(file,buffer+done,bytes-done,&position);if(count<=0){error=count?count:-22;goto free;}done+=(unsigned long)count;}
 unsigned char extra;error=cl_file_read(file,&extra,1,&position);if(error){error=-22;goto free;}
 error=cl_load_source(buffer,bytes,s,kernel,1);
 free:cl_buffer_free(buffer);
 close:cl_file_close(file,(void *)0);
 log:cl_print("\0016CairnLoader boot result=%ld core_bytes=%lu\n",error,s->bytes);
}
