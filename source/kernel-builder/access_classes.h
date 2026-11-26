/* SPDX-License-Identifier: GPL-2.0-only
 * Verified secclass_map for the supported device kernel. Shared by cnd/kernel.
 * Class IDs are kernel IDs, never policydb IDs. */
// Access rule class definitions.
#ifndef CAIRN_ACCESS_CLASSES_H
#define CAIRN_ACCESS_CLASSES_H
struct cav_class { unsigned int id; char name[24]; char perms[32][24]; };
static const struct cav_class cav_classes[]={
 {6,"filesystem",{"mount","remount","unmount","getattr","relabelfrom","relabelto","associate","quotamod","quotaget","watch"}},
 {7,"file",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","unlink","link","rename","execute","quotaon","mounton","audit_access","open","execmod","watch","watch_mount","watch_sb","watch_with_perm","watch_reads","execute_no_trans","entrypoint"}},
 {8,"dir",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","unlink","link","rename","execute","quotaon","mounton","audit_access","open","execmod","watch","watch_mount","watch_sb","watch_with_perm","watch_reads","add_name","remove_name","reparent","search","rmdir"}},
 {9,"fd",{"use"}},
 {11,"chr_file",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","unlink","link","rename","execute","quotaon","mounton","audit_access","open","execmod","watch","watch_mount","watch_sb","watch_with_perm","watch_reads"}},
 {13,"sock_file",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","unlink","link","rename","execute","quotaon","mounton","audit_access","open","execmod","watch","watch_mount","watch_sb","watch_with_perm","watch_reads"}},
 {14,"fifo_file",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","unlink","link","rename","execute","quotaon","mounton","audit_access","open","execmod","watch","watch_mount","watch_sb","watch_with_perm","watch_reads"}},
 {24,"unix_stream_socket",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","bind","connect","listen","accept","getopt","setopt","shutdown","recvfrom","sendto","name_bind","connectto"}},
 {25,"unix_dgram_socket",{"ioctl","read","write","create","getattr","setattr","lock","relabelfrom","relabelto","append","map","bind","connect","listen","accept","getopt","setopt","shutdown","recvfrom","sendto","name_bind"}},
};
#define CAV_CLASSES (sizeof(cav_classes)/sizeof(cav_classes[0]))
static const struct cav_class *cav_class_id(unsigned int id){for(unsigned int i=0;i<CAV_CLASSES;i++)if(cav_classes[i].id==id)return &cav_classes[i];return (void *)0;}
static unsigned int cav_valid_mask(unsigned int id){const struct cav_class *c=cav_class_id(id);if(!c)return 0;unsigned int mask=0;for(unsigned int i=0;i<32&&c->perms[i][0];i++)mask|=1U<<i;return mask;}
#endif
