// ABI shared between the loader and its probes.
#ifndef CAIRN_VISIBILITY_LOADER_ABI_H
#define CAIRN_VISIBILITY_LOADER_ABI_H
#include <stdint.h>
#define CAIRN_VISIBILITY_ABI 1u
#define CAIRN_VISIBILITY_MAGIC UINT64_C(0x434149524e564953)

struct cairn_visibility_descriptor {
 uint64_t magic;
 uint32_t size;
 uint32_t abi;
 uint32_t pointer_size;
 uint32_t reserved;
};
typedef const struct cairn_visibility_descriptor *(*cairn_descriptor_fn)(void);

typedef int (*cairn_selftest_fn)(uint32_t abi);
#endif
