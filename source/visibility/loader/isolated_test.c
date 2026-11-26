// Isolated test harness for the loader.
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <time.h>
#include <errno.h>
#include <limits.h>
#include "crash_record.h"
#include "art_launch.h"
int cairn_probe_main(int argc,char **argv);
static long millis(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000L+t.tv_nsec/1000000;}
int main(int argc,char **argv){
 int art=argc==6&&!strcmp(argv[1],"--art-test");
 if(art){if(argv[2][0]!='/'||argv[3][0]!='/'||strcmp(argv[4],"--log")||argv[5][0]!='/')return 2;}
 else if((argc!=3&&argc!=5)||strcmp(argv[1],"--selftest")||argv[2][0]!='/'||(argc==5&&(strcmp(argv[3],"--log")||argv[4][0]!='/'))){fprintf(stderr,"Usage: cnd.a --selftest /payload.so [--log /new.log] or --art-test /classes.dex /jni.so --log /new.log\n");return 2;}
 char path[PATH_MAX];int n=snprintf(path,sizeof(path),"%s.%ld.%ld.log",argv[2],(long)getpid(),millis());
 if(n<0||(size_t)n>=sizeof(path))return 12;
 const char *logpath=art?argv[5]:(argc==5?argv[4]:path);
 int log=open(logpath,O_WRONLY|O_CREAT|O_EXCL|O_APPEND|O_CLOEXEC|O_NOFOLLOW,0600);
 if(log<0){perror("LOG_OPEN_FAILED");return 12;}
 dprintf(log,"TEST_START monotonic_ms=%ld parent_pid=%ld\n",millis(),(long)getpid());
 if(fsync(log)<0){perror("LOG_SYNC_FAILED");close(log);return 12;}
 printf("TEST_LOG path=%s\n",logpath);fflush(NULL);
 pid_t child=fork();if(child<0){dprintf(log,"FORK_FAILED errno=%d\n",errno);fsync(log);close(log);return 10;}
 if(child==0){
  if(dup2(log,STDOUT_FILENO)<0||dup2(log,STDERR_FILENO)<0)_exit(13);
  close(log);setvbuf(stdout,NULL,_IONBF,0);setvbuf(stderr,NULL,_IONBF,0);
  if(art)cairn_art_exec(argv,logpath);
  if(cairn_install_crash_record()<0){perror("CRASH_RECORD_INSTALL_FAILED");_exit(14);}
  printf("CHILD_LOAD_START pid=%ld\n",(long)getpid());
  int rc=cairn_probe_main(3,argv);fflush(NULL);_exit(rc);
 }
 dprintf(log,"CHILD_CREATED pid=%ld\n",(long)child);fsync(log);
 long end=millis()+(art?10000:3000);int status=0,timeout=0,rc=126;
 for(;;){pid_t result=waitpid(child,&status,WNOHANG);
  if(result==child)break;
  if(result<0&&errno!=EINTR){dprintf(log,"WAIT_FAILED errno=%d\n",errno);kill(child,SIGKILL);while(waitpid(child,&status,0)<0&&errno==EINTR){}rc=11;goto done;}
  if(millis()>=end){timeout=1;kill(child,SIGKILL);pid_t w;do{w=waitpid(child,&status,0);}while(w<0&&errno==EINTR);if(w!=child){rc=11;goto done;}break;}
  usleep(10000);
 }
 if(timeout){dprintf(log,"CHILD_TIMEOUT_REAPED\n");puts("CHILD_TIMEOUT_REAPED");rc=124;}
 else if(WIFSIGNALED(status)){dprintf(log,"CHILD_CRASH signal=%d\n",WTERMSIG(status));printf("CHILD_CRASH signal=%d\n",WTERMSIG(status));rc=125;}
 else if(WIFEXITED(status)){rc=WEXITSTATUS(status);dprintf(log,"ISOLATED_CHILD_EXIT rc=%d\n",rc);printf("ISOLATED_CHILD_EXIT rc=%d\n",rc);}
 done:
 dprintf(log,"TEST_END monotonic_ms=%ld rc=%d\n",millis(),rc);
 if(fsync(log)<0){perror("LOG_SYNC_FAILED");if(rc==0)rc=12;}close(log);return rc;
}
