/* SPDX-License-Identifier: GPL-2.0-only
 * Shared policy ingestion core. The kernel adapter supplies safe user-copy,
 * allocation, reader synchronization and reclamation; this header does not
 * provide VFS hooks. Publish only after cpr_prepare() succeeds.
 */
// Rule engine for path visibility decisions.
#ifndef CAIRN_PATH_ENGINE_H
#define CAIRN_PATH_ENGINE_H
#include "path_rules.h"
#define CPR_EINVAL (-22)
#define CPR_EFAULT (-14)
#define CPR_EPERM (-1)
struct cpr_request_identity { unsigned int uid, euid; int authenticated; };
typedef int (*cpr_copy_fn)(void *destination, const void *source, unsigned long bytes);

static int cpr_prepare(struct cpr_table *destination,
                       const void *user_data, unsigned long bytes,
                       struct cpr_request_identity identity, cpr_copy_fn copy) {
    if (identity.uid || identity.euid || !identity.authenticated)return CPR_EPERM;
    if (!destination || !user_data || !copy || bytes!=sizeof(*destination))return CPR_EINVAL;
    if (copy(destination,user_data,bytes))return CPR_EFAULT;
    if (cpr_validate(destination))return CPR_EINVAL;
    return 0;
}
#endif
