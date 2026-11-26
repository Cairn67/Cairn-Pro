/* SPDX-License-Identifier: GPL-2.0-only */
// Container format for the core payload.
#ifndef CAIRN_LOADER_FORMAT_H
#define CAIRN_LOADER_FORMAT_H
#define CL_MAGIC 0x434C4431U
#define CL_ABI 1U
#define CL_PROFILE 0x434E0001U
#define CL_LOAD 8UL
#define CL_STATUS 9UL
#define CL_LIMIT (1024U*1024U)
struct cl_header { unsigned int magic,abi,profile,bytes; unsigned char digest[32]; };
struct cl_state { unsigned int busy; int last_error; unsigned long base,bytes,loads; };
struct cl_fixup { unsigned int offset,bridge; };
struct cl_relative { unsigned int offset,target; };
#endif
