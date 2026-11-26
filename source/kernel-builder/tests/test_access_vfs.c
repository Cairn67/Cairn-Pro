/* SPDX-License-Identifier: GPL-2.0-only
 * Synthetic struct-layout unit tests; real kernel ABI still requires VM/device tests.
 */
// Tests for the VFS access path.
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../access_runtime.h"
static struct ca_runtime runtime;
struct ca_runtime *ca_runtime_peek(void){return &runtime;}
#include "../access_backend.c"
#include "../access_vfs.c"
static unsigned long lk(void *p){(void)p;return 7;}
static void ul(void *p,unsigned long f){(void)p;assert(f==7);}
static int resolve_fail;
static char *resolve(const void *path,char *out,int size){
 (void)path;const char *p="/module/file";assert(size>32);
 if(resolve_fail)return (char *)(uintptr_t)-36;
 strcpy(out+8,p);return out+8;
}
static void pointer(void *base,unsigned int off,void *value){memcpy((char *)base+off,&value,sizeof(value));}
static void word(void *base,unsigned int off,unsigned int value){memcpy((char *)base+off,&value,4);}
int main(void){
 _Alignas(8) unsigned char task[2128]={0},cred[160]={0},file[224]={0},inode[64]={0},nd[80]={0},dentry[64]={0};
 struct car_table table={.magic=CAR_MAGIC,.version=1,.count=1};
 struct car_rule *rule=&table.rules[0];rule->uid=12001;rule->ops=CAR_READ|CAR_META;rule->length=7;strcpy(rule->path,"/module");assert(!car_validate(&table));
 runtime=(struct ca_runtime){.lock=lk,.unlock=ul,.active=&table,.resolve=resolve};
 pointer(task,2080,cred);word(cred,8,12001);word(cred,24,12001);word(cred,32,12001);pointer(file,184,inode);*(unsigned short *)inode=0x8000;word(file,20,1);
 assert(ca_file_allowed(file,task));
 assert(ca_file_data_allowed(file,2,task,0));
 assert(!ca_file_data_allowed(file,4,task,0));assert(!ca_file_data_allowed(file,0,task,0));
 assert(!ca_file_data_allowed(file,1,task,0));assert(!ca_file_data_allowed(file,0x40000,task,0));
 rule->ops|=CAR_WRITE;assert(ca_file_data_allowed(file,4,task,0));assert(ca_file_data_allowed(file,512,task,0));assert(ca_file_data_allowed(file,6,task,0));
 word(cred,32,12002);assert(!ca_file_allowed(file,task));word(cred,32,12001);
 word(cred,8,12002);word(cred,24,12002);word(cred,32,12002);assert(!ca_file_data_allowed(file,2,task,0));
 word(cred,8,12001);word(cred,24,12001);word(cred,32,12001);
 *(unsigned short *)inode=0xc000;assert(!ca_file_allowed(file,task));assert(!ca_file_data_allowed(file,2,task,0));*(unsigned short *)inode=0x8000;
 resolve_fail=1;assert(!ca_file_allowed(file,task));resolve_fail=0;
 table.count=0;assert(!ca_file_data_allowed(file,2,task,0));table.count=1;
 pointer(task,2112,nd);pointer(nd,8,dentry);pointer(dentry,48,inode);
 assert(ca_inode_allowed(inode,4,task)==1);assert(ca_inode_allowed(inode,0x84,task)==-10);
 word(nd,56,0x40);assert(!ca_inode_allowed(inode,4,task));word(nd,56,0);
 pointer(dentry,48,NULL);assert(!ca_inode_allowed(inode,4,task));
 assert(!ca_file_map_allowed(file,task));
  rule->ops|=CAR_MAP;assert(ca_file_map_allowed(file,task));
  assert(ca_file_data_allowed(file,2,task,1));assert(!ca_file_data_allowed(file,0x4002,task,1));
  rule->ops|=CAR_EXEC;assert(ca_file_data_allowed(file,0x4002,task,1));
  assert(!ca_file_data_allowed(file,0x4002,task,0));assert(!ca_file_data_allowed(file,0x80000,task,1));
  rule->ops&=~CAR_MAP;assert(!ca_file_data_allowed(file,2,task,1));
  rule->ops=CAR_MAP;assert(!ca_file_map_allowed(file,task));assert(!ca_file_data_allowed(file,2,task,1));
  rule->ops=CAR_READ|CAR_MAP;assert(ca_file_map_allowed(file,task));
  assert(ca_file_data_allowed(file,2,task,1));assert(!ca_file_data_allowed(file,6,task,1));
  assert(!ca_file_data_allowed(file,0x4002,task,1));assert(!ca_file_data_allowed(file,512,task,1));
  rule->ops|=CAR_WRITE|CAR_EXEC;assert(ca_file_data_allowed(file,0x4006,task,1));
  assert(!ca_file_data_allowed(file,0x4000,task,0));assert(!ca_file_data_allowed(file,0,task,1));
  assert(!ca_file_data_allowed(file,0x2000000,task,1));
  word(cred,32,12002);assert(!ca_file_map_allowed(file,task));assert(!ca_file_data_allowed(file,2,task,1));word(cred,32,12001);
  resolve_fail=1;assert(!ca_file_map_allowed(file,task));resolve_fail=0;
  *(unsigned short *)inode=0x4000;assert(!ca_file_map_allowed(file,task));assert(!ca_file_data_allowed(file,2,task,1));
  *(unsigned short *)inode=0xc000;assert(!ca_file_map_allowed(file,task));assert(!ca_file_data_allowed(file,2,task,1));
  *(unsigned short *)inode=0x8000;table.count=0;assert(!ca_file_map_allowed(file,task));table.count=1;
  puts("ACCESS_VFS_UNIT_PASS identity=1 operation_mask=1 unsupported_resource=1 resolver_failure=1 table_clear=1 RCU_retry=1 inode_identity=1 mmap_mask=1 mapping_provenance=1 regular_file_only=1");
 return 0;
}
