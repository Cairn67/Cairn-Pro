# Security policy

## Scope and intent

Cairn Pro is a **dual-use** security tool. It performs kernel-level privilege handling on a
device the operator owns. It is published for research and personal use on one's own
hardware.

It is **not** intended to compromise devices the operator does not control, and it does
not target any other software's licensing or activation checks.

Distribution scope: this repository publishes source to be read and studied. GitHub's
Acceptable Use Policies treat research into vulnerabilities and exploits as an accepted
use, and do not extend that treatment to package registries, where software is installed
and run rather than read. Do not republish this as an installable package on a registry.

## Reporting a concern

If you believe something in this repository:

- enables attacks on devices the operator does not own,
- contains a credential, key, or personal data,
- misuses a third party's intellectual property, or
- describes a vulnerability in another project,

please open an issue on this repository and mark the nature of the concern in the title.
You do not need to establish a private channel first — an issue keeps the discussion
visible and lets the maintainer respond quickly without requiring anyone to file a formal
abuse report.

For coordinated disclosure of a vulnerability **in Cairn Pro itself**, open an issue titled
`[security] ...` and avoid posting a working exploit for another project's code.

## What this repository will never contain

- plaintext authentication keys or a signing keystore;
- device-specific boot images, or any artifact with authentication material baked in;
- personal data of any user, contributor, or device owner.

If you find any of the above, report it immediately — it is a mistake, not a feature.

## Handling by maintainers

Concerns are triaged on the merits. Where content is judged to be genuinely harmful with
no legitimate research or personal-use value, it will be removed or the affected file
corrected. Where content is dual-use but legitimate, the fix is a clearer disclaimer or a
narrower default, not removal.
