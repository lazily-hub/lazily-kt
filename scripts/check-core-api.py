#!/usr/bin/env python3
"""Fail-closed Core API, deprecation, and module-range compatibility gate."""

from __future__ import annotations

import argparse
import copy
import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT_PATH = ROOT / "compat/core-api.json"
DEPRECATIONS_PATH = ROOT / "compat/core-deprecations.json"
MODULES_PATH = ROOT / "compat/core-modules.json"

EXPECTED_PROFILE = "core-v1"
EXPECTED_GENERATION = 1
EXPECTED_ENTRYPOINT = "io.github.lazily"
EXPECTED_SYMBOLS = {
    "context": {"Context"},
    "source": {"Source"},
    "computed": {"Computed"},
    "effect": {"Effect"},
    "batch": {"Context.batch"},
    "scope": {"Context.scope", "TeardownScope"},
    "typed_context": {"Context"},
}
EXPECTED_DEPRECATIONS = {
    "Context.cell",
    "Context.slot",
    "Context.getCell",
    "Context.setCell",
    "Context.disposeSlot",
    "Context.disposeCell",
    "TeardownScope.cell",
    "TeardownScope.slot",
    "ComputeOps.getCell",
}
EXPECTED_MODULES = {
    "collections",
    "statecharts",
    "synchronization",
    "durable",
    "distributed",
    "transport",
    "codecs",
    "ffi",
    "react",
    "r3",
}


class GateError(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise GateError(f"cannot read {path.relative_to(ROOT)}: {error}") from error
    if not isinstance(value, dict):
        raise GateError(f"{path.relative_to(ROOT)} must contain a JSON object")
    return value


def normalized(text: str) -> str:
    return " ".join(text.split())


def require_common_header(document: dict[str, Any], label: str) -> None:
    if document.get("schema_version") != 1:
        raise GateError(f"{label}: unsupported schema_version")
    if document.get("profile_id") != EXPECTED_PROFILE:
        raise GateError(f"{label}: expected profile {EXPECTED_PROFILE}")
    if document.get("core_api_generation") != EXPECTED_GENERATION:
        raise GateError(f"{label}: expected Core generation {EXPECTED_GENERATION}")


def require_source_signature(entry: dict[str, Any], *, deprecated: bool = False) -> None:
    path_value = entry.get("source_path")
    signature = entry.get("source_signature")
    if not isinstance(path_value, str) or not isinstance(signature, str):
        raise GateError(f"{entry.get('symbol', entry.get('public_name'))}: source proof is incomplete")
    path = (ROOT / path_value).resolve()
    if ROOT not in path.parents or not path.is_file():
        raise GateError(f"{path_value}: source proof path is outside the project or missing")
    source = path.read_text(encoding="utf-8")
    normalized_signature = normalized(signature)
    signature_pattern = re.compile(
        re.escape(normalized_signature).replace(r"\ ", r"\s+"),
        re.DOTALL,
    )
    matches = list(signature_pattern.finditer(source))
    occurrence = entry.get("source_occurrence", 1)
    if not isinstance(occurrence, int) or occurrence < 1 or len(matches) < occurrence:
        raise GateError(f"{path_value}: source signature occurrence {occurrence} is missing: {signature!r}")
    if deprecated:
        prefix = source[max(0, matches[occurrence - 1].start() - 700) : matches[occurrence - 1].start()]
        last_annotation = prefix.rfind("@Deprecated")
        last_declaration = max(prefix.rfind("\n    fun "), prefix.rfind("\ninline fun "), prefix.rfind("\nclass "))
        if last_annotation < 0 or last_annotation < last_declaration:
            raise GateError(f"{entry['symbol']}: declaration is not backed by @Deprecated")


def validate_snapshot(document: dict[str, Any], native_dump: str) -> None:
    require_common_header(document, "Core API snapshot")
    if document.get("package_entrypoint") != EXPECTED_ENTRYPOINT:
        raise GateError(f"Core API snapshot: expected package {EXPECTED_ENTRYPOINT}")
    if document.get("native_dump") != "api/lazily-kt.api":
        raise GateError("Core API snapshot: native dump path changed")

    declarations = document.get("declarations")
    if not isinstance(declarations, list):
        raise GateError("Core API snapshot: declarations must be an array")
    actual: dict[str, set[str]] = {}
    seen: set[tuple[str, str]] = set()
    native_lines = [line.strip() for line in native_dump.splitlines()]
    for declaration in declarations:
        if not isinstance(declaration, dict):
            raise GateError("Core API snapshot: declaration must be an object")
        abstract_symbol = declaration.get("abstract_symbol")
        public_name = declaration.get("public_name")
        key = (abstract_symbol, public_name)
        if not isinstance(abstract_symbol, str) or not isinstance(public_name, str) or key in seen:
            raise GateError(f"Core API snapshot: duplicate or invalid declaration {key}")
        seen.add(key)
        actual.setdefault(abstract_symbol, set()).add(public_name)

        signature = declaration.get("binary_signature")
        owner = declaration.get("binary_owner")
        signature_lines = native_lines
        if owner is not None:
            if not isinstance(owner, str) or native_lines.count(owner) != 1:
                raise GateError(f"{public_name}: binary owner is absent or ambiguous: {owner!r}")
            owner_start = native_lines.index(owner)
            try:
                owner_end = native_lines.index("}", owner_start + 1)
            except ValueError as error:
                raise GateError(f"{public_name}: binary owner is unterminated") from error
            signature_lines = native_lines[owner_start + 1 : owner_end]
        if not isinstance(signature, str) or signature_lines.count(signature) != 1:
            raise GateError(f"{public_name}: binary signature is absent or ambiguous: {signature!r}")
        if "source_signature" in declaration:
            require_source_signature(declaration)

        if declaration.get("introduced_generation") != EXPECTED_GENERATION:
            raise GateError(f"{public_name}: introduction generation must be 1")
        lifecycle = (
            declaration.get("deprecated_since_generation"),
            declaration.get("replacement"),
            declaration.get("earliest_removal_generation"),
        )
        if lifecycle != (None, None, None):
            raise GateError(f"{public_name}: active Core declaration has partial deprecation metadata")

    if actual != EXPECTED_SYMBOLS:
        raise GateError(f"Core API snapshot does not match the profile allow-list: {actual!r}")


def validate_deprecations(document: dict[str, Any]) -> None:
    require_common_header(document, "Core deprecations")
    policy = document.get("compatibility_policy")
    if not isinstance(policy, dict):
        raise GateError("Core deprecations: compatibility policy is missing")
    minimum_generations = policy.get("minimum_generations")
    minimum_days = policy.get("minimum_days")
    if not isinstance(minimum_generations, int) or minimum_generations < 2:
        raise GateError("Core deprecations: compatibility window must span at least two generations")
    if not isinstance(minimum_days, int) or minimum_days < 90:
        raise GateError("Core deprecations: compatibility window must span at least 90 days")

    entries = document.get("deprecations")
    if not isinstance(entries, list):
        raise GateError("Core deprecations: deprecations must be an array")
    symbols = {entry.get("symbol") for entry in entries if isinstance(entry, dict)}
    if symbols != EXPECTED_DEPRECATIONS or len(entries) != len(symbols):
        raise GateError("Core deprecations: metadata is incomplete, stale, or duplicated")

    for entry in entries:
        symbol = entry["symbol"]
        introduced = entry.get("introduced_generation")
        deprecated = entry.get("deprecated_since_generation")
        replacement = entry.get("replacement")
        earliest = entry.get("earliest_removal_generation")
        window = entry.get("compatibility_window")
        if not isinstance(introduced, int) or not isinstance(deprecated, int) or introduced > deprecated:
            raise GateError(f"{symbol}: invalid introduction/deprecation generations")
        if not isinstance(replacement, str) or not replacement:
            raise GateError(f"{symbol}: replacement is missing")
        if not isinstance(window, dict):
            raise GateError(f"{symbol}: compatibility window is missing")
        if window.get("minimum_generations") != minimum_generations or window.get("minimum_days") != minimum_days:
            raise GateError(f"{symbol}: compatibility window diverges from policy")
        if not isinstance(earliest, int) or earliest < deprecated + minimum_generations:
            raise GateError(f"{symbol}: earliest removal violates the compatibility window")
        require_source_signature(entry, deprecated=True)


def validate_modules(document: dict[str, Any]) -> None:
    require_common_header(document, "Core modules")
    if document.get("artifact") != "io.github.lazily:lazily" or document.get("shipping_model") != "single_artifact":
        raise GateError("Core modules: Kotlin modules must remain in the primary artifact")
    modules = document.get("modules")
    if not isinstance(modules, list) or set(modules) != EXPECTED_MODULES or len(modules) != len(EXPECTED_MODULES):
        raise GateError("Core modules: module inventory is incomplete, stale, or duplicated")
    if document.get("independently_shipped_modules") != []:
        raise GateError("Core modules: separately shipped modules require their own oldest/current artifact tests")
    support = document.get("support_range")
    if not isinstance(support, dict):
        raise GateError("Core modules: support range is missing")
    minimum = support.get("minimum_core_api_generation")
    maximum = support.get("maximum_core_api_generation")
    if minimum != 1 or maximum != 1 or minimum > maximum:
        raise GateError("Core modules: expected the candidate profile's 1-1 support range")
    if document.get("compatibility_tested_generations") != sorted({minimum, maximum}):
        raise GateError("Core modules: oldest/current generation evidence is incomplete")


def expect_rejection(label: str, action: Any) -> None:
    try:
        action()
    except GateError:
        return
    raise GateError(f"negative self-test did not reject {label}")


def run_self_test(
    snapshot: dict[str, Any],
    deprecations: dict[str, Any],
    modules: dict[str, Any],
    native_dump: str,
) -> None:
    missing_symbol = copy.deepcopy(snapshot)
    missing_symbol["declarations"].pop()
    expect_rejection("a removed normalized Core symbol", lambda: validate_snapshot(missing_symbol, native_dump))

    mutated_native = native_dump.replace(
        "public final class io/github/lazily/Effect : io/github/lazily/GraphNode {",
        "public final class io/github/lazily/EffectRemoved : io/github/lazily/GraphNode {",
        1,
    )
    expect_rejection("a native ABI mutation", lambda: validate_snapshot(snapshot, mutated_native))

    missing_replacement = copy.deepcopy(deprecations)
    missing_replacement["deprecations"][0]["replacement"] = None
    expect_rejection("incomplete deprecation lifecycle", lambda: validate_deprecations(missing_replacement))

    missing_generation = copy.deepcopy(modules)
    missing_generation["compatibility_tested_generations"] = []
    expect_rejection("missing oldest/current module evidence", lambda: validate_modules(missing_generation))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    snapshot = load_json(SNAPSHOT_PATH)
    deprecations = load_json(DEPRECATIONS_PATH)
    modules = load_json(MODULES_PATH)
    native_path = ROOT / snapshot.get("native_dump", "")
    try:
        native_dump = native_path.read_text(encoding="utf-8")
    except OSError as error:
        raise GateError(f"cannot read native API dump {native_path}: {error}") from error

    validate_snapshot(snapshot, native_dump)
    validate_deprecations(deprecations)
    validate_modules(modules)
    if args.self_test:
        run_self_test(snapshot, deprecations, modules, native_dump)
        print("core API negative self-test: OK")
    else:
        print("core API compatibility: OK")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GateError as error:
        print(f"core API compatibility: ERROR: {error}")
        raise SystemExit(1) from error
