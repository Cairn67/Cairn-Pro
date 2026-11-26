// Gates tracing output during startup.
#define _GNU_SOURCE
#include <stdio.h>
#include <unistd.h>
#include <signal.h>
#include <errno.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <elf.h>
#include <time.h>
#include <stdlib.h>
#include <limits.h>
#include "crash_record.h"
int cairn_probe_main(int argc,char **argv);
static long ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000L+t.tv_nsec/1000000;}
int main(int argc,char **argv){
 if(argc!=1&&(argc!=3||strcmp(argv[1],"--selftest")||argv[2][0]!='/'))return 2;
 int channel[2];if(pipe(channel)<0)return 10;
 setvbuf(stdout,NULL,_IONBF,0);setvbuf(stderr,NULL,_IONBF,0);
 pid_t child=fork();if(child<0){perror("FORK_FAILED");return 10;}
 if(child==0){
  close(channel[1]);char command=0;ssize_t n;
  do{n=read(channel[0],&command,1);}while(n<0&&errno==EINTR);
  close(channel[0]);if(n!=1||command!='L')_exit(20);
  if(argc==1)_exit(0);
  if(cairn_install_crash_record()<0)_exit(14);
  puts("COOPERATIVE_CHILD_LOAD_START");int result=cairn_probe_main(argc,argv);
  puts("COOPERATIVE_CHILD_RESUMED");_exit(result);
 }
 close(channel[0]);
 int status=0,rc=1;long deadline=ms()+3000;
 printf("TRACE_GATE_OWN_CHILD pid=%ld\n",(long)child);
 if(ptrace(PTRACE_ATTACH,child,NULL,NULL)<0){printf("TRACE_ATTACH_DENIED errno=%d error=%s\n",errno,strerror(errno));goto cleanup;}
 for(;;){pid_t w=waitpid(child,&status,WNOHANG|WUNTRACED);if(w==child)break;if(w<0&&errno!=EINTR){perror("TRACE_WAIT_FAILED");goto cleanup;}if(ms()>=deadline){puts("TRACE_STOP_TIMEOUT");goto cleanup;}usleep(10000);}
 if(!WIFSTOPPED(status)){puts("TRACE_UNEXPECTED_STATUS");goto cleanup;}
 unsigned long long regs[34]={0};struct iovec io={regs,sizeof(regs)};
 if(ptrace(PTRACE_GETREGSET,child,(void*)NT_PRSTATUS,&io)<0){printf("TRACE_REGS_FAILED errno=%d error=%s\n",errno,strerror(errno));goto cleanup;}
 printf("TRACE_REGS_READ bytes=%zu\n",io.iov_len);
 if(io.iov_len!=sizeof(regs)){puts("TRACE_REGS_SIZE_REJECTED");goto cleanup;}
 if(write(channel[1],"L",1)!=1){perror("CONTROL_WRITE_FAILED");goto cleanup;}
 close(channel[1]);channel[1]=-1;
 if(ptrace(PTRACE_DETACH,child,NULL,NULL)<0){perror("TRACE_DETACH_FAILED");goto cleanup;}
 puts("TRACE_DETACH_PASS");deadline=ms()+3000;
 for(;;){pid_t w=waitpid(child,&status,WNOHANG);
  if(w==child){if(WIFEXITED(status)){rc=WEXITSTATUS(status);printf("COOPERATIVE_CHILD_EXIT rc=%d\n",rc);}else if(WIFSIGNALED(status)){rc=125;printf("COOPERATIVE_CHILD_CRASH signal=%d\n",WTERMSIG(status));}child=-1;break;}
  if(w<0&&errno!=EINTR){perror("CHILD_WAIT_FAILED");break;}
  if(ms()>=deadline){puts("COOPERATIVE_CHILD_TIMEOUT");rc=124;break;}usleep(10000);
 }
 cleanup:
 if(channel[1]>=0)close(channel[1]);
 if(child>0){kill(child,SIGKILL);while(waitpid(child,&status,0)<0&&errno==EINTR){}}
 printf("TRACE_GATE_END rc=%d\n",rc);return rc;
}
