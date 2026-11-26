// SPDX-License-Identifier: GPL-2.0-only
// Credential-domain adapter for validated kernel layouts; not wired into the payload.
struct cr_domain_adapter {
 int (*context_to_sid)(const char *,unsigned int,unsigned int *);
 unsigned long cred_security_offset;
 long selinux_cred_offset;
 unsigned int selinux_cred_size;
};
long cr_set_shell_domain(void *new_cred,const void *old_cred,
                        const struct cr_domain_adapter *a){
 static const char context[]="u:r:shell:s0";
 if(!a || !a->context_to_sid || !new_cred || !old_cred || new_cred==old_cred)return -22;
 
 if(a->cred_security_offset!=128 || a->selinux_cred_offset<0 ||
    a->selinux_cred_offset>4096 || a->selinux_cred_size!=24)return -22;
 unsigned int sid=0;
 int rc=a->context_to_sid(context,sizeof(context)-1,&sid);
 if(rc)return rc;
 if(!sid)return -22;
 unsigned char *next=*(unsigned char **)((unsigned char *)new_cred+128);
 const unsigned char *old=*(unsigned char *const *)((const unsigned char *)old_cred+128);
 if(!next || !old || next==old)return -22;
 unsigned int *security=(unsigned int *)(next+a->selinux_cred_offset);
 
 security[1]=sid;
 security[2]=0;security[3]=0;security[4]=0;security[5]=0;
 return 0;
}
