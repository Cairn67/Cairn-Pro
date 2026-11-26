// SPDX-License-Identifier: GPL-2.0-only
// Payload layout constants shared with the assembler.
#ifndef CAIRN_LAYOUT_H
#define CAIRN_LAYOUT_H

#define CA_TASK_CRED        2080
#define CA_TASK_NAMEIDATA   2112

#define CA_CRED_UID         8
#define CA_CRED_EUID        24
#define CA_CRED_FSUID       32
#define CA_CRED_CAP_EFF     64
#define CA_CRED_SECURITY    128

#define CA_FILE_F_PATH      168
#define CA_FILE_F_INODE     184
#define CA_FILE_F_MODE      20

#define CA_INODE_SECURITY   56

#define CA_DENTRY_INODE     48

#define CA_NAMEI_PATH       0
#define CA_NAMEI_FLAGS      56

#define CA_PATH_DENTRY      8

#define CA_AUDIT_U_FILE     8

#define CA_DENTRY_NAME      32
#define CA_DENTRY_INAME     56
#define CA_QSTR_LEN         4
#define CA_QSTR_NAME        8
#endif
