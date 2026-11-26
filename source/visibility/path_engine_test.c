// Tests for the path visibility engine.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "path_engine.h"
static struct cpr_table current,candidate,request;
static unsigned int tests;
#define CHECK(x) do{tests++;if(!(x)){fprintf(stderr,"FAIL line=%d\n",__LINE__);exit(1);}}while(0)
static int copy_ok(void *d,const void *s,unsigned long n){memcpy(d,s,n);return 0;}
static int copy_fault(void *d,const void *s,unsigned long n){(void)d;(void)s;(void)n;return 1;}
static int apply(struct cpr_request_identity id,cpr_copy_fn copy){
 int rc=cpr_prepare(&candidate,&request,sizeof(request),id,copy);
 if(!rc)current=candidate;
 return rc;
}
static void rule(unsigned int index,unsigned int uid,unsigned int flags,const char *path){
 struct cpr_rule *r=&request.rules[index];memset(r,0,sizeof(*r));r->uid=uid;r->flags=flags;r->length=strlen(path);memcpy(r->path,path,r->length+1);
}
int main(void){
 struct cpr_request_identity root={0,0,1},app={10001,10001,1},unauth={0,0,0};
 request.version=CPR_VERSION;request.bytes=sizeof(request);request.count=2;
 rule(0,10001,CPR_SUBTREE,"/private/test");rule(1,10001,CPR_SUBTREE|CPR_ALLOW,"/private/test/visible");
 CHECK(apply(root,copy_ok)==0);
 CHECK(cpr_hidden(&current,10001,"/private/test",13)==1);
 CHECK(cpr_hidden(&current,10001,"/private/test/file",18)==1);
 CHECK(cpr_hidden(&current,10002,"/private/test/file",18)==0);
 CHECK(cpr_hidden(&current,0,"/private/test/file",18)==0);
 CHECK(cpr_hidden(&current,10001,"/private/tester",15)==0);
 CHECK(cpr_hidden(&current,10001,"/private/test/visible/a",23)==0);
 CHECK(apply(app,copy_ok)==CPR_EPERM);CHECK(apply(unauth,copy_ok)==CPR_EPERM);
 CHECK(apply(root,copy_fault)==CPR_EFAULT);
 CHECK(cpr_prepare(&candidate,&request,sizeof(request)-1,root,copy_ok)==CPR_EINVAL);
 const char *bad[]={"/","relative","/a/","/a//b","/a/../b","/a/./b","/a\nb"};
 for(unsigned int i=0;i<sizeof(bad)/sizeof(*bad);i++){
  rule(0,10001,CPR_SUBTREE,bad[i]);CHECK(apply(root,copy_ok)==CPR_EINVAL);
  CHECK(cpr_hidden(&current,10001,"/private/test/file",18)==1);
 }
 rule(0,10001,CPR_SUBTREE,"/private/test");
 rule(1,10001,CPR_SUBTREE|CPR_ALLOW,"/private/test");CHECK(apply(root,copy_ok)==CPR_EINVAL);
 rule(1,10001,CPR_ALLOW,"/private/test");CHECK(apply(root,copy_ok)==0);
 CHECK(cpr_hidden(&current,10001,"/private/test",13)==0);
 CHECK(cpr_hidden(&current,10001,"/private/test/file",18)==1);
 request.count=33;CHECK(apply(root,copy_ok)==CPR_EINVAL);
 request.count=0;memset(request.rules,0,sizeof(request.rules));CHECK(apply(root,copy_ok)==0);
 CHECK(cpr_hidden(&current,10001,"/private/test/file",18)==0);
 printf("PATH_ENGINE_PASS assertions=%u vfs_hooks_tested=0\n",tests);return 0;
}
