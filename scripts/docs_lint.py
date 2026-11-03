#!/usr/bin/env python3
"""docs_lint.py — documentation-vs-reality lint for solidus-core.

Born from WS-7: the docs shipped facts that drifted from the code
(version badges 4 releases behind, test counts from 3 versions ago, a
mocking framework that was never used, a CI description missing the
service containers). This script makes that class of drift fail loudly.

Checks (all pure Python — no Java, no Docker):
  1. mod_version in gradle.properties == README version badge
                                     == README jar filename
                                     == ARCHITECTURE.md header badge
  2. Test-class count quoted in ARCHITECTURE.md §18 and in README
     == number of *Test.java files under src/test
  3. Static @Test-declaration count quoted in ARCHITECTURE.md §18 and
     in README == lexer-counted @Test annotations (comments/strings
     stripped; the abstract contract harness counted once)
  4. "Mockito" must not appear in ARCHITECTURE.md at all (the suite is
     JUnit-5-only; a bare mention is how the last drift shipped)
  5. notes/CHANGES.md must cover a version >= mod_version

Usage:  python3 scripts/docs_lint.py            # from anywhere
Exit:   0 = green, 1 = drift found.

NOTE: the CI-recorded runtime total (462 at the time of writing) is
deliberately NOT linted — static analysis cannot reproduce it. Lint
enforces the two numbers it can prove; release notes own the rest.
"""

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


# ── Java lexer: strip comments and string/char literals, then count ──
def strip_java_noncode(src: str) -> str:
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            while i < n and src[i] != '\n':
                i += 1
        elif c == '/' and nxt == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                i += 1
            i += 2
        elif c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == '\\' else 1
            i += 1
            out.append("''")
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def read(rel: str) -> str:
    return (REPO / rel).read_text(encoding='utf-8', errors='replace')


def semver_tuple(v: str):
    return tuple(int(p) for p in v.split('.'))


failures, notes = [], []


def check(label, actual, expected):
    if actual == expected:
        notes.append(f"  OK   {label}: {actual}")
    else:
        failures.append(
            f"  FAIL {label}: found {actual!r} in the docs, "
            f"but reality (gradle.properties / src/test) says {expected!r}")


# ── 1. mod_version consistency ────────────────────────────────────────
gp = read('gradle.properties')
m = re.search(r'^mod_version\s*=\s*(\S+)\s*$', gp, re.M)
if not m:
    failures.append("  FAIL gradle.properties: no 'mod_version = X' line found")
    sys.exit(1)
mod_version = m.group(1)

readme = read('README.md')
arch = read('docs/ARCHITECTURE.md')

badge = re.search(r'img\.shields\.io/badge/version-([\w.]+)-blue', readme)
jar = re.search(r'`solidus-([\w.]+)\.jar`', readme)
arch_badge = re.search(r'\*\*Version\*\*:\s*([\w.]+)', arch)

check('gradle.properties mod_version', mod_version, mod_version)  # anchor
check('README version badge', badge.group(1) if badge else '<missing>', mod_version)
check('README jar filename', jar.group(1) if jar else '<missing>', mod_version)
check('ARCHITECTURE.md header badge', arch_badge.group(1) if arch_badge else '<missing>', mod_version)

# ── 2+3. test-class and @Test-declaration counts from src/test ────────
test_files = sorted((REPO / 'src' / 'test').rglob('*Test.java'))
class_count = len(test_files)

own = 0
abstract_with_tests = 0
for f in test_files:
    body = strip_java_noncode(f.read_text(encoding='utf-8', errors='replace'))
    n = len(re.findall(r'@Test\b(?!\w)', body))
    own += n
    if re.search(r'\babstract\s+class\s+\w+', body):
        abstract_with_tests += n

# documented numbers
arch_18 = arch.split('## 18. Testing Strategy', 1)[-1].split('## 19.', 1)[0]
arch_counts = re.search(
    r'\*\*(\d+) test classes,\s*(\d+) static `@Test` declarations\*\*', arch_18)
readme_counts = re.search(
    r'spans (\d+) test classes and (\d+) static `@Test` declarations', readme)

check('ARCHITECTURE §18 test-class count',
      int(arch_counts.group(1)) if arch_counts else -1, class_count)
check('ARCHITECTURE §18 @Test-declaration count',
      int(arch_counts.group(2)) if arch_counts else -1, own)
check('README test-class count',
      int(readme_counts.group(1)) if readme_counts else -1, class_count)
check('README @Test-declaration count',
      int(readme_counts.group(2)) if readme_counts else -1, own)
notes.append(f"  note  src/test: {class_count} *Test.java files, {own} @Test "
             f"declarations ({abstract_with_tests} inside abstract harness classes)")

# ── 4. no mocking framework named in ARCHITECTURE.md ─────────────────
hits = [ln for ln in arch.splitlines() if re.search(r'mockito', ln, re.I)]
if hits:
    failures.append(
        "  FAIL ARCHITECTURE.md mentions 'Mockito' (suite is JUnit-5-only) at line(s): "
        + ', '.join(str(arch.splitlines().index(h) + 1) for h in hits[:5]))
else:
    notes.append("  OK   ARCHITECTURE.md: no mocking-framework mentions")

# ── 5. CHANGES.md coverage vs mod_version ─────────────────────────────
changes = read('notes/CHANGES.md')
versions = [v.group(1) for v in re.finditer(r'^## (\d+\.\d+\.\d+)[ —-]', changes, re.M)]
if not versions:
    failures.append("  FAIL notes/CHANGES.md: no '## X.Y.Z' version headings found")
else:
    latest = max(versions, key=semver_tuple)
    check('CHANGES.md latest version', latest, mod_version)
    if semver_tuple(latest) < semver_tuple(mod_version):
        failures.append(
            f"  FAIL notes/CHANGES.md stops at {latest} but mod_version is "
            f"{mod_version} — write the release entry before bumping")

# ── verdict ───────────────────────────────────────────────────────────
print("docs_lint — documentation vs reality")
print('\n'.join(notes))
if failures:
    print('\n'.join(failures))
    print(f"\nRESULT: RED — {len(failures)} drift finding(s). Fix the docs or the counts, not the linter.")
    sys.exit(1)
print("\nRESULT: GREEN — docs match gradle.properties and src/test.")
