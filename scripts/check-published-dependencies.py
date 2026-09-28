#!/usr/bin/env python3
"""Pin the PUBLISHED dependency set of `io.github.lazily:lazily` and prove the pin measures.

This binding had no dependency floor (#lzktoptionaldeps). Four `implementation`
entries sat unconditionally in `dependencies { }`, so the published POM carried
all four at runtime scope and every consumer of a reactive-signals library
resolved protobuf-kotlin — and through it protobuf-java, ~1.8 MB of wire codec —
to use `Context`/`Cell`/`Effect`. Exactly ONE file imported the generated
protobuf types, and nothing in `src/main` referenced that file.

Reading `build.gradle.kts` would not have caught it, and will not catch the next
one. What a consumer resolves is not the text of a dependency block: it is the
`<dependencies>` of the generated POM and the variants of the generated Gradle
Module Metadata, both of which are derived through plugin behaviour (the Kotlin
plugin's implicit `kotlin-stdlib`, the `-jvm` artifact names Gradle substitutes
for the multiplatform coordinates, the `<optional>` marker a feature variant
produces). So this guard reads the two GENERATED documents.

Both are measured, because each closes a hole the other leaves open:

  1. POM `<dependencies>`  — what a MAVEN consumer resolves. Non-optional entries
     must equal ALLOWED_POM_DEFAULT exactly, and `<optional>true</optional>`
     entries must equal GATED_POM_OPTIONAL exactly. Both directions: a new
     unconditional dependency reddens the first, and a pin for something nothing
     declares reddens it too.

  2. module.json variants — what a GRADLE consumer resolves. The default
     `runtimeElements` variant must equal ALLOWED_MODULE_RUNTIME exactly, and the
     variant carrying each capability in GATED_MODULE_RUNTIME_BY_CAPABILITY
     must equal that capability's pinned graph exactly.

(2) is not decoration. `<optional>true</optional>` is fifteen characters of XML;
a POM-only guard is satisfied by a dependency marked optional in a POM that no
feature variant stands behind, which is a Maven consumer protected and a Gradle
consumer still resolving the codec — and it is satisfied just as happily by a
feature variant whose capability was renamed out from under the documented
coordinate, because the POM does not carry capabilities at all. So the gated
dependency is required to appear under a variant that carries the exact
capability string consumers are told to ask for, and that variant is required to
publish at least one file, so an empty shell cannot stand in for the codec.

Equally, (1) is not redundant given (2): module metadata is ignored by Maven
entirely, so a regression that only moved the POM would pass a metadata-only
check while every Maven consumer kept downloading protobuf.

Neither half reads an exit status alone, and neither trusts a document it could
not parse: a measurement that produced no dependencies at all is a REFUSAL
(exit 2), never a satisfied set-equality over nothing.

Exit 0 when every pin holds, 1 when one is violated, 2 on refusal.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

POM_PATH = ROOT / "build" / "publications" / "gpr" / "pom-default.xml"
MODULE_PATH = ROOT / "build" / "publications" / "gpr" / "module.json"

POM_NS = "{http://maven.apache.org/POM/4.0.0}"

# The publication tasks that write the two documents above. Both are named
# explicitly rather than relying on `publish` or `build` to pull them in: a task
# that stopped being wired would otherwise leave last run's files on disk and
# this guard would pin a stale publication (the #lzstalemanifest shape).
GENERATE_TASKS = (
    "generatePomFileForGprPublication",
    "generateMetadataFileForGprPublication",
)

# --- the pins -------------------------------------------------------------

# Every dependency a MAVEN consumer of io.github.lazily:lazily resolves.
#
# kotlin-stdlib is here because the Kotlin plugin adds it and there is no
# version of this library without it; coroutines-core backs the async plane
# (AsyncContext.kt, Context.kt).
#
# JNA is NO LONGER here (#lzktoptionaljna). It backed LazilyFFI.kt alone, and at
# ~1.9 MB — the jar embeds native stubs for every supported platform — it was the
# largest thing every consumer downloaded for a binding most never call.
#
# kotlinx-serialization-json is CORE here and deliberately not gated. It is not
# "the JSON codec": nine main-source files use it, including StateChart.kt's
# `ChartDef.fromJson` (the Harel chart parser named in this module's own POM
# description), Ipc.kt, Command.kt, Capability.kt, Receipt.kt and Signaling.kt.
# Gating it would mean splitting the wire plane out of the library, which is a
# different change from removing a dependency nothing in the default surface
# uses. Recorded here so the next reader does not have to re-derive it.
#
# Gradle substitutes the `-jvm` artifacts for the multiplatform coordinates
# declared in build.gradle.kts, so these are the artifactIds as PUBLISHED, which
# is the point of reading the generated document.
ALLOWED_POM_DEFAULT = frozenset(
    {
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
    }
)

# Dependencies that must reach the POM ONLY as optional feature-variant entries.
# Non-empty by construction: see require_pins_measure().
GATED_POM_OPTIONAL = frozenset(
    {
        "com.google.protobuf:protobuf-kotlin",
        "net.java.dev.jna:jna",
    }
)

# The same two sets as a GRADLE consumer sees them. The coordinates differ from
# the POM's on purpose — module metadata records what was declared
# (`kotlinx-coroutines-core`), the POM records what Gradle resolved
# (`kotlinx-coroutines-core-jvm`) — and writing one set for both would be a pin
# that quietly stopped describing one of the two documents.
ALLOWED_MODULE_RUNTIME = frozenset(
    {
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        "org.jetbrains.kotlinx:kotlinx-serialization-json",
    }
)

# The capabilities consumers are documented to request, each mapped to the
# runtime graph its variant must publish. Pinned as exact strings: Gradle derives
# the default capability from the PROJECT name (`lazily-kt`) rather than the
# published artifactId (`lazily`), so build.gradle.kts overrides both, and an
# override that silently reverted would leave every documented consumer snippet
# resolving nothing.
#
# This is a MAP rather than one capability and one set because the second feature
# (#lzktoptionaljna) would otherwise be invisible here: with a single pin, a
# publication that gated jna under a variant nobody named, or under the codec's
# capability, would still satisfy every comparison. Each capability is measured
# against its OWN graph, so the two cannot stand in for each other.
#
# kotlin-stdlib appears in each because a feature variant compiles Kotlin. That
# is not a leak: the consumer already has it from the main variant.
GATED_MODULE_RUNTIME_BY_CAPABILITY = {
    "io.github.lazily:lazily-protobuf-codec": frozenset(
        {
            "com.google.protobuf:protobuf-kotlin",
            "org.jetbrains.kotlin:kotlin-stdlib",
        }
    ),
    "io.github.lazily:lazily-ffi": frozenset(
        {
            "net.java.dev.jna:jna",
            "org.jetbrains.kotlin:kotlin-stdlib",
        }
    ),
}

# The default variant the Java plugin publishes for runtime consumers.
DEFAULT_RUNTIME_VARIANT = "runtimeElements"


class GuardRefusal(RuntimeError):
    """The measurement itself cannot be trusted, so nothing is asserted from it."""


# --- measurement ----------------------------------------------------------


def generate_publication(runner=None) -> None:
    """Regenerate both published documents, or refuse."""
    argv = ["./gradlew", *GENERATE_TASKS, "--console=plain", "-q"]
    if runner is None:
        completed = subprocess.run(
            argv, cwd=str(ROOT), capture_output=True, text=True, check=False
        )
        code, err = completed.returncode, completed.stderr
    else:
        code, err = runner(argv)
    if code != 0:
        tail = err.strip().splitlines()[-1] if err.strip() else ""
        raise GuardRefusal(
            f"`{' '.join(argv)}` exited {code}; the publication was not generated"
            + (f": {tail}" if tail else "")
        )


def measure_pom(path: Path) -> tuple[frozenset[str], frozenset[str]]:
    """Return (non-optional, optional) `group:artifact` sets from the generated POM."""
    if not path.is_file():
        raise GuardRefusal(f"{path} does not exist; the POM was not measured")
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise GuardRefusal(f"{path} is not parseable XML: {exc}") from exc

    block = root.find(f"{POM_NS}dependencies")
    if block is None:
        raise GuardRefusal(
            f"{path} has no <dependencies> element; there is nothing to compare "
            "and an empty set must not satisfy a pin"
        )

    default: set[str] = set()
    gated: set[str] = set()
    for dep in block.findall(f"{POM_NS}dependency"):
        group = dep.findtext(f"{POM_NS}groupId")
        artifact = dep.findtext(f"{POM_NS}artifactId")
        if not group or not artifact:
            raise GuardRefusal(
                f"{path} contains a <dependency> without groupId/artifactId; "
                "the document is not the shape this guard reads"
            )
        optional = (dep.findtext(f"{POM_NS}optional") or "").strip() == "true"
        (gated if optional else default).add(f"{group}:{artifact}")

    if not default and not gated:
        raise GuardRefusal(
            f"{path} has an empty <dependencies> block; a publication with no "
            "dependencies at all is a broken measurement, not a clean graph"
        )
    return frozenset(default), frozenset(gated)


def measure_module(
    path: Path,
) -> tuple[frozenset[str], dict[str, frozenset[str]], dict[str, int]]:
    """Return (default runtime deps, per-capability gated deps, per-capability files)."""
    if not path.is_file():
        raise GuardRefusal(f"{path} does not exist; the module metadata was not measured")
    try:
        module = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        raise GuardRefusal(f"{path} is not parseable JSON: {exc}") from exc

    variants = module.get("variants")
    if not variants:
        raise GuardRefusal(f"{path} declares no variants; there is nothing to compare")

    def coords(variant: dict) -> frozenset[str]:
        return frozenset(
            f"{dep['group']}:{dep['module']}" for dep in variant.get("dependencies", [])
        )

    default_variants = [v for v in variants if v.get("name") == DEFAULT_RUNTIME_VARIANT]
    if len(default_variants) != 1:
        raise GuardRefusal(
            f"{path} has {len(default_variants)} variants named "
            f"{DEFAULT_RUNTIME_VARIANT!r}; expected exactly one to measure"
        )

    gated: dict[str, frozenset[str]] = {}
    gated_files: dict[str, int] = {}
    for capability in GATED_MODULE_RUNTIME_BY_CAPABILITY:
        gated_variants = [
            v
            for v in variants
            if any(
                f"{cap['group']}:{cap['name']}" == capability
                for cap in v.get("capabilities", [])
            )
            # The sources variant carries the capability too and has no dependencies;
            # the runtime variant is the one that decides what a consumer downloads.
            and v.get("name", "").endswith("RuntimeElements")
        ]
        if len(gated_variants) != 1:
            raise GuardRefusal(
                f"{path} has {len(gated_variants)} runtime variants carrying capability "
                f"{capability!r}; expected exactly one. The capability is what a Gradle "
                "consumer asks for, so measuring the gated graph without it would be "
                "measuring some other variant."
            )
        variant = gated_variants[0]
        gated[capability] = coords(variant)
        gated_files[capability] = len(variant.get("files", []))

    return coords(default_variants[0]), gated, gated_files


# --- assertions -----------------------------------------------------------


def require_pins_measure() -> list[str]:
    """Refuse pins that cannot fail. Checked before any document is read."""
    problems = []
    if not GATED_POM_OPTIONAL:
        problems.append(
            "GATED_POM_OPTIONAL is empty — the optional half would be satisfied by a "
            "publication that gated nothing, which is the state this guard exists to refuse"
        )
    if not GATED_MODULE_RUNTIME_BY_CAPABILITY:
        problems.append(
            "GATED_MODULE_RUNTIME_BY_CAPABILITY names no capability — with no feature "
            "pinned, a publication that gated nothing would satisfy the module half"
        )
    for capability, pinned in GATED_MODULE_RUNTIME_BY_CAPABILITY.items():
        if not pinned:
            problems.append(f"{capability} pins an empty runtime graph — same vacuity")
    # Every gated coordinate must be gated by exactly one feature. Two features
    # pinned for the same dependency means neither variant's pin can fail on it:
    # the dependency would be found wherever it was published.
    seen: dict[str, str] = {}
    for capability, pinned in GATED_MODULE_RUNTIME_BY_CAPABILITY.items():
        for coord in pinned - {"org.jetbrains.kotlin:kotlin-stdlib"}:
            if coord in seen:
                problems.append(
                    f"{coord} is pinned under both {seen[coord]} and {capability}, so "
                    "neither capability's graph can fail on it"
                )
            seen[coord] = capability
    if not ALLOWED_POM_DEFAULT:
        problems.append("ALLOWED_POM_DEFAULT is empty — an empty pin describes nothing")
    overlap = GATED_POM_OPTIONAL & ALLOWED_POM_DEFAULT
    if overlap:
        problems.append(
            "these coordinates are pinned as BOTH default and gated, so neither "
            f"direction can fail on them: {sorted(overlap)}"
        )
    for capability, pinned in GATED_MODULE_RUNTIME_BY_CAPABILITY.items():
        overlap = (pinned & ALLOWED_MODULE_RUNTIME) - {"org.jetbrains.kotlin:kotlin-stdlib"}
        if overlap:
            problems.append(
                f"these module coordinates are pinned as both default and gated under "
                f"{capability}: {sorted(overlap)}"
            )
    return problems


def compare(label: str, measured: frozenset[str], pinned: frozenset[str]) -> list[str]:
    """Set equality in BOTH directions, reported by name."""
    failures = []
    for coord in sorted(measured - pinned):
        failures.append(
            f"{label}: {coord} is published but not pinned — a new dependency "
            "reached consumers without passing this guard"
        )
    for coord in sorted(pinned - measured):
        failures.append(
            f"{label}: {coord} is pinned but not published — the pin describes a "
            "dependency that no longer exists, so it was asserting nothing"
        )
    return failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skip-generate",
        action="store_true",
        help="read the already-generated publication instead of re-running Gradle",
    )
    args = parser.parse_args(argv)

    problems = require_pins_measure()
    if problems:
        for problem in problems:
            print(f"check-published-dependencies: REFUSED: {problem}", file=sys.stderr)
        return 2

    try:
        if not args.skip_generate:
            generate_publication()
        pom_default, pom_gated = measure_pom(POM_PATH)
        module_default, module_gated, gated_files = measure_module(MODULE_PATH)
    except GuardRefusal as refusal:
        print(f"check-published-dependencies: REFUSED: {refusal}", file=sys.stderr)
        return 2

    failures: list[str] = []
    failures += compare("POM default (runtime/compile scope)", pom_default, ALLOWED_POM_DEFAULT)
    failures += compare("POM optional (feature variant)", pom_gated, GATED_POM_OPTIONAL)
    failures += compare(
        f"module {DEFAULT_RUNTIME_VARIANT}", module_default, ALLOWED_MODULE_RUNTIME
    )
    for capability, pinned in sorted(GATED_MODULE_RUNTIME_BY_CAPABILITY.items()):
        failures += compare(
            f"module {capability} runtime", module_gated[capability], pinned
        )

        # A capability with no artifact behind it satisfies every set comparison
        # above while shipping a consumer nothing to call.
        if gated_files[capability] == 0:
            failures.append(
                f"module {capability} runtime: the variant publishes no files — the "
                "capability is an empty shell, so a consumer that requested it would "
                "resolve the dependency and none of the code that needs it"
            )

    if failures:
        for failure in failures:
            print(f"check-published-dependencies: {failure}", file=sys.stderr)
        print(
            "check-published-dependencies: the published dependency graph moved. "
            "If the move is intended, update the pins in this file in the same "
            "commit — and say in the message which consumers gain or lose a "
            "download.",
            file=sys.stderr,
        )
        return 1

    gated_summary = ", ".join(
        f"{capability} ({gated_files[capability]} file(s))"
        for capability in sorted(GATED_MODULE_RUNTIME_BY_CAPABILITY)
    )
    print(
        "check-published-dependencies: OK — "
        f"{len(pom_default)} default POM dependencies, "
        f"{len(pom_gated)} gated across {len(GATED_MODULE_RUNTIME_BY_CAPABILITY)} "
        f"feature variant(s): {gated_summary}; both documents pinned in both directions"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
