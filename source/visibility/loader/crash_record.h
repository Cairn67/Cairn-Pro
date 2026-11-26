// Records startup crashes for diagnosis.
#ifndef CAIRN_CRASH_RECORD_H
#define CAIRN_CRASH_RECORD_H
#include <stdint.h>
#include <ucontext.h>

static unsigned char cairn_signal_stack[65536];
static void crash_hex(const char *key,size_t len,uint64_t value){
 char out[128];size_t n=0;
 for(size_t i=0;i<len&&n<80;i++)out[n++]=key[i];
 out[n++]='=';out[n++]='0';out[n++]='x';
 for(int i=60;i>=0;i-=4)out[n++]="0123456789abcdef"[(value>>i)&15];
 out[n++]='\n';size_t sent=0;
 while(sent<n){ssize_t w=write(STDERR_FILENO,out+sent,n-sent);if(w>0)sent+=(size_t)w;else if(w<0&&errno==EINTR)continue;else break;}
}
#define CRASH_HEX(k,v) crash_hex(k,sizeof(k)-1,(uint64_t)(v))
static void cairn_crash_handler(int sig,siginfo_t *info,void *context){
 CRASH_HEX("CRASH_SIGNAL",sig);
 if(info){CRASH_HEX("CRASH_CODE",info->si_code);CRASH_HEX("FAULT_ADDRESS",(uintptr_t)info->si_addr);}
#if defined(__aarch64__)
 ucontext_t *uc=(ucontext_t*)context;
 if(uc){CRASH_HEX("PC",uc->uc_mcontext.pc);CRASH_HEX("SP",uc->uc_mcontext.sp);CRASH_HEX("PSTATE",uc->uc_mcontext.pstate);
 for(int i=0;i<31;i++){char key[3]={'X',(char)('0'+i/10),(char)('0'+i%10)};crash_hex(key,3,uc->uc_mcontext.regs[i]);}}
#else
 (void)context;
#endif
 
 if(kill(getpid(),sig)<0)_exit(128+sig);
}
static int cairn_install_crash_record(void){
 stack_t ss={0};ss.ss_sp=cairn_signal_stack;ss.ss_size=sizeof(cairn_signal_stack);
 if(sigaltstack(&ss,NULL)<0)return -1;
 const int signals[]={SIGSEGV,SIGBUS,SIGILL,SIGABRT,SIGFPE,SIGTRAP};
 struct sigaction action={0};action.sa_sigaction=cairn_crash_handler;action.sa_flags=SA_SIGINFO|SA_ONSTACK|SA_RESETHAND;sigemptyset(&action.sa_mask);
 for(size_t i=0;i<sizeof(signals)/sizeof(signals[0]);i++)if(sigaction(signals[i],&action,NULL)<0)return -1;
 return 0;
}
#endif
