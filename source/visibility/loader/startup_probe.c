// Probe that reports early startup behaviour.
#include <stdio.h>
#include <unistd.h>

__attribute__((constructor)) static void cairn_startup_probe(void){
 char line[96];int n=snprintf(line,sizeof(line),"CAIRN_STARTUP_PAYLOAD_ENTER pid=%ld\n",(long)getpid());
 if(n>0&&(size_t)n<sizeof(line)){ssize_t ignored=write(STDERR_FILENO,line,(size_t)n);(void)ignored;}
}
