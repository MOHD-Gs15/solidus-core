#!/usr/bin/env python3
"""api_contract_guard.py — the solidus-api drift guard for the family drill.

Background (Task C design-gap sweep): the companions compile against a
COMMITTED compileOnly jar (libs/solidus-api-*.jar) while Core ships its own
freshly built solidus-api nested under META-INF/jars/. Nothing in CI noticed
if the shipped contract drifted from the jars the companions compiled
against — the failure mode is a silent NoSuchMethodError / NoClassDefFound-
Error on a live server, invisible to green CI, caught only by players.

This guard compares the shipped nested api jar with every companion's libs
jar at two levels:

  1. CLASS SET — a class present on one side and missing on the other is a
     HARD FAIL (missing linkage is a guaranteed runtime error).
  2. PER-CLASS SIGNATURES via `javap -p` — member-level drift is a HARD
     FAIL (that IS the contract). Signatures are compared instead of raw
     .class SHA-256 so a pure recompile (a newer javac patch producing
     different bytecode from unchanged sources) does NOT false-red the
     drill; raw-byte drift with identical signatures is reported as an
     informational NOTE instead.

Usage: api_contract_guard.py <core-jar> [<companion-libs-dir> ...]
Exit:  0 = contract identical across every libs jar, 1 = drift detected.
"""

import hashlib
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


def fail(msg: str) -> None:
    print(f"::error::{msg}")
    sys.exit(1)


def class_entries(jar: Path):
    """FQCN -> entry name for every top-level and nested class in the jar."""
    classes = {}
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if not name.endswith(".class"):
                continue
            if name.endswith("module-info.class") or name.endswith("package-info.class"):
                continue
            fqcn = name[:-len(".class")].replace("/", ".")
            classes[fqcn] = name
    return classes


def entry_sha256(jar: Path, entry: str) -> str:
    with zipfile.ZipFile(jar) as zf:
        return hashlib.sha256(zf.read(entry)).hexdigest()


def javap_signature(jar: Path, fqcn: str) -> str:
    """Digest of the full `javap -p` member declaration surface."""
    out = subprocess.run(
        ["javap", "-p", "-cp", str(jar), fqcn],
        capture_output=True, text=True, check=True)
    return hashlib.sha256(out.stdout.encode("utf-8")).hexdigest()


def load_nested_api(core_jar: Path, tmp: Path) -> Path:
    """Extract the single nested solidus-api jar Core ships."""
    with zipfile.ZipFile(core_jar) as zf:
        nested = [n for n in zf.namelist()
                  if n.startswith("META-INF/jars/solidus-api-") and n.endswith(".jar")]
        if len(nested) != 1:
            fail(f"expected exactly one META-INF/jars/solidus-api-*.jar inside "
                 f"{core_jar.name}, found {len(nested)}: {nested}")
        target = tmp / Path(nested[0]).name
        with zf.open(nested[0]) as src, open(target, "wb") as dst:
            shutil.copyfileobj(src, dst)
    return target


def compare(shipped: Path, other: Path, other_label: str):
    """Returns (hard_failures, soft_notes)."""
    failures, notes = [], []
    shipped_classes = class_entries(shipped)
    other_classes = class_entries(other)

    only_shipped = sorted(set(shipped_classes) - set(other_classes))
    only_other = sorted(set(other_classes) - set(shipped_classes))
    for fqcn in only_shipped:
        failures.append(f"{other_label}: class {fqcn} is shipped by Core but MISSING "
                        f"from the compileOnly jar the companion compiled against")
    for fqcn in only_other:
        failures.append(f"{other_label}: class {fqcn} exists in the compileOnly jar "
                        f"but the shipped api no longer carries it")

    for fqcn in sorted(set(shipped_classes) & set(other_classes)):
        sig_shipped = javap_signature(shipped, fqcn)
        sig_other = javap_signature(other, fqcn)
        if sig_shipped != sig_other:
            failures.append(f"{other_label}: {fqcn} signatures DRIFTED between the "
                            f"compileOnly jar and the shipped api (run "
                            f"`javap -p -cp <jar> {fqcn}` on both to see the diff)")
        elif entry_sha256(shipped, shipped_classes[fqcn]) != \
                entry_sha256(other, other_classes[fqcn]):
            notes.append(f"{other_label}: {fqcn} bytecode differs but signatures are "
                         f"identical (pure recompile — informational, not a break)")
    return failures, notes


def main() -> None:
    if len(sys.argv) < 3:
        fail("usage: api_contract_guard.py <core-jar> [<companion-libs-dir> ...]")

    core_jar = Path(sys.argv[1])
    if not core_jar.is_file():
        fail(f"core jar not found: {core_jar}")

    libs_dirs = [Path(a) for a in sys.argv[2:]]
    for d in libs_dirs:
        if not d.is_dir():
            fail(f"companion libs dir not found: {d}")

    all_failures, all_notes = [], []
    with tempfile.TemporaryDirectory() as tmp:
        shipped = load_nested_api(core_jar, Path(tmp))
        shipped_classes = class_entries(shipped)
        print(f"shipped api: {shipped.name} ({len(shipped_classes)} classes)")

        for d in libs_dirs:
            jars = sorted(d.glob("solidus-api-*.jar"))
            if len(jars) != 1:
                all_failures.append(
                    f"{d}: expected exactly one solidus-api-*.jar, found "
                    f"{[j.name for j in jars]}")
                continue
            failures, notes = compare(shipped, jars[0], f"{d.parent.name}/libs/{jars[0].name}")
            all_failures += failures
            all_notes += notes
            if not failures:
                print(f"  OK   {d.parent.name}/libs/{jars[0].name}: contract identical "
                      f"({len(class_entries(jars[0]))} classes, signatures match)")

    for n in all_notes:
        print(f"  note {n}")
    if all_failures:
        for f in all_failures:
            print(f"  FAIL {f}")
        fail("the shipped solidus-api drifted from a companion compileOnly libs jar — "
             "refresh libs/ in the companions or revert the contract change "
             "(silent NoSuchMethodError otherwise)")
    print("solidus-api contract guard: GREEN — shipped api matches every "
          "companion compileOnly jar, member for member.")


if __name__ == "__main__":
    main()
