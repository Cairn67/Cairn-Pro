/* SPDX-License-Identifier: GPL-3.0-only
 * Optional diagnostic userspace loader for explicit one-shot loading.
 * Normal boot loading is performed by the matched bootstrap/initramfs path.
 * This utility does not install a service and never prints authentication keys.
 */
// Maps and verifies the core payload at load time.
#define _GNU_SOURCE
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "format.h"
static int hex(char c){if(c>='0'&&c<='9')return c-'0';if(c>='a'&&c<='f')return c-'a'+10;if(c>='A'&&c<='F')return c-'A'+10;return -1;}
int main(int argc,char **argv){
 if(argc!=3){fputs("Usage: cairn-load-core CORE.cld PRIVATE_KEY_FILE\nDiagnostic explicit load only; normal bootstrap boot loading is separate.\n",stderr);return 2;}
 int k=open(argv[2],O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);struct stat ks;
 if(k<0||fstat(k,&ks)||!S_ISREG(ks.st_mode)||(ks.st_mode&0077)||ks.st_size<32||ks.st_size>34){fputs("Invalid private key file or permissions\n",stderr);if(k>=0)close(k);return 3;}
 char text[35]={0};ssize_t n=read(k,text,34);close(k);if(n<32){fputs("Cannot read private key\n",stderr);return 3;}
 unsigned char key[16];unsigned int nonzero=0;
 for(int i=0;i<16;i++){int a=hex(text[2*i]),b=hex(text[2*i+1]);if(a<0||b<0){fputs("Invalid key format\n",stderr);return 3;}key[i]=(a<<4)|b;nonzero|=key[i];}
 for(int i=32;i<n;i++)if(text[i]!='\n'&&text[i]!='\r'){fputs("Invalid key length\n",stderr);return 3;}
 if(!nonzero){fputs("Zero key refused\n",stderr);return 3;}uint64_t words[2];memcpy(words,key,16);memset(text,0,sizeof(text));memset(key,0,sizeof(key));
 int fd=open(argv[1],O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);struct stat st;
 if(fd<0||fstat(fd,&st)||!S_ISREG(st.st_mode)||st.st_size<(off_t)sizeof(struct cl_header)||st.st_size>CL_LIMIT+(off_t)sizeof(struct cl_header)){fputs("Invalid core file\n",stderr);if(fd>=0)close(fd);return 4;}
 void *data=mmap(NULL,st.st_size,PROT_READ,MAP_PRIVATE,fd,0);close(fd);if(data==MAP_FAILED){perror("Map core");return 4;}
 struct cl_header h;memcpy(&h,data,sizeof(h));
 if(h.magic!=CL_MAGIC||h.abi!=CL_ABI||h.profile!=CL_PROFILE||st.st_size!=h.bytes+(off_t)sizeof(h)){fputs("Core header/size mismatch\n",stderr);munmap(data,st.st_size);return 4;}
 long result=syscall(SYS_prctl,0x43414952UL,words[0],words[1],CL_LOAD,(unsigned long)data);int error=errno;munmap(data,st.st_size);memset(words,0,sizeof(words));
 if(result){fprintf(stderr,"CORE_LOADED=false errno=%d\n",error);return 5;}
 puts("CORE_LOADED=true");return 0;
}
