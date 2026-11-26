// SPDX-License-Identifier: GPL-2.0-only
// Virtual machine tests for the access rule format.
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/ioctl.h>
#include <poll.h>
#include "../../daemon/access_parser.h"
static const char *v2_server_context="u:r:system_server:s0";
static const char *v2_pts_context="u:object_r:untrusted_app_all_devpts:s0:c104,c257,c512,c768";
struct v2_observation {int setup,rc,error,uid,euid;char ctx[128];};
static struct v2_observation *v2_result;
static void v2_rules(const char *text,int expected,const char *name){
 int f=open("/access-v2.rules",O_CREAT|O_WRONLY|O_TRUNC,0600);
 ck(f>=0&&write(f,text,strlen(text))==(ssize_t)strlen(text),"v2-rule-file-write");close(f);
 ck(cnd("apply-text","/access-v2.rules")==expected,name);
}
static void v2_request(unsigned int uid,const char *ctx,int action,int fd,const char *path){
 memset(v2_result,0,sizeof(*v2_result));pid_t p=fork();ck(p>=0,"v2-subject-fork");
 if(!p){
  if(textwrite("/proc/self/attr/current",ctx)||setgid(uid)||setuid(uid)){v2_result->setup=errno;_exit(0);}
  v2_result->uid=getuid();v2_result->euid=geteuid();context(v2_result->ctx,sizeof(v2_result->ctx));
  errno=0;
  if(action==0)v2_result->rc=write(fd,"V2_PTY_OUTPUT\n",14);
  else if(action==1){char b;v2_result->rc=read(fd,&b,1);}
  else if(action==2){
   int s=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
   if(s<0)v2_result->rc=-2;
   else {struct sockaddr_un a={.sun_family=AF_UNIX};strcpy(a.sun_path,path);v2_result->rc=connect(s,(void *)&a,sizeof(a));int e=errno;close(s);errno=e;}
  }else if(action==3){
   int s=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
   if(s<0)v2_result->rc=-2;
   else {struct sockaddr_un a={.sun_family=AF_UNIX};strcpy(a.sun_path,path);v2_result->rc=bind(s,(void *)&a,sizeof(a));int e=errno;close(s);errno=e;}
  }else if(action==4)v2_result->rc=unlink(path);
  else if(action==5)v2_result->rc=chmod(path,0600);
  else if(action==6){unsigned int n=0;v2_result->rc=ioctl(fd,TIOCGPTN,&n);}
  v2_result->error=errno;_exit(0);
 }
 waitok(p);printf("V2_OBSERVATION uid=%u action=%d path=%s setup=%d rc=%d errno=%d context=%s\n",uid,action,path?path:"held-fd",v2_result->setup,v2_result->rc,v2_result->error,v2_result->ctx);
 ck(!v2_result->setup&&v2_result->uid==(int)uid&&v2_result->euid==(int)uid&&!strcmp(v2_result->ctx,ctx),"v2-subject-uid-and-domain-preserved");
}
static int v2_denied(void){return v2_result->rc<0&&(v2_result->error==EACCES||v2_result->error==EPERM);}
static void v2_pty_test(void){
 int master,slave,master2,slave2;char path[128],path2[128];
 ck(!openpty(&master,&slave,path,NULL,NULL)&&!openpty(&master2,&slave2,path2,NULL,NULL),"v2-two-pty-fixtures");
 ck(!fchmod(slave,0666)&&!fchmod(slave2,0666),"v2-pty-DAC-permitted");exact_label(path,v2_pts_context);exact_label(path2,v2_pts_context);
 ck(!fcntl(slave,F_SETFL,O_NONBLOCK),"v2-pty-nonblocking-read");
 char base[2048],full[3072];
 snprintf(base,sizeof(base),"version=2\nallow uid=1000 subject=u:r:system_server:s0 object=u:r:kernel:s0 class=fd perms=use\n");
 v2_rules(base,0,"v2-fd-use-only-submit");
 v2_request(1000,v2_server_context,0,slave,NULL);ck(v2_denied(),"v2-server-PTY-write-denied-without-rule");
 snprintf(full,sizeof(full),"%sallow uid=1000 subject=u:r:system_server:s0 object=%s class=chr_file perms=write path=%s match=exact\n",base,v2_pts_context,path);
 v2_rules(full,0,"v2-exact-PTY-write-grant-submit");
 v2_request(1000,v2_server_context,0,slave,NULL);ck(v2_result->rc==14,"v2-server-PTY-write-granted");
 struct pollfd f={.fd=master,.events=POLLIN};char output[128]={0};ck(poll(&f,1,100)>0&&read(master,output,sizeof(output)-1)>0&&strstr(output,"V2_PTY_OUTPUT"),"v2-package-service-style-output-reaches-terminal");
 v2_request(1000,v2_server_context,0,slave2,NULL);ck(v2_denied(),"v2-other-PTY-same-label-stays-denied");
 v2_request(1000,v2_server_context,1,slave,NULL);ck(v2_denied(),"v2-write-grant-does-not-grant-read");
 v2_request(1001,v2_server_context,0,slave,NULL);ck(v2_denied(),"v2-other-UID-same-domain-stays-denied");
 v2_rules("version=2\nallow uid=1000 subject=u:r:system_server:s0 object=u:object_r:rootfs:s0 class=chr_file perms=write unknown=yes\n",2,"v2-invalid-reload-rejected");
 v2_request(1000,v2_server_context,0,slave,NULL);ck(v2_result->rc==14,"v2-invalid-reload-keeps-previous-grant");
 v2_rules(base,0,"v2-PTY-grant-revoked");v2_request(1000,v2_server_context,0,slave,NULL);ck(v2_denied(),"v2-held-PTY-FD-write-revoked");
 v2_request(0,appctx,0,slave,NULL);ck(v2_result->rc==14,"v2-UID0-write-without-declarative-rule");
 v2_request(0,appctx,6,master,NULL);ck(v2_result->rc==0,"v2-UID0-extended-ioctl-path");
 close(master);close(slave);close(master2);close(slave2);
 puts("SCOPED_PTY_VM_PASS exact_path=1 service_uid=1000 identity_preserved=1 operation_isolation=1 held_fd_revoke=1 root_uid0=1");
}
static int v2_listen(const char *path){
 int s=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC|SOCK_NONBLOCK,0);ck(s>=0,"v2-root-create-socket");
 struct sockaddr_un a={.sun_family=AF_UNIX};strcpy(a.sun_path,path);ck(!bind(s,(void *)&a,sizeof(a))&&!listen(s,8),"v2-root-bind-listen-node");
 ck(!chmod(path,0777),"v2-socket-node-DAC");label(path);return s;
}

static void v2_created_socket_context(char *out,unsigned int cap){
 char id[32]={0},query[640];int f=open("/sys/fs/selinux/class/sock_file/index",O_RDONLY);
 ck(f>=0&&read(f,id,sizeof(id)-1)>0,"v2-read-policy-socket-class");close(f);
 int policy_class=atoi(id);ck(policy_class>0,"v2-policy-class-valid");
 int n=snprintf(query,sizeof(query),"%s %s %d app.sock",appctx,"u:object_r:adb_data_file:s0",policy_class);
 f=open("/sys/fs/selinux/create",O_RDWR);ck(f>=0,"v2-open-native-context-computation");
 ck(write(f,query,n)==n,"v2-native-node-context-computation");
 ck(lseek(f,0,SEEK_SET)==0,"v2-context-computation-rewind");
 int z=read(f,out,cap-1);ck(z>0&&(unsigned int)z<cap,"v2-read-computed-node-context");out[z]=0;close(f);
 for(int i=0;i<z;i++)if(out[i]=='\n')out[i]=0;
 printf("V2_COMPUTED_SOCKET_CONTEXT=%s\n",out);
}
static void v2_socket_test(void){
 mkdir("/socket-v2",0777);chmod("/socket-v2",0777);label("/socket-v2");
 int listener=v2_listen("/socket-v2/server.sock");
 const char *node="allow uid=12041 subject=u:r:untrusted_app:s0:c104,c257,c512,c768 object=u:object_r:adb_data_file:s0 class=dir perms=search\nallow uid=12041 subject=u:r:untrusted_app:s0:c104,c257,c512,c768 object=u:object_r:adb_data_file:s0 class=sock_file perms=write\n";
 char rules[6000];snprintf(rules,sizeof(rules),"version=2\n%s",node);v2_rules(rules,0,"v2-socket-node-only-rules");
 v2_request(12041,appctx,2,0,"/socket-v2/server.sock");ck(v2_denied(),"v2-node-write-does-not-imply-peer-connectto");
 snprintf(rules,sizeof(rules),"version=2\n%sallow uid=12041 subject=u:r:untrusted_app:s0:c104,c257,c512,c768 object=u:r:kernel:s0 class=unix_stream_socket perms=connectto\n",node);
 v2_rules(rules,0,"v2-peer-connectto-submit");v2_request(12041,appctx,2,0,"/socket-v2/server.sock");ck(v2_result->rc==0,"v2-unix-stream-peer-connectto-granted");
 int accepted=accept(listener,NULL,NULL);ck(accepted>=0,"v2-server-actually-received-connection");close(accepted);
 v2_request(12042,appctx,2,0,"/socket-v2/server.sock");ck(v2_denied(),"v2-other-socket-client-UID-denied");
 v2_rules("version=2\n",0,"v2-socket-clear");v2_request(12041,appctx,2,0,"/socket-v2/server.sock");ck(v2_denied(),"v2-new-connection-denied-after-clear");
 char node_context[128];v2_created_socket_context(node_context,sizeof(node_context));
 snprintf(rules,sizeof(rules),"version=2\nallow uid=12041 subject=%s object=u:object_r:adb_data_file:s0 class=dir perms=search,write,add_name,remove_name\nallow uid=12041 subject=%s object=%s class=sock_file perms=create,unlink,getattr,setattr\nallow uid=12041 subject=%s source=%s object=u:object_r:rootfs:s0 class=filesystem perms=associate\n",appctx,appctx,node_context,appctx,node_context);
 v2_request(12041,appctx,3,0,"/socket-v2/app.sock");ck(v2_denied(),"v2-unix-node-create-denied-before-rules");
 v2_rules(rules,0,"v2-node-create-unlink-submit");v2_request(12041,appctx,3,0,"/socket-v2/app.sock");ck(v2_result->rc==0,"v2-unix-bind-created-socket-node");
 struct stat st;ck(!stat("/socket-v2/app.sock",&st)&&S_ISSOCK(st.st_mode)&&st.st_uid==12041,"v2-node-is-real-app-owned-socket");
 v2_request(12041,appctx,5,0,"/socket-v2/app.sock");ck(v2_result->rc==0,"v2-sock-file-setattr-granted");
 v2_request(12041,appctx,4,0,"/socket-v2/app.sock");ck(v2_result->rc==0,"v2-sock-file-unlink-granted");
 v2_request(0,appctx,3,0,"/socket-v2/root.sock");ck(v2_result->rc==0,"v2-UID0-unix-bind-node-without-rule");
 v2_request(0,appctx,4,0,"/socket-v2/root.sock");ck(v2_result->rc==0,"v2-UID0-unix-unlink-without-rule");
 close(listener);ck(!unlink("/socket-v2/server.sock"),"v2-server-node-cleanup");
 puts("SCOPED_SOCKET_VM_PASS unix_connectto=1 node_create=1 node_unlink=1 node_setattr=1 identity_preserved=1 root_uid0=1 policy_unchanged=1");
}
static void access_v2_tests(void){
 ck(op(12,NULL)==CAV_CAP,"v2-kernel-capability");v2_result=mmap(NULL,4096,PROT_READ|PROT_WRITE,MAP_SHARED|MAP_ANONYMOUS,-1,0);ck(v2_result!=MAP_FAILED,"v2-observation-memory");
 v2_pty_test();v2_socket_test();
 ck(!cnd("apply","/access.rules"),"v2-restore-original-legacy-persisted-rules");munmap(v2_result,4096);
 puts("ACCESS_V2_KERNEL_VM_PASS scoped_PTY=1 unix_connectto=1 socket_nodes=1 strict_scope=1 revoke=1 UID0=1 enforcing=1");
}
