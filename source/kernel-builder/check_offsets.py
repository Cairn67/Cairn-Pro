#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Fail if any struct offset the payload dereferences is still a bare literal.

layout.h exists so the 15 kernel struct offsets are named once and retargeted by
`loader/rebuild.py --layout`. That only holds if every dereference goes through a
name. A bare literal still compiles, still produces a plausible core, and only
shows up as the wrong field at runtime on a kernel whose layout differs -- the
most expensive possible failure mode. This check makes that mistake loud.

Run from the payload source directory. Exits non-zero and lists every offending
file:line, so it can gate a build.
"""
from pathlib import Path
import re
import sys

EXPECTED = 20

ACCESSORS = r'(?:ca_ptr|ca_u32|cv_ptr|cv_word)'

ARITH = r'\((?:const )?char \*\)[a-z_]+'

def layout_names(root: Path) -> set[str]:
    text = (root / 'layout.h').read_text()
    return set(re.findall(r'^#define\s+(CA_[A-Z_0-9]+)\s+\d+', text, re.M))

def offenders(root: Path, names: set[str]) -> list[str]:
    found: list[str] = []
    for path in sorted(root.glob('*.c')) + sorted(root.glob('*.S')):
        for lineno, line in enumerate(path.read_text().splitlines(), 1):
            code = line.split('//')[0]
            if code.lstrip().startswith('*') or code.lstrip().startswith('/*'):
                continue
            
            for m in re.finditer(ACCESSORS + r'\s*\(\s*[a-z_>.\-]+\s*,\s*(\d+)\s*\)', code):
                found.append(f'{path.name}:{lineno}: accessor with literal {m[1]}: {line.strip()}')
            
            for m in re.finditer(ARITH + r'\s*\+\s*(\d+)\b', code):
                found.append(f'{path.name}:{lineno}: pointer arithmetic with literal {m[1]}: {line.strip()}')
    return found

def main() -> int:
    root = Path(__file__).resolve().parent
    names = layout_names(root)
    if len(names) != EXPECTED:
        print(f'layout.h defines {len(names)} names, expected {EXPECTED}', file=sys.stderr)
        return 1
    bad = offenders(root, names)
    if bad:
        print('NAKED_OFFSETS ' + str(len(bad)), file=sys.stderr)
        for b in bad:
            print('  ' + b, file=sys.stderr)
        return 1
    print(f'OFFSET_NAMES_CLEAN sources=10 names={len(names)}')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())

