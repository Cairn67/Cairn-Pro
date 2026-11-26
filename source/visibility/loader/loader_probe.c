// Probe that reports loader behaviour.
#define _GNU_SOURCE
#include "loader_abi.h"
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

int main(int argc,char **argv){
 if(argc!=3||strcmp(argv[1],"--selftest")||argv[2][0]!='/'){
  fprintf(stderr,"Usage: cairn-loader-probe --selftest /absolute/test-payload.so\n");return 2;
 }
 void *handle=dlopen(argv[2],RTLD_NOW|RTLD_LOCAL);
 if(!handle){fprintf(stderr,"PAYLOAD_LOAD_FAILED\n");return 3;}
 cairn_descriptor_fn describe=(cairn_descriptor_fn)dlsym(handle,"cairn_visibility_descriptor");
 cairn_selftest_fn test=(cairn_selftest_fn)dlsym(handle,"cairn_visibility_selftest");
 Dl_info mapping;
 if(test && dladdr((void*)test,&mapping))fprintf(stderr,"PAYLOAD_MAPPING base=%p path=%s symbol=%s\n",mapping.dli_fbase,mapping.dli_fname,mapping.dli_sname?mapping.dli_sname:"unknown");
 int rc=4;
 if(!describe||!test)goto done;
 const struct cairn_visibility_descriptor *d=describe();
 if(!d||d->magic!=CAIRN_VISIBILITY_MAGIC||d->size!=sizeof(*d)||d->abi!=CAIRN_VISIBILITY_ABI||d->pointer_size!=sizeof(void*)||d->reserved)goto done;
 rc=test(CAIRN_VISIBILITY_ABI)==0?0:5;
done:
 dlclose(handle);
 puts(rc==0?"LOADER_HANDSHAKE_PASS":"LOADER_HANDSHAKE_REJECTED");
 return rc;
}
