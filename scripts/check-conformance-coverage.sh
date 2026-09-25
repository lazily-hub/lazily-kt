#!/usr/bin/env bash
# Conformance-coverage guard.
#
# Fails the build when the canonical corpus in ../lazily-spec/conformance/ holds a
# fixture this binding does not actually replay. That is the drift this guard
# exists for: a fixture lands upstream, every binding stays green, and nobody
# learns that one of them is not replaying it.
#
# This binding uses the RUNTIME manifest, not a static grep: every fixture read
# through ConformanceFixtures.read() is recorded and flushed to
# build/conformance-fixtures-loaded.txt on JVM shutdown. A fixture named in a
# comment but hand-transcribed is therefore caught here — a source grep cannot see
# that case at all.
#
# This guard used to be a MIN_FIXTURES floor (117) plus REQUIRED_AREAS. That is
# strictly weaker than per-fixture accounting and it had rotted: 131 fixtures
# replayed against a floor of 117, so roughly 14 replays could have stopped
# running with CI still green, and REQUIRED_AREAS only noticed a whole area going
# dark. The floor is gone. Every canonical fixture must now be OPENED or
# explicitly excused in KNOWN_UNCOVERED, exactly as the other eight bindings
# require.
#
# A missing manifest is missing EVIDENCE and fails. It does not mean "no fixtures
# were read"; it means the suite ran without the recorder attached, and passing in
# that state is the vacuous green this guard exists to prevent. A missing CORPUS
# is the same claim one level up: it is a skip on a local checkout without the
# sibling, and a hard failure under CI (#lzvacuousrun). And because every rung
# here is a negative check over a population, the run must also prove the
# population was non-empty before it may print OK — see the positive-evidence
# block near the bottom.
#
# A second runtime ledger, written the same way, carries per-SCENARIO replay
# accounting (#lzscenariocoverage). "Was the file opened" and "was every scenario
# in it replayed" are different questions, and the first cannot answer the second:
# one scenario is enough to open a file. See the rung-4 block below.
#
# Ahead of all of that, a source-hygiene rung asserts only the seam SPELLS the
# default corpus root (#lzcorpusrootguards). Every ledger below reasons about
# fixtures that WERE opened, and a runner holding a hardcoded
# "../lazily-spec/conformance/<area>" opens real fixtures — it is invisible here
# and it is unfalsifiable, because LAZILY_SPEC_CONFORMANCE_DIR cannot reach it.
#
# Usage: scripts/check-conformance-coverage.sh [manifest-path] [scenario-ledger-path]
set -euo pipefail

# ---- RUNG: only the seam may SPELL the corpus root (#lzcorpusrootguards) ----
#
# `LAZILY_SPEC_CONFORMANCE_DIR` is what makes a conformance replay falsifiable:
# copy the corpus, perturb one fixture, confirm the suite reddens. A runner that
# builds its own "../lazily-spec/conformance/<area>" instead of asking
# ConformanceFixtures never sees the override — and the failure is SILENT,
# because a runner reading the DEFAULT corpus while believing it was redirected
# is green either way. Nothing else in this file can see it: every rung below
# audits fixtures that WERE opened, and a hardcoded root opens real fixtures.
#
# The measurement is what makes this worth a guard rather than a convention.
# lazily-kt was MEASURED BROKEN this round for the sibling reason —
# `ConformanceFixtures` read only `LAZILY_SPEC_DIR` while this script read
# `LAZILY_SPEC_CONFORMANCE_DIR` first, so a scratch corpus holding a truncated
# fixture produced 447 passing tests and exit 0 (fixed in ba7f41d). That same
# commit removed `StateChartConformanceTest.specDir`, a dead field spelling
# "../lazily-spec/conformance/statechart" verbatim. It bypassed nothing — the
# file reads through the seam — but it sat in the tree through every green run.
# That is the standing proof that nothing here guards against re-spelling the
# default root, and this rung is that guard.
#
# It runs FIRST, before the corpus is even located: it is a source-hygiene check
# with no dependency on a sibling checkout, and the absent-corpus skip further
# down must not be able to swallow it.
#
# SCOPE: `src/**/*.kt` only. `build.gradle.kts:88` has
# `srcDir("../lazily-spec/proto")` — protobuf source generation, not a corpus
# path — and lines 143-145 DECLARE the corpus default as the Gradle task input,
# which is the build-level seam. Scoping the walk to `src` means the scanner
# never sees either, so neither needs a special case.
#
# `ConformanceFixtures.kt` is the one legitimate mention in `src`: it DECLARES
# the default (`"../lazily-spec"` + `.resolve("conformance")`) after reading both
# environment variables. Every other file naming the path does so in KDoc, which
# the scanner skips on purpose.
CORPUS_ROOT_ALLOW="src/test/kotlin/io/github/lazily/ConformanceFixtures.kt"
read -r -a CORPUS_ROOT_SCAN_DIRS <<< "${LAZILY_CORPUS_ROOT_SCAN_DIRS:-src}"

# The floor exists because the clause below reasons about files the walk FOUND,
# so it is vacuously satisfied by an empty file list: a scan that examined
# nothing reports no offenders and would print OK. That is the vacuous green the
# rest of this file refuses (#lzvacuousrun). Pinned below the real tree (141
# sources) with headroom; a drop this far means the walk is pointed somewhere
# wrong, not that the repo shrank.
MIN_SCANNED_SOURCES="${MIN_SCANNED_SOURCES:-100}"

collect_kt_sources() {
  for d in "${CORPUS_ROOT_SCAN_DIRS[@]}"; do
    [ -d "$d" ] || continue
    find "$d" -type f -name '*.kt' -not -path '*/build/*'
  done | sort
}

CORPUS_ROOT_PY="$(cat <<'PY'
import re
import sys

NEEDLE = "../lazily-spec/conformance"
NEEDLE_SQUASHED = re.sub(r"[\\/\s]", "", NEEDLE)
WINDOW_LITERALS = 12
WINDOW_LINES = 10


def _read_raw(text, i, line):
    """Kotlin raw string: \"\"\" ... \"\"\", no escapes, interpolation allowed."""
    n = len(text)
    i += 3
    start = i
    while i < n:
        if text[i] == '"' and text[i:i + 3] == '"""':
            body = text[start:i]
            # A run of more than three quotes closes on the LAST three.
            j = i
            while j < n and text[j] == '"':
                j += 1
            body = text[start:j - 3]
            return j, body, line + text[start:j].count("\n")
        i += 1
    body = text[start:]
    return n, body, line + body.count("\n")


def _read_regular(text, i, line):
    """Kotlin escaped string: "..." with backslash escapes and $ interpolation."""
    n = len(text)
    i += 1
    out = []
    while i < n:
        c = text[i]
        if c == "\\" and i + 1 < n:
            nxt = text[i + 1]
            out.append("\\" if nxt == "\\" else nxt)
            i += 2
            continue
        if c == '"':
            return i + 1, "".join(out), line
        if c == "$" and i + 1 < n and text[i + 1] == "{":
            # Drop the interpolation hole, including any nested string literal,
            # so a quote inside `${x ?: "y"}` cannot desync the scan — and so
            # "../lazily-spec${sep}conformance" still reads as the root.
            depth = 1
            i += 2
            while i < n and depth > 0:
                d = text[i]
                if d == "{":
                    depth += 1
                elif d == "}":
                    depth -= 1
                elif d == '"':
                    i, _, line = _read_regular(text, i, line)
                    continue
                elif d == "\n":
                    line += 1
                i += 1
            continue
        if c == "\n":
            # Unterminated in valid Kotlin; tolerate rather than desync.
            line += 1
        out.append(c)
        i += 1
    return n, "".join(out), line


def literals(text):
    """Every string-literal VALUE in source order, with its opening line.

    Comments and KDoc are skipped: several files here legitimately quote the
    corpus root while explaining it, and lint-forcing an explanation to stop
    describing the thing it explains trades real documentation for a guard that
    is trivial to satisfy. Kotlin block comments NEST, so the depth counter is
    not optional.
    """
    out = []
    i = 0
    n = len(text)
    line = 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if c == "/" and text[i:i + 2] == "//":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and text[i:i + 2] == "/*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if text[i:i + 2] == "/*":
                    depth += 1
                    i += 2
                    continue
                if text[i:i + 2] == "*/":
                    depth -= 1
                    i += 2
                    continue
                if text[i] == "\n":
                    line += 1
                i += 1
            continue
        if c == "`":
            # Backtick-quoted identifier (`fun \`a name\`()`).
            i += 1
            while i < n and text[i] != "`":
                if text[i] == "\n":
                    line += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                if i < n and text[i] == "\n":
                    line += 1
                i += 1
            i += 1
            continue
        if c == '"':
            start = line
            if text[i:i + 3] == '"""':
                i, val, line = _read_raw(text, i, line)
            else:
                i, val, line = _read_regular(text, i, line)
            out.append((start, val))
            continue
        i += 1
    return out


def squash(s):
    return re.sub(r"[\\/\s]", "", s)


def offenders(text):
    found = []
    lits = literals(text)
    flagged = set()
    # Pass 1 — the single-literal form.
    for idx, (line, val) in enumerate(lits):
        if NEEDLE in val.replace("\\", "/"):
            found.append((line, "single literal", val))
            flagged.add(idx)
    # Pass 2 — the JOINED-SEGMENT form: Path.of("..", "lazily-spec",
    # "conformance", area) or "../lazily-spec" + "/conformance". No single piece
    # carries the root, so the match runs over a short run of ADJACENT literals
    # with the separators squashed out. This is the form the lazily-go and
    # lazily-js guards missed and were proven evadable on.
    for idx, (line, val) in enumerate(lits):
        if idx in flagged:
            continue
        joined = squash(val)
        for j in range(idx + 1, min(idx + WINDOW_LITERALS, len(lits))):
            if j in flagged:
                break
            nline, nval = lits[j]
            if nline - line > WINDOW_LINES:
                break
            joined += squash(nval)
            if NEEDLE_SQUASHED in joined:
                found.append((line, "joined segments", " + ".join(
                    repr(v) for _, v in lits[idx:j + 1])))
                flagged.update(range(idx, j + 1))
                break
    found.sort()
    return found


def main(argv):
    allow = set(argv[1].split(",")) if argv[1] else set()
    paths = [p for p in sys.stdin.read().split("\n") if p]
    examined = 0
    hits = []
    for p in paths:
        rel = p[2:] if p.startswith("./") else p
        if rel in allow:
            continue
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError as exc:
            print("ERROR: cannot read %s: %s" % (p, exc), file=sys.stderr)
            return 2
        examined += 1
        for line, form, detail in offenders(text):
            hits.append((rel, line, form, detail))
    print("EXAMINED %d" % examined)
    for rel, line, form, detail in hits:
        print("HIT %s:%d\t%s\t%s" % (rel, line, form, detail))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
PY
)"

corpus_root_report="$(collect_kt_sources | python3 -c "$CORPUS_ROOT_PY" "$CORPUS_ROOT_ALLOW")"
scanned="$(sed -n 's/^EXAMINED //p' <<< "$corpus_root_report")"

if [ -z "$scanned" ]; then
  echo "ERROR: the corpus-root scanner produced no verdict at all." >&2
  echo "       That is missing EVIDENCE, not a clean tree." >&2
  exit 1
fi
if [ "$scanned" -lt "$MIN_SCANNED_SOURCES" ]; then
  echo "ERROR: corpus-root scan examined only $scanned Kotlin sources, expected >= $MIN_SCANNED_SOURCES." >&2
  echo "       Searched: ${CORPUS_ROOT_SCAN_DIRS[*]} (from \$PWD=$PWD)." >&2
  echo "       Reporting OK here would be a pass over nothing: no files means no" >&2
  echo "       offenders, which is not the same finding as no offenders in the" >&2
  echo "       tree (#lzvacuousrun)." >&2
  exit 1
fi
if grep -q '^HIT ' <<< "$corpus_root_report"; then
  echo "ERROR: these sources SPELL the canonical corpus root instead of resolving it" >&2
  echo "       through ConformanceFixtures:" >&2
  grep '^HIT ' <<< "$corpus_root_report" | sed 's/^HIT /         /' >&2
  echo "       LAZILY_SPEC_CONFORMANCE_DIR does not reach a hardcoded path, so those" >&2
  echo "       fixtures are replayed unfalsifiably — a perturbation probe cannot" >&2
  echo "       redden them and the runner looks green either way. Resolve the corpus" >&2
  echo "       with ConformanceFixtures.root / ConformanceFixtures.read()" >&2
  echo "       (#lzcorpusrootguards)." >&2
  exit 1
fi

echo "corpus-root guard OK: $scanned Kotlin sources examined, none spell '../lazily-spec/conformance'" \
     "(single-literal AND joined-segment forms; comments and KDoc skipped; 1 allowlisted seam)"


# ---- RUNG: the flag/presence spellings a runner may NOT reach for -----------
#
# `#lzsiblingrunnermasking`. Every coerced flag and defaulted presence read
# `#lzflagcoercion` found is fixed. Nothing stopped the next one — and the
# measurement is what makes that worth a guard rather than a convention:
#
#  - `CollectionsFamilyConformanceTest` ran `handle_stable` in BOTH directions
#    where `CollectionsConformanceTest` ran one, over the SAME two fixtures. A
#    planted `handle_stable: false` reddened only because the family runner
#    existed.
#  - `QueueFamilyConformanceTest` read `expected` and `expected.invalidates`
#    through `getValue` where `QueueCellConformanceTest` defaulted BOTH to
#    `JsonObject(emptyMap())`, over the SAME five fixtures. An emptied block
#    replays the op and then asserts nothing at all.
#  - `WorkQueueConformanceTest` read the four matrix kinds by name (catching a
#    DROPPED kind, blind to an ADDED one) where the family runner iterated the
#    fixture's own keys (catching the add, blind to the drop). Each covered the
#    half the other missed.
#
# In all three the coverage was an accident of which runners exist, not a
# property of any assertion — and a mask disappears the moment a runner is
# deleted, split, renamed or SKIPPED. lazily-kt's Gradle `:test` reads
# `UP-TO-DATE` under RTK's output filter, so a suite that never executed reads
# as a passing one: the tooling that hides a skipped runner is the same tooling
# that hides the mask.
#
# So the spellings themselves are made unavailable. lazily-cpp could delete its
# `Json::as_bool()` and turn the weak read into a COMPILE error; Kotlin cannot —
# `booleanOrNull`, `as? Boolean` and `?: emptyList()` are kotlinx and stdlib, and
# there is nothing local to delete. This rung is that compile error's stand-in,
# and it is why it has to scan rather than trust a convention.
#
# WHAT IS BANNED, on code with comments, KDoc and string literals removed and
# whitespace collapsed (so `?:\n    emptyList()` and `as?  Boolean` are the same
# pattern to the scan — a guard that only matches the one-line form is one a
# reformat walks through):
#
#   empty-default            `?: emptyList() / emptySet() / emptyMap()`
#   flag-default             `?: true` / `?: false`
#   empty-json-default       `?: JsonObject(...)` / `?: JsonArray(...)`
#   boolean-cast             `as? Boolean`
#   or-null-skip             `booleanOrNull?.let` / `intOrNull?.let`
#   presence-proxy           `takeIf { ... isNotEmpty() }`
#   unguarded-booleanOrNull  `booleanOrNull` with no `!isString` guard in the
#                            same expression
#
# The strict shapes are `.boolean` / `.int`, `getValue`, and `!!` — all of which
# THROW on a wrong type or a missing key, which is the whole point.
#
# `unguarded-booleanOrNull` is a rule about the GUARD, not about the name: the
# one strict spelling is `(v as? JsonPrimitive)?.takeIf { !it.isString }?.
# booleanOrNull ?: error(...)`, and the `!isString` half is load-bearing because
# kotlinx parses the STRING "true" as a boolean. `AssertionKeys.boolean()` and
# `StateChartConformanceTest`'s guard read that way and pass unallowlisted;
# anything that drops the guard is a hit. `import` declarations are blanked
# first — an import names a function, it does not read a fixture.
#
# SCOPE: `src/test/kotlin` only. `src/main` is library code, where a codec's
# type-dispatch chain (`MsgpackCodec.kt`: try bool, then long, then string)
# legitimately reaches for `booleanOrNull`, and `StateChart.kt` already carries
# the `!isString` guard for `parallel`/`internal` with a comment saying why.
# Widening this to `src/main` would ban a correct codec to guard a runner.
#
# NOT covered, and deliberately: `longOrNull` / `contentOrNull` read against a
# NULLABLE expectation (`next_fire`, `holder`, `current_leader`) are the corpus's
# own shape for "this observable may be absent", and the general presence-proxy
# form (`if (x.isNotEmpty()) { assert... }`) is not statically separable from the
# floors this file is full of (`assertTrue(steps.isNotEmpty())`). The
# presence-proxy rule therefore catches only the `takeIf` spelling, which is the
# one `#lzflagcoercion` actually found in a runner.
#
# It runs with the corpus-root rung, before the corpus is located: it is source
# hygiene, and the absent-corpus skip further down must not swallow it.
FLAG_HYGIENE_SCAN_DIRS_DEFAULT="src/test/kotlin"
read -r -a FLAG_HYGIENE_SCAN_DIRS <<< "${LAZILY_FLAG_HYGIENE_SCAN_DIRS:-$FLAG_HYGIENE_SCAN_DIRS_DEFAULT}"

# Allowlist entries are `<path>:<rule>` — a file is never excused wholesale, so
# excusing one spelling cannot hide a different one in the same file.
#
# ONE entry. `ConformanceFixtures.kt` reads its two environment overrides as
# `System.getenv(...)?.takeIf { it.isNotEmpty() }`, which is emptiness standing
# for "nobody set this variable" — a shell variable really is empty-or-absent,
# and there is no JSON key and no fixture anywhere near it. Both reads are the
# override seam this whole file exists to keep falsifiable.
FLAG_HYGIENE_ALLOW="src/test/kotlin/io/github/lazily/ConformanceFixtures.kt:presence-proxy"

# The floor exists because the rung reasons about files the walk FOUND, so it is
# vacuously satisfied by an empty file list: a scan that examined nothing reports
# no offenders and prints OK (#lzvacuousrun). Pinned below the real tree (80 test
# sources) with headroom; a drop this far means the walk is pointed somewhere
# wrong, not that the suite shrank.
MIN_SCANNED_FLAG_SOURCES="${MIN_SCANNED_FLAG_SOURCES:-60}"

collect_flag_hygiene_sources() {
  for d in "${FLAG_HYGIENE_SCAN_DIRS[@]}"; do
    [ -d "$d" ] || continue
    find "$d" -type f -name '*.kt' -not -path '*/build/*'
  done | sort
}

FLAG_HYGIENE_PY="$(cat <<'FLAGPY'
import re
import sys

# Every rule is (id, description, compiled pattern over NORMALIZED code).
#
# NORMALIZED code is the file with comments, KDoc, string literals, character
# literals and backtick identifiers removed, and every run of whitespace
# collapsed to one space. Normalizing is what makes the near-miss spellings
# unreachable: `?:\n    emptyList()` across a line break and `as?  Boolean` with
# two spaces are the SAME pattern to this scanner, and a guard that only sees
# the one-line form is a guard a reformat walks straight through.
RULES = [
    (
        "empty-default",
        "a fixture read defaulted to an empty collection",
        re.compile(
            r"\?: ?(?:[A-Za-z_][A-Za-z0-9_]* ?\. ?)*"
            r"empty(?:List|Set|Map)\b(?: ?<[^()]*>)? ?\(\)"
        ),
    ),
    (
        "flag-default",
        "a fixture flag defaulted to a bare true/false",
        re.compile(r"\?: ?(?:true|false)\b"),
    ),
    (
        "empty-json-default",
        "a fixture block defaulted to an empty JsonObject/JsonArray",
        re.compile(r"\?: ?Json(?:Object|Array) ?\("),
    ),
    (
        "boolean-cast",
        "a Kotlin cast to Boolean instead of the JSON type",
        re.compile(r"as\? ?Boolean\b"),
    ),
    (
        "or-null-skip",
        "an *OrNull read whose null is then SKIPPED by ?.let",
        re.compile(r"(?:boolean|int)OrNull ?\?\. ?let\b"),
    ),
    (
        "presence-proxy",
        "emptiness used as a proxy for a key's PRESENCE",
        re.compile(r"takeIf ?\{[^}]*isNotEmpty\(\)"),
    ),
]

# `booleanOrNull` gets its own rule: the ONE strict spelling of it is
# `(x as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error(...)`,
# which is load-bearing (kotlinx parses the STRING "true" as a boolean, so the
# `!isString` guard is what refuses the quoted spelling). So the rule is not
# "never name booleanOrNull" but "never name it without that guard in the same
# expression".
GUARDED_WINDOW = 200


IMPORT = re.compile(r"^[^\S\n]*import[^\S\n]+\S+[^\S\n]*$", re.MULTILINE)


def strip(text):
    """Code characters only, with their 1-based source line.

    `import` declarations are blanked first, keeping their newlines so line
    numbers still line up. An import NAMES a function; it does not read a
    fixture, and `import kotlinx.serialization.json.booleanOrNull` is required
    by the one guarded call site that is allowed to use it.
    """
    text = IMPORT.sub("", text)
    out = []
    i = 0
    n = len(text)
    line = 1
    while i < n:
        c = text[i]
        if c == "\n":
            out.append(("\n", line))
            line += 1
            i += 1
            continue
        if text[i:i + 2] == "//":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text[i:i + 2] == "/*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if text[i:i + 2] == "/*":
                    depth += 1
                    i += 2
                    continue
                if text[i:i + 2] == "*/":
                    depth -= 1
                    i += 2
                    continue
                if text[i] == "\n":
                    line += 1
                i += 1
            continue
        if c == "`":
            i += 1
            while i < n and text[i] != "`":
                if text[i] == "\n":
                    line += 1
                i += 1
            i += 1
            out.append((" ", line))
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append((" ", line))
            continue
        if c == '"':
            if text[i:i + 3] == '"""':
                i += 3
                while i < n:
                    if text[i] == '"' and text[i:i + 3] == '"""':
                        i += 3
                        break
                    if text[i] == "\n":
                        line += 1
                    i += 1
            else:
                i += 1
                while i < n:
                    if text[i] == "\\":
                        i += 2
                        continue
                    if text[i] == '"':
                        i += 1
                        break
                    if text[i] == "\n":
                        line += 1
                    i += 1
            out.append((" ", line))
            continue
        out.append((c, line))
        i += 1
    return out


def normalize(stripped):
    """Collapse whitespace runs to one space; keep a line number per char."""
    chars = []
    lines = []
    prev_ws = False
    for c, ln in stripped:
        if c.isspace():
            if not prev_ws:
                chars.append(" ")
                lines.append(ln)
            prev_ws = True
            continue
        prev_ws = False
        chars.append(c)
        lines.append(ln)
    return "".join(chars), lines


def hits(text):
    code, lines = normalize(strip(text))
    found = []
    for rule, desc, pat in RULES:
        for m in pat.finditer(code):
            found.append((lines[m.start()], rule, desc, m.group(0)))
    for m in re.finditer(r"booleanOrNull", code):
        window = code[max(0, m.start() - GUARDED_WINDOW):m.start()]
        if "isString" in window:
            continue
        found.append((
            lines[m.start()],
            "unguarded-booleanOrNull",
            "booleanOrNull with no !isString guard in the same expression",
            "booleanOrNull",
        ))
    found.sort()
    return found


def scanner_self_test():
    """Prove the normalized matcher sees the spellings that broke earlier rungs."""
    probes = [
        ("val x = block ?: emptyMap<String, Boolean>()", "empty-default"),
        ("val x = block ?: \n emptyList < String > ()", "empty-default"),
        (
            "val x = block ?: kotlin.collections.emptyMap < String, List<Boolean> > ()",
            "empty-default",
        ),
        ("val x = value as?   Boolean", "boolean-cast"),
    ]
    failures = []
    for source, expected in probes:
        observed = {rule for _, rule, _, _ in hits(source)}
        if expected not in observed:
            failures.append("%s missed by %s" % (source.replace("\n", "\\n"), expected))
    return failures


def main(argv):
    probe_failures = scanner_self_test()
    if probe_failures:
        for failure in probe_failures:
            print("ERROR: flag-hygiene scanner self-test: %s" % failure, file=sys.stderr)
        return 2
    allow = set()
    for entry in argv[1].split(","):
        entry = entry.strip()
        if entry:
            allow.add(entry)
    paths = [p for p in sys.stdin.read().split("\n") if p]
    examined = 0
    out = []
    for p in paths:
        rel = p[2:] if p.startswith("./") else p
        try:
            with open(p, "r", encoding="utf-8", errors="replace") as fh:
                text = fh.read()
        except OSError as exc:
            print("ERROR: cannot read %s: %s" % (p, exc), file=sys.stderr)
            return 2
        examined += 1
        for line, rule, desc, snippet in hits(text):
            if "%s:%s" % (rel, rule) in allow:
                continue
            out.append((rel, line, rule, desc, snippet))
    print("EXAMINED %d" % examined)
    for rel, line, rule, desc, snippet in out:
        print("HIT %s:%d\t%s\t%s\t%s" % (rel, line, rule, desc, snippet))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
FLAGPY
)"

flag_hygiene_report="$(collect_flag_hygiene_sources | python3 -c "$FLAG_HYGIENE_PY" "$FLAG_HYGIENE_ALLOW")"
flag_scanned="$(sed -n 's/^EXAMINED //p' <<< "$flag_hygiene_report")"

flag_hygiene_failed=0
if [ -z "$flag_scanned" ]; then
  echo "ERROR: the flag/presence hygiene scanner produced no verdict at all." >&2
  echo "       That is missing EVIDENCE, not a clean tree." >&2
  flag_hygiene_failed=1
elif [ "$flag_scanned" -lt "$MIN_SCANNED_FLAG_SOURCES" ]; then
  echo "ERROR: flag/presence scan examined only $flag_scanned Kotlin test sources," >&2
  echo "       expected >= $MIN_SCANNED_FLAG_SOURCES." >&2
  echo "       Searched: ${FLAG_HYGIENE_SCAN_DIRS[*]} (from \$PWD=$PWD)." >&2
  echo "       Reporting OK here would be a pass over nothing: no files means no" >&2
  echo "       offenders, which is not the same finding as no offenders in the" >&2
  echo "       tree (#lzvacuousrun)." >&2
  flag_hygiene_failed=1
fi

if grep -q '^HIT ' <<< "$flag_hygiene_report"; then
  echo "ERROR: these conformance sources reach for a flag/presence spelling that" >&2
  echo "       cannot fail on a malformed or missing fixture value:" >&2
  grep '^HIT ' <<< "$flag_hygiene_report" | sed 's/^HIT /         /' >&2
  echo "       Every one of these decodes a wrong TYPE or an ABSENT key to a value" >&2
  echo "       and then compares it, or skips the comparison entirely — so the row" >&2
  echo "       reads exactly like a key the fixture never carried and the suite" >&2
  echo "       stays green. Use .boolean / .int (which throw on the wrong type)," >&2
  echo "       getValue (which throws on a missing key), or spell the absence out" >&2
  echo "       in a when/if that names what absence MEANS. Do not rely on a" >&2
  echo "       sibling runner over the same fixture being stricter: that coverage" >&2
  echo "       disappears the moment the sibling is deleted, split, renamed or" >&2
  echo "       skipped (#lzsiblingrunnermasking)." >&2
  flag_hygiene_failed=1
fi

if [ "$flag_hygiene_failed" -ne 0 ]; then
  exit 1
fi

echo "flag/presence hygiene OK: $flag_scanned Kotlin test sources examined, none reach for a" \
     "coercing or defaulting fixture read (7 rules; comments, KDoc, string literals and imports" \
     "skipped; whitespace-insensitive; 1 allowlisted rule-in-file)"


SPEC_DIR="${LAZILY_SPEC_CONFORMANCE_DIR:-${LAZILY_SPEC_DIR:-../lazily-spec}/conformance}"

# A missing corpus is a legitimate LOCAL state (no sibling checkout) and an
# illegitimate CI state (#lzvacuousrun). Every rung below reasons about fixtures
# the corpus lists and the run OPENED, so an absent corpus makes all of them
# vacuously true: zero fixtures means zero uncovered fixtures, zero stale
# excuses, and zero unreplayed scenarios. Exiting 0 there reports conformance OK
# having examined nothing, which is exactly the false claim this guard exists to
# prevent. Under CI that is missing EVIDENCE — a wrong checkout — not evidence of
# absence, so it is a hard failure. Locally it stays a skip, because a
# contributor without the sibling is not making a claim about coverage at all.
if [ ! -d "$SPEC_DIR" ]; then
  if [ -n "${CI:-}" ]; then
    echo "ERROR: canonical corpus not found at $SPEC_DIR, and CI is set." >&2
    echo "       Under CI this is missing EVIDENCE, not evidence of absence: the" >&2
    echo "       checkout is wrong, not the corpus. Exiting 0 here would report" >&2
    echo "       conformance OK having examined zero fixtures (#lzvacuousrun)." >&2
    exit 1
  fi
  echo "SKIP: canonical corpus not found at $SPEC_DIR (clone the lazily-spec sibling)" >&2
  echo "      Local checkout only — this is a hard failure under CI." >&2
  exit 0
fi

# --- RUN-ID FRESHNESS (#lzstalemanifest) -------------------------------------
#
# Every evidence file this guard reads is written by the Gradle test JVM and read
# back here, in a separate process. Until this rung landed, nothing connected the
# two but bytes on disk — and Gradle caches `:test` aggressively. With nothing
# changed it prints `> Task :test UP-TO-DATE`, no JVM starts, nothing is written,
# and the PREVIOUS run's manifest, scenario ledger and block ledger are still
# sitting in build/ byte-identical to a real run's output.
#
# Measured before this landed: a second `make check` with nothing changed exited
# 0 with `> Task :test UP-TO-DATE`, so every rung below — the per-fixture
# accounting, the scenario equalities in both directions, the rung-0 site and
# digest magnitudes, the bind ledger and its two stale directions — reported OK
# about a run that never happened. Real evidence required forcing
# `cleanTest test`. RTK strips Gradle task lines, so the one line that explains
# it is invisible in captured output, which is how it survived.
#
# That is the failure mode this whole script exists to refuse, one level up: not
# "the suite ran and proved nothing", but "the suite did not run and the file
# says it did". Every message here claiming "these bytes were really read" was
# conditional on a build-cache state nobody checked.
#
# So `make` generates ONE id per invocation, the test JVM stamps it as the FIRST
# line of every evidence file it writes, and every read below goes through
# require_run_id first. A cached `:test` writes no stamp at all, last run's id
# stays on disk, and this fails BY NAME rather than trusting it.
#
# ABSENT is a REFUSAL, not a skip. A guard that accepts unstamped evidence when
# the variable is unset is the same hole with one more step in front of it, and
# it is the step every caller would take. There is deliberately NO opt-out flag:
# both paths that run this guard set the variable — `make check` (the Makefile
# generates it) and CI's own `Guard — conformance fixtures actually replayed`
# step (the job sets it, because CI runs `./gradlew test` and this script as two
# separate steps rather than through the Makefile).
#
# This sits AFTER the corpus skip on purpose. A checkout with no lazily-spec
# sibling makes no claim about coverage and reads no evidence, so there is
# nothing there for a freshness check to be about.
RUN_ID_STAMP_PREFIX='# lazily-run-id '
RUN_ID="${LAZILY_CONFORMANCE_RUN_ID-}"
if [ -z "$RUN_ID" ]; then
  echo "FAIL: LAZILY_CONFORMANCE_RUN_ID is unset (or empty)." >&2
  echo "      Every rung below reads an evidence file written by a DIFFERENT" >&2
  echo "      process, and Gradle's :test is cached — without a per-invocation id" >&2
  echo "      to match against, a stale manifest from a run that happened last" >&2
  echo "      week is indistinguishable from this one's (#lzstalemanifest)." >&2
  echo "      Run this through \`make check\` / \`make test\`, which generates the" >&2
  echo "      id, or export LAZILY_CONFORMANCE_RUN_ID yourself around BOTH the" >&2
  echo "      test step and this guard. Refusing rather than skipping: accepting" >&2
  echo "      unstamped evidence when the variable is unset is the same hole." >&2
  exit 1
fi

# Refuse an evidence file that is not stamped with THIS invocation's run id.
# Names the file, the id it found and the id it wanted — "stale evidence" without
# those three is not actionable.
require_run_id() {
  file="$1"
  what="$2"
  first="$(head -n 1 "$file")"
  case "$first" in
    "$RUN_ID_STAMP_PREFIX"*)
      found="${first#"$RUN_ID_STAMP_PREFIX"}"
      ;;
    *)
      echo "FAIL: $what at $file carries NO run-id stamp (#lzstalemanifest)." >&2
      echo "      first line: ${first:-<empty>}" >&2
      echo "      wanted:     ${RUN_ID_STAMP_PREFIX}$RUN_ID" >&2
      echo "      Either the file predates the stamp and is left over from an older" >&2
      echo "      build directory, or the recorder that writes it is older than this" >&2
      echo "      guard. Re-run the suite (./gradlew cleanTest test) so it is" >&2
      echo "      rewritten. An unstamped file is undatable evidence, which is the" >&2
      echo "      same as no evidence." >&2
      exit 1
      ;;
  esac
  if [ "$found" != "$RUN_ID" ]; then
    echo "FAIL: $what at $file is STALE evidence (#lzstalemanifest)." >&2
    echo "      stamped with: $found" >&2
    echo "      this run is:  $RUN_ID" >&2
    echo "      The test step did not write this file during this invocation, so it" >&2
    echo "      describes an EARLIER run. The usual cause is Gradle skipping the" >&2
    echo "      work: \`> Task :test UP-TO-DATE\` writes nothing and leaves the" >&2
    echo "      previous run's bytes in place — and RTK strips task lines, so that" >&2
    echo "      line may not appear in captured output at all." >&2
    echo "      Re-run the suite for real: ./gradlew cleanTest test" >&2
    echo "      Do NOT re-stamp the file by hand. The point of the id is that only" >&2
    echo "      a run that actually happened can write one." >&2
    exit 1
  fi
}

# The evidence CONTENT of a stamped file: `#` lines are comments, the same rule
# the committed per-site ledger below already uses. Only the run-id stamp is
# written that way today, and freshness is checked from the raw first line before
# any caller gets here — so this never launders an unstamped file into a valid
# one, it only keeps the stamp out of the populations being counted.
evidence_lines() {
  grep -v '^#' "$1" || true
}

# Refuse a STAMPED file that carries no records (#lzstampsatisfiesnonempty).
#
# The run-id stamp made every evidence file non-empty by construction, which
# quietly weakened every byte-based emptiness test standing in front of one. A
# detached recorder that writes nothing but its stamp produces a file a few dozen
# bytes long that passes `[ -s ]`, and passes require_run_id too — the stamp is
# this run's id, it really was written by this run — and then yields an EMPTY
# population to whatever rung reads it. The freshness check cannot catch it: the file is fresh.
# It says nothing, freshly.
#
# So emptiness is asked of the RECORDS, below the stamp, and asked AFTER
# require_run_id: the stamp has to be this run's before its absence of content
# means anything about this run. lazily-js measured the same hazard as a 33-byte
# stamp-only file satisfying its `statSync().size` check, and lazily-cpp found it
# in CI, where a "manifest written" step tested `-s`.
#
# `[ ! -s ]` stays in front of every caller and is not redundant: it answers the
# ABSENT and ZERO-BYTE cases, which must fail before `head -n 1` reads the file,
# and it fails with the message that names the recorder that never attached.
# This asks the different question that the stamp introduced.
require_evidence_records() {
  file="$1"
  what="$2"
  records="$(evidence_lines "$file" | grep -c . || true)"
  if [ "$records" -eq 0 ]; then
    echo "FAIL: $what at $file carries a run-id stamp and NOTHING ELSE" \
         "(#lzstampsatisfiesnonempty)." >&2
    echo "      The file is fresh — it is stamped with THIS run's id — and it is" >&2
    echo "      non-empty, so an \`[ -s ]\` test passes it. It records zero" >&2
    echo "      $what entries, which is not evidence of an empty corpus: it is a" >&2
    echo "      recorder that attached and then wrote nothing." >&2
    echo "      Usual cause: the suite ran with no fixture-bearing test selected" >&2
    echo "      (a --tests filter, or a shutdown hook that flushed before the" >&2
    echo "      replays), so the writer opened the file, stamped it and exited." >&2
    echo "      Re-run the whole suite: ./gradlew cleanTest test" >&2
    exit 1
  fi
}

# Fixtures deliberately not replayed by this binding yet. Each entry is a claim
# that someone looked; shrinking this list is the work. Adding to it silently is
# how the guard rots, so keep a reason with any new entry.
#
# NOTE: this ledger is NOT the same thing as EXPECTED_SKIPS in
# ReactiveGraphConformanceTest. That map records fixtures this script counts as
# OPENED — their bytes are read and their ops parsed — whose *replay* then stops
# at a named unsupported op (merge_cell, drain_exhausted). Different stages,
# different failure modes: KNOWN_UNCOVERED answers "did we read it at all", and
# EXPECTED_SKIPS answers "having read it, did we assert on it". EXPECTED_SKIPS is
# already rot-proof via exact set equality in the test; do not merge the two.
KNOWN_UNCOVERED=(
  # The three replay-equivalence fixtures were excused here while this binding had
  # no harness. They are now REPLAYED (#lzreplaykt, src/main/kotlin/io/github/lazily/
  # Replay.kt + ReplayConformanceTest.kt), so the entries are gone rather than kept
  # as stale excuses — this script fails an excuse for a fixture the same run opens,
  # which is exactly what should happen to a gap that has been closed. `replay` is a
  # REQUIRED_AREA below for the same reason `codec` is: the replay exists, so
  # deleting the runner must turn something red.
  #
  # Register CRDTs (LWW / MV / PnCounter + the CellCrdt projection bit) are
  # implemented here, but this binding has no canonical replay for the new
  # registers corpus yet; the Registers coverage row is `~` until it does.
  "collections/registers_convergence.json"
  # The latest-durable per-key projection is replayed by
  # LatestDurableProjectionConformanceTest. These older FIFO-stream fixtures
  # describe a distinct egress contract that Kotlin does not yet implement.
  "egress/egress_generation_fence.json"
  "egress/egress_inflight_window.json"
  "egress/egress_ordered_ack.json"
  "egress/egress_retry_budget.json"
  # codec/frame_roundtrip_msgpack.json was excused here while lazily-kt spoke
  # only the `json` half of the frame-codec obligation. It is now REPLAYED
  # (#lzmsgpackseven, MsgpackCodec.kt + CodecConformanceTest.kt), so the entry
  # is gone rather than kept as a stale excuse — this script fails an excuse
  # for a fixture the same run opened, which is exactly what should happen to
  # a gap that has been closed.
  #
  # No lazily-kt runner drives the reliable-sync outbox-coalescing or
  # lease-eviction scenarios yet. Excused in every other binding for the same
  # reason.
  "reliable-sync/coalesce_bounds_outbox.json"
  "reliable-sync/liveness_lease_eviction.json"
  # The canonical journal-decoder trace has no Kotlin replay runner yet.
  "reliable-sync/outbox_journal_decode.json"
)

# --- rung 4: per-SCENARIO accounting (#lzscenariocoverage) -------------------
#
# KNOWN_UNCOVERED above asks only whether a fixture FILE was opened, and one
# scenario is enough to answer yes. A fixture carrying four scenarios of which a
# runner drives three is therefore green, counted as covered, and proving three
# quarters of what it claims. The assertion-key guards cannot see it either: they
# only bind blocks a runner reaches, so an unreplayed scenario contributes no
# unconsumed key and no unasserted key. Skipping a whole scenario is invisible to
# a guard that only inspects the scenarios you ran.
#
# So the runner writes a second RUNTIME ledger — build/conformance-scenarios-
# replayed.txt, one `fixture<TAB>id<TAB>id-source` line per scenario actually
# replayed (io.github.lazily.ConformanceScenarios) — and the check below compares
# it against the ids the fixtures carry on disk, in both directions.
#
# Ids resolve `id`, else `name`, identically in every binding. There is no
# positional fallback (#lzspecscenarioids) -- it used to exist because
# collections/mergecell_algebra.json
# carries no scenario identifier at all; it is REPORTED below rather than
# silently accepted, so the corpus gap stays visible and fixable upstream. Adding
# the missing identifiers is a shared-corpus change and does not belong here.
KNOWN_UNREPLAYED_SCENARIOS=()

# excuseScenario <fixture> <scenario-id> <reason>
#
# Declares that this binding does not replay one scenario of a fixture it DOES
# open, and says why. Checked in both directions below, exactly as
# KNOWN_UNCOVERED is: excusing a scenario this same run replayed, or naming an id
# the fixture does not carry, fails as a stale excuse. Prefer implementing the
# scenario — a known-skipped scenario is the work this guard exists to force, and
# an excuse is the fallback for something the binding genuinely cannot express.
excuseScenario() {
  if [ -z "${3:-}" ]; then
    echo "ERROR: excuseScenario('${1:-}', '${2:-}') has no reason — an excuse without" >&2
    echo "       one is an allowlist entry wearing a function call." >&2
    exit 1
  fi
  KNOWN_UNREPLAYED_SCENARIOS+=("$1|$2|$3")
}

# (empty — every scenario in every fixture lazily-kt opens is replayed)

MANIFEST="${1:-${LAZILY_CONFORMANCE_MANIFEST:-build/conformance-fixtures-loaded.txt}}"
SCENARIO_LEDGER="${2:-${LAZILY_CONFORMANCE_SCENARIO_LEDGER:-build/conformance-scenarios-replayed.txt}}"

# Corpus areas lazily-kt expects the canonical checkout to contain. This is a
# CORPUS-shape tripwire, not a coverage check — the per-fixture accounting below
# is what proves replays ran. It is kept because per-fixture enumeration is blind
# in exactly one direction: if the lazily-spec sibling is a stale, partial, or
# half-cloned checkout, whole areas simply vanish from the enumeration and the
# guard passes over a corpus a fraction of its real size. That silent-shrink case
# was the one legitimate job the old MIN_FIXTURES floor did, and asserting area
# names against the corpus does it without a number that rots.
REQUIRED_AREAS=(
  agent-doc
  # `codec` was ABSENT here while CodecConformanceTest.kt replayed
  # frame_roundtrip_json.json every run: the replay existed, and nothing required
  # it, so deleting that runner would have left this guard green and the
  # coverage matrix still claiming the reference codec. Found by lazily-spec's
  # coverage-claim guard (#coveragejsonscores); the same hole cost lazily-cpp
  # two undetected codec defects.
  codec
  collections
  coordination
  crdt-tree
  distributed
  egress
  familysync
ingress
ipc
lossless-tree
  materialization
  membership
  message-passing
  presence
  protobuf
  rateshape
  reactive-graph
  receipts
  reliable-sync
  replay
  resilience
  service
  signaling
  statechart
  stdlib
  temporal
  windowing
)

# Areas this binding deliberately opens NOTHING from. Together with
# REQUIRED_AREAS this must partition the canonical corpus exactly; the guard
# below checks both directions and rejects overlap. Kotlin currently opens every
# area, so the honest complement is empty rather than an omitted assertion.
EXCUSED_AREAS=()

if [ ! -s "$MANIFEST" ]; then
  echo "FAIL: no conformance manifest at $MANIFEST." >&2
  echo "      Run the suite with LAZILY_CONFORMANCE_MANIFEST set so the recorder" >&2
  echo "      attaches. An absent manifest is missing evidence, not evidence of" >&2
  echo "      absence." >&2
  exit 1
fi
require_run_id "$MANIFEST" "the fixture manifest"
require_evidence_records "$MANIFEST" "fixture-opened"
OPENED="$(evidence_lines "$MANIFEST" | sort -u)"

missing=0

for area in "${REQUIRED_AREAS[@]}"; do
if [ "$area" = "ipc" ]; then
area_fixture="$(find "$SPEC_DIR" -maxdepth 1 -type f \
  \( -name 'arena_blob.json' -o -name 'snapshot_*.json' -o -name 'delta_*.json' \) \
  -print -quit 2>/dev/null)"
else
area_fixture="$(find "$SPEC_DIR/$area" -name '*.json' -print -quit 2>/dev/null)"
fi
if [ -z "$area_fixture" ]; then
echo "ERROR: canonical corpus has no fixtures under area '$area'." >&2
echo "       The lazily-spec checkout at $SPEC_DIR is stale or partial; coverage" >&2
echo "       computed against it would silently understate the real corpus." >&2
missing=$((missing + 1))
fi
done

# The two area arrays must PARTITION the corpus. Without this check a new corpus
# directory can sit in neither array, making every array-driven coverage rung
# blind to it while the guard still reports success. `ipc` is the one deliberate
# non-directory area: its fixtures live at the corpus root and are matched above.
corpus_areas="$(cd "$SPEC_DIR" && find . -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)"
while IFS= read -r area; do
  [ -n "$area" ] || continue
  listed=0
  for known in "${REQUIRED_AREAS[@]}" "${EXCUSED_AREAS[@]}"; do
    if [ "$known" = "$area" ]; then listed=1; break; fi
  done
  if [ "$listed" -eq 0 ]; then
    echo "ERROR: conformance area '$area' is in neither REQUIRED_AREAS nor EXCUSED_AREAS." >&2
    echo "       Classify it explicitly so corpus growth cannot bypass the audit." >&2
    missing=$((missing + 1))
  fi
done <<< "$corpus_areas"

for area in "${REQUIRED_AREAS[@]}" "${EXCUSED_AREAS[@]}"; do
  [ "$area" = "ipc" ] && continue
  if [ ! -d "$SPEC_DIR/$area" ]; then
    echo "ERROR: area '$area' is listed here but is not in the canonical corpus." >&2
    echo "       It was renamed or removed upstream — prune the stale entry." >&2
    missing=$((missing + 1))
  fi
done

for required in "${REQUIRED_AREAS[@]}"; do
  for excused in "${EXCUSED_AREAS[@]}"; do
    if [ "$required" = "$excused" ]; then
      echo "ERROR: conformance area '$required' is both REQUIRED and EXCUSED." >&2
      echo "       The two arrays must be disjoint to form an exact partition." >&2
      missing=$((missing + 1))
    fi
  done
done

total=0
covered=0
excused_count=0
# Areas witnessed by a fixture that is BOTH listed by the corpus and recorded as
# opened. Accumulated here rather than derived from the manifest's own strings so
# the positive-evidence block at the bottom cannot be satisfied by paths that name
# nothing on disk.
COVERED_AREA_LIST=""
while IFS= read -r fixture; do
  total=$((total + 1))
  # Here-string, NOT a pipe. With `set -o pipefail`, `printf ... | grep -q` reports
  # FAILURE when grep matches: grep -q exits immediately on the first hit, printf
  # takes SIGPIPE writing the rest, and pipefail surfaces printf's death as the
  # pipeline's status. The check then inverts — every covered fixture is reported
  # missing.
  if grep -qxF "$fixture" <<< "$OPENED"; then
    covered=$((covered + 1))
case "$fixture" in
*/*) COVERED_AREA_LIST+="${fixture%%/*}"$'\n' ;;
arena_blob.json|snapshot_*.json|delta_*.json) COVERED_AREA_LIST+='ipc'$'\n' ;;
*) COVERED_AREA_LIST+='(unknown-root)'$'\n' ;;
esac
    continue
  fi
  excused=0
  for known in "${KNOWN_UNCOVERED[@]:-}"; do
    if [ "$known" = "$fixture" ]; then excused=1; break; fi
  done
  if [ "$excused" -eq 1 ]; then
    excused_count=$((excused_count + 1))
  else
    echo "ERROR: canonical fixture '$fixture' was NOT opened by the suite." >&2
    echo "       A runner may still name it in source while no longer reading it —" >&2
    echo "       that is the drift this manifest exists to catch. Replay it, or add" >&2
    echo "       it to KNOWN_UNCOVERED with a reason." >&2
    missing=$((missing + 1))
  fi
done < <(cd "$SPEC_DIR" && find . -name '*.json' | sed 's|^\./||' | sort)

# The evidence channel guards itself. Every recorded id must resolve against the
# corpus root; otherwise the manifest was truncated or interleaved in transit,
# and coverage computed from it cannot be trusted.
while IFS= read -r id; do
  [ -n "$id" ] || continue
  if [ ! -f "$SPEC_DIR/$id" ]; then
    echo "ERROR: manifest records '$id', which names no file in $SPEC_DIR." >&2
    echo "       The recorder is dropping or interleaving writes; coverage computed" >&2
    echo "       from this manifest cannot be trusted." >&2
    missing=$((missing + 1))
  fi
done <<< "$OPENED"

# A stale allowlist is its own drift, in two directions.
#
# 1. An entry naming a fixture that no longer exists means the corpus moved and
#    nobody updated the excuse.
# 2. An entry naming a fixture the suite DOES open is a stale excuse: the gap it
#    claims was closed, and the excuse outlived it. That rot understates coverage,
#    which is the direction nobody files a bug about — you do not report missing
#    coverage you have been told you lack — and it buries the real gaps in noise.
#    Worse, a stale excuse silently disarms the guard for that fixture: the day
#    the replay really does stop running, the excuse absorbs it.
#
# The open test below uses the SAME `grep -qxF ... <<< "$OPENED"` comparison as the
# covered-check above, deliberately: if the two ever disagreed, a fixture could be
# both counted as covered and excused as uncovered in one run.
for known in "${KNOWN_UNCOVERED[@]:-}"; do
  if [ ! -f "$SPEC_DIR/$known" ]; then
    echo "ERROR: KNOWN_UNCOVERED lists '$known', which is not in the canonical corpus." >&2
    missing=$((missing + 1))
    continue
  fi
  if grep -qxF "$known" <<< "$OPENED"; then
    echo "ERROR: KNOWN_UNCOVERED lists '$known', but the suite DID open it." >&2
    echo "       The excuse is stale — the gap it claims no longer exists. Delete" >&2
    echo "       this entry from KNOWN_UNCOVERED. Leaving it there understates this" >&2
    echo "       binding's coverage and disarms the guard for that fixture." >&2
    missing=$((missing + 1))
  fi
done

# ===========================================================================
# rung 4 — per-SCENARIO replay accounting (#lzscenariocoverage)
# ===========================================================================
#
# Everything above proves a fixture FILE was opened. This proves every scenario
# inside it was actually replayed. The two ledgers are deliberately separate
# evidence channels written by the same runner: the manifest records reads, the
# scenario ledger records replays, and only the second can see a fixture that was
# opened and then half-driven.

if ! command -v jq >/dev/null 2>&1; then
  echo "FAIL: jq is required to enumerate the corpus's scenario ids independently" >&2
  echo "      of the runner. Install jq (it ships on ubuntu-latest runners)." >&2
  exit 1
fi

# A missing ledger is missing EVIDENCE and fails, for the same reason a missing
# manifest does: it means the suite ran without the recorder attached, not that
# there was nothing to record.
if [ ! -s "$SCENARIO_LEDGER" ]; then
  echo "FAIL: no scenario ledger at $SCENARIO_LEDGER." >&2
  echo "      Run the suite with LAZILY_CONFORMANCE_SCENARIO_LEDGER set so" >&2
  echo "      io.github.lazily.ConformanceScenarios attaches its recorder. An absent" >&2
  echo "      ledger is missing evidence, not evidence of absence." >&2
  exit 1
fi
require_run_id "$SCENARIO_LEDGER" "the scenario ledger"
require_evidence_records "$SCENARIO_LEDGER" "scenario-replayed"
LEDGER="$(evidence_lines "$SCENARIO_LEDGER" | sort -u)"
LEDGER_KEYS="$(cut -f1,2 <<< "$LEDGER")"
TAB=$'\t'

# Resolve a fixture's scenario ids straight from the corpus on disk: `id`, else
# `name`. There is no positional fallback (#lzspecscenarioids): an id derived
# from a POSITION silently rebinds to a different scenario when the corpus array
# is reordered, so an unidentified scenario is reported rather than given an
# invented id. This must stay independent of the runner — the whole point is that
# the corpus, not the binding, says what there was to replay.
scenario_ids() {
  # A manifest or ledger entry naming a file this corpus does not hold is
  # already reported by the self-guards above, so resolve it to "no scenarios"
  # rather than letting jq die on the open. Under `set -e` that death aborts the
  # whole script with status 2 part-way through the diagnosis: the summary gate
  # never runs, every problem queued behind it is lost, and — the reason it
  # matters here — the positive-evidence block at the bottom becomes unreachable
  # in exactly the empty/half-cloned-corpus case it exists to catch.
  [ -f "$SPEC_DIR/$1" ] || return 0
  jq -r '
    def identifier: if type == "string" and (gsub("\\s"; "") != "") then . else null end;
    if (type == "object") and ((.scenarios | type) == "array")
    then (
      .scenarios
      | to_entries[]
      | ((.value.id? | identifier) // (.value.name? | identifier) // "!UNIDENTIFIED!\(.key)")
    )
    else empty end
  ' "$SPEC_DIR/$1"
}

sc_total=0
sc_replayed=0
sc_excused=0
sc_fixtures=0
while IFS= read -r fixture; do
  [ -n "$fixture" ] || continue
  ids="$(scenario_ids "$fixture")"
  [ -n "$ids" ] || continue
  sc_fixtures=$((sc_fixtures + 1))
  while IFS= read -r id; do
    [ -n "$id" ] || continue
    sc_total=$((sc_total + 1))
    # An unidentified scenario is a corpus defect, not an id to invent
    # (#lzspecscenarioids). Booking it by POSITION would silently rebind that
    # ledger entry to a different scenario on any corpus reorder.
    case "$id" in
      '!UNIDENTIFIED!'*)
        echo "ERROR: '$fixture' scenario at index ${id#!UNIDENTIFIED!} carries neither" >&2
        echo "       \`id\` nor \`name\`. The ledger would record it by POSITION, which" >&2
        echo "       silently rebinds on a corpus reorder. Give it a stable id upstream" >&2
        echo "       in lazily-spec (#lzspecscenarioids)." >&2
        missing=$((missing + 1))
        continue
        ;;
    esac
    if grep -qxF "$fixture$TAB$id" <<< "$LEDGER_KEYS"; then
      sc_replayed=$((sc_replayed + 1))
      continue
    fi
    hit=0
    for entry in "${KNOWN_UNREPLAYED_SCENARIOS[@]:-}"; do
      [ -n "$entry" ] || continue
      if [ "${entry%%|*}" = "$fixture" ]; then
        rest="${entry#*|}"
        if [ "${rest%%|*}" = "$id" ]; then hit=1; break; fi
      fi
    done
    if [ "$hit" -eq 1 ]; then
      sc_excused=$((sc_excused + 1))
    else
      echo "ERROR: '$fixture' scenario '$id' was NOT replayed." >&2
      echo "       The fixture was opened, so every guard above it reports green while" >&2
      echo "       this scenario proves nothing: an unreplayed scenario contributes no" >&2
      echo "       unconsumed key and no unasserted key, so the assertion-key guards" >&2
      echo "       cannot see it either. Replay it, or excuseScenario it with a reason." >&2
      missing=$((missing + 1))
    fi
  done <<< "$ids"
done <<< "$OPENED"

# The scenario ledger guards itself, exactly as the manifest does above: every
# recorded entry must name a fixture in the corpus AND an id that fixture really
# carries. A ledger entry the corpus does not recognise means the id resolution
# drifted from the shared order, and coverage computed from it is fiction.
while IFS= read -r line; do
  [ -n "$line" ] || continue
  lf="${line%%$TAB*}"
  lrest="${line#*$TAB}"
  lid="${lrest%%$TAB*}"
  if [ ! -f "$SPEC_DIR/$lf" ]; then
    echo "ERROR: scenario ledger records '$lf', which names no file in $SPEC_DIR." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$lid" <<< "$(scenario_ids "$lf")"; then
    echo "ERROR: scenario ledger records '$lf' scenario '$lid', which the fixture does" >&2
    echo "       not carry. The runner's id resolution has drifted from the shared" >&2
    echo "       'id -> name' order; the ledger cannot be compared to the corpus." >&2
    missing=$((missing + 1))
  fi
done <<< "$LEDGER"

# Both directions, same rule as KNOWN_UNCOVERED: a scenario excuse for something
# this run DID replay, or for an id the fixture does not carry, is stale. Stale
# excuses understate coverage and silently disarm the guard for that scenario.
for entry in "${KNOWN_UNREPLAYED_SCENARIOS[@]:-}"; do
  [ -n "$entry" ] || continue
  ef="${entry%%|*}"
  erest="${entry#*|}"
  eid="${erest%%|*}"
  ereason="${erest#*|}"
  if [ ! -f "$SPEC_DIR/$ef" ]; then
    echo "ERROR: excuseScenario names '$ef', which is not in the canonical corpus." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$ef" <<< "$OPENED"; then
    echo "ERROR: excuseScenario names '$ef', a fixture the suite never opens. A whole" >&2
    echo "       unopened fixture belongs in KNOWN_UNCOVERED, not here — as written the" >&2
    echo "       excuse hides nothing and rots in a second place." >&2
    missing=$((missing + 1))
    continue
  fi
  if ! grep -qxF "$eid" <<< "$(scenario_ids "$ef")"; then
    echo "ERROR: excuseScenario '$ef' / '$eid' ($ereason) names a scenario the fixture" >&2
    echo "       does not carry. The excuse is stale — the corpus renamed or removed it." >&2
    missing=$((missing + 1))
    continue
  fi
  if grep -qxF "$ef$TAB$eid" <<< "$LEDGER_KEYS"; then
    echo "ERROR: excuseScenario '$ef' / '$eid' ($ereason) is stale — the suite DID replay" >&2
    echo "       that scenario. Delete the excuse. Leaving it there understates this" >&2
    echo "       binding's coverage and disarms the guard the day the replay stops." >&2
    missing=$((missing + 1))
  fi
done

# ===========================================================================
# Positive evidence — the examined population itself (#lzvacuousrun)
# ===========================================================================
#
# Every rung above is a NEGATIVE check: it walks a population and reports the
# problems it finds. All of them are vacuously satisfied by an empty population.
# Zero fixtures in the corpus means zero uncovered fixtures; zero fixtures in the
# manifest means zero stale excuses and zero unreplayed scenarios. The loops
# cannot tell "nothing is wrong" from "nothing was examined", and neither can the
# OK line they license.
#
# This block is deliberately NOT a reinstated MIN_FIXTURES floor. That floor was
# removed for good reason (see the header): it was a number that rots, and 131
# replays against a floor of 117 meant fourteen replays could stop running with
# CI green. Per-fixture accounting replaced it and is strictly stronger — for
# everything except magnitude. So the magnitude assertion here is stated in the
# same AREA vocabulary this guard already uses, and mostly without numbers:
#
#   1. The REQUIRED_AREAS list must be non-empty. An area tripwire with no areas
#      passes over any corpus at all, so the guard that protects the enumeration
#      needs its own emptiness check.
#   2. The corpus enumeration and the opened set must both be non-empty.
#   3. Every REQUIRED_AREA must contribute at least one OPENED fixture. The loop
#      near the top asserts the area exists in the CORPUS; this asserts the RUN
#      actually read something from it. That turns the area list from a
#      corpus-shape tripwire into positive evidence about the examined
#      population, and it is the assertion that cannot rot into a stale number:
#      it is derived from the area names, which are also what the corpus check
#      already uses.
#   4. One calibrated floor on the count of distinct opened areas, so a corpus
#      that loses areas outright (a partial checkout that also drops them from
#      REQUIRED_AREAS) still cannot slip through at a fraction of its real size.
#
# Do not lower MIN_OPENED_AREAS to fix a red run — a drop here means the corpus
# or the recorder shrank, which is the finding, not the obstacle.

if [ "${#REQUIRED_AREAS[@]}" -eq 0 ]; then
  echo "ERROR: REQUIRED_AREAS is empty — the corpus-shape tripwire asserts nothing." >&2
  echo "       An area guard with no areas is green over any corpus, including none." >&2
  missing=$((missing + 1))
fi
if [ "$total" -eq 0 ]; then
  echo "ERROR: the corpus at $SPEC_DIR enumerated ZERO fixtures." >&2
  echo "       Every per-fixture check above is vacuously green over an empty" >&2
  echo "       population (#lzvacuousrun)." >&2
  missing=$((missing + 1))
fi
if [ "$covered" -eq 0 ]; then
  echo "ERROR: the suite OPENED zero canonical fixtures." >&2
  echo "       The manifest exists but records nothing this corpus recognises, so" >&2
  echo "       coverage, allowlist-rot and scenario accounting all compared nothing." >&2
  missing=$((missing + 1))
fi

# Distinct top-level areas witnessed by a fixture the corpus lists AND the run
# opened. Root-level fixtures (snapshot_*/delta_*/arena_blob) form the explicit
# `ipc` area. An unknown future root fixture is deliberately not credited to it.
OPENED_AREAS="$(sort -u <<< "$COVERED_AREA_LIST" | grep . || true)"
opened_area_count=0
[ -n "$OPENED_AREAS" ] && opened_area_count="$(grep -c . <<< "$OPENED_AREAS")"

for area in "${REQUIRED_AREAS[@]}"; do
  if ! grep -qxF "$area" <<< "$OPENED_AREAS"; then
    echo "ERROR: area '$area' is required, but the suite OPENED no fixture in it." >&2
    echo "       The corpus check above only proves the area EXISTS. This proves the" >&2
    echo "       run examined it — without that, an area can go entirely dark while" >&2
    echo "       every negative check stays green over the fixtures it never saw." >&2
    missing=$((missing + 1))
  fi
done

# This floor tracks WHAT THE RUN ACTUALLY OPENS, exactly — no margin, no slack.
# Pinned 2026-08-09 at 26 distinct opened areas, re-derived from a full
# `./gradlew test --no-daemon --rerun-tasks` plus this guard on the commit CI run
# 31343632650 was green over (that run reported the same 142/150 fixtures,
# 153/153 scenarios and 18/18 assertion blocks, so it examined the same corpus).
#
# It previously sat at 22 against 26, on the theory that slack makes an upstream
# area rename a one-line corpus edit rather than a forced number change. That
# reasoning does not hold: a rename ALSO fails the REQUIRED_AREAS loop directly
# above, so the list has to be edited either way — the slack bought nothing and
# let four areas go entirely dark with this guard still green. Do not restore it,
# and do not raise this floor "by however many areas a change adds" while leaving
# an old margin in place; that convention is what let the fixture and scenario
# floors in the sibling bindings rot to 40 replays behind reality
# (#lzscenariofloordrift). Set it to the count the `area coverage OK` line below
# reports from a COMPLETED CI run, which examines the published corpus rather
# than a working tree (#lzspecpushbeforebindings).
#
# Raised to 28 on 2026-09-11 with the replay-equivalence harness (#lzreplaykt):
# `replay` is a 28th area this run now opens, and it is REQUIRED above, so the
# floor moves with it rather than keeping a margin the new area could hide in.
#
# Note the number is necessarily >= the 27 REQUIRED_AREAS, since each of those
# must contribute an opened fixture; the extra is an area the run opens without
# requiring. Do not lower this to fix a red run — the shrink is the finding.
MIN_OPENED_AREAS="${MIN_OPENED_AREAS:-28}"
if [ "$opened_area_count" -lt "$MIN_OPENED_AREAS" ]; then
  echo "ERROR: the suite OPENED fixtures in only $opened_area_count corpus area(s)," >&2
  echo "       expected >= $MIN_OPENED_AREAS. The corpus is a partial checkout, or the" >&2
  echo "       recorder detached part-way through the run. Do not lower" >&2
  echo "       MIN_OPENED_AREAS to fix this — the shrink is the finding." >&2
  missing=$((missing + 1))
fi

# The scenario rung walks the scenarios of OPENED fixtures, so it is vacuous in
# exactly the same way one level down: no opened scenario-bearing fixture means
# no scenario to find unreplayed.
if [ "$sc_fixtures" -eq 0 ] || [ "$sc_total" -eq 0 ]; then
  echo "ERROR: ZERO scenarios were enumerated across the opened fixtures." >&2
  echo "       The per-scenario rung is vacuously green over an empty population." >&2
  missing=$((missing + 1))
fi

# Positive runtime evidence for scenario replay magnitude. The exact per-id set
# checks above remain the primary proof; this floor independently refuses a run
# whose scenario recorder silently shrank while still producing a non-empty
# ledger. Derived from the completed gate's own 157/157 report. Override only for
# the n+1 exactness probe; never lower it to repair a red run.
MIN_SCENARIOS="${MIN_SCENARIOS:-157}"
if [ "$sc_replayed" -lt "$MIN_SCENARIOS" ]; then
  echo "ERROR: the runtime ledger records only $sc_replayed replayed scenario(s)," >&2
  echo "       expected >= MIN_SCENARIOS=$MIN_SCENARIOS. The replay population shrank;" >&2
  echo "       do not lower the floor to repair this run (#lzscenariofloormissing)." >&2
  missing=$((missing + 1))
fi

# --- rung 0: was every fixture-level `assertions` block BOUND to a tracker? ---
#
# Every other rung here is scoped to a block a runner ALREADY OPENED. The unread
# check, the unasserted check and the prose ledger all live inside AssertionKeys,
# so a fixture-level `assertions` block that no runner ever constructs an
# AssertionKeys over reports NOTHING: its keys are not unread, because nothing was
# reading them. lazily-dart found two such blocks in its own suite — eight silent
# keys, including the invariant that a forwarded `from` is the server's registered
# peer id and never a client-supplied one. No grep finds that; the evidence is the
# absence of a call, so it has to be recorded at runtime like every other rung
# here (#lznullformblind).
#
# ConformanceFixtures inventories the block when it reads the fixture, and
# AssertionKeys books it bound when it is constructed over it — matched by
# CONTENT, not by the `where` label a runner picks for itself.
BLOCK_LEDGER="${3:-${LAZILY_CONFORMANCE_ASSERTION_BLOCK_LEDGER:-build/conformance-assertion-blocks.txt}}"
# The committed per-site excuse ledger. A FILE rather than a bash array: at this
# many hundred entries an array would dominate this script, and lazily-spec's
# check-corpus-floors.mjs classifies the top-level arrays here — a third one
# holding site ids would be a new shape for it to guess at.
UNBOUND_LEDGER="${LAZILY_CONFORMANCE_UNBOUND_BLOCK_LEDGER:-$(dirname "$0")/conformance-unbound-blocks.txt}"
if [ ! -f "$BLOCK_LEDGER" ]; then
  echo "ERROR: assertion-block ledger not found at $BLOCK_LEDGER." >&2
  echo "       That is missing EVIDENCE, not evidence of absence: the suite ran" >&2
  echo "       without the rung-0 recorder attached (#lzvacuousrun)." >&2
  missing=$((missing + 1))
else
  require_run_id "$BLOCK_LEDGER" "the assertion-block ledger"
  # This rung already asks emptiness of the RECORDS below the stamp, so it is
  # the one evidence read the run-id stamp did NOT weaken
  # (#lzstampsatisfiesnonempty) — a stamp-only ledger counts zero here and is
  # refused. Deliberately NOT switched to require_evidence_records: this section
  # accumulates into `missing` so every rung-0 diagnosis is reported in one pass,
  # where the manifest and scenario reads exit immediately because nothing below
  # them can be computed. Do not "tidy" this into a `[ -s ]` test: a stamped file
  # is non-empty whatever it records.
  blocks_total=$(evidence_lines "$BLOCK_LEDGER" | grep -c . || true)
  if [ "$blocks_total" -eq 0 ]; then
    echo "ERROR: ZERO assertion blocks were inventoried in $BLOCK_LEDGER." >&2
    echo "       Rung 0 is vacuously green over an empty population. The file is" >&2
    echo "       stamped with this run's id and is therefore non-empty — it records" >&2
    echo "       nothing (#lzstampsatisfiesnonempty)." >&2
    missing=$((missing + 1))
  fi

  # The bind rung, reconciled against the committed per-site excuse ledger in
  # BOTH directions (#lzktblockwalk). An UNBOUND site missing from the ledger is
  # an error, and a ledger entry the run BOUND — or that the corpus no longer
  # carries at all — is a stale excuse and also an error. Neither direction has
  # slack, and the ledger is per SITE so each entry dies on its own.
  if [ ! -f "$UNBOUND_LEDGER" ]; then
    echo "ERROR: per-site unbound-block ledger not found at $UNBOUND_LEDGER." >&2
    echo "       Without it every unbound site would pass unchallenged, which is the" >&2
    echo "       opposite of what this rung is for (#lznullformblind)." >&2
    missing=$((missing + 1))
  else
    # awk, not `grep -v ... | grep .` (#lzgrepcpipefail). Both greps report
    # "nothing matched" with exit 1, and under `set -euo pipefail` that status is
    # the PIPELINE's, and the pipeline's is the ASSIGNMENT's — so a ledger holding
    # only its header comments killed this script dead on this line, before any
    # rung below could say a word about it. Measured: two OK lines, then exit 1
    # with no diagnostic at all. That state is not exotic, it is this ladder's
    # GOAL: drain the ledger by binding every site and the guard stopped being
    # able to report it. awk filters the same two line classes and exits 0 on an
    # empty result, so "no excused sites" stays a measurement instead of a status.
    # Emptiness is legitimate here and is already handled below: `unexcused`,
    # `stale_bound` and `stale_gone` guard it, and the count at the end tests
    # `[ -n ]` first. Do not "simplify" either line back to a grep pipeline.
    excused_blocks="$(awk '/^[[:space:]]*#/ { next } /./ { print }' "$UNBOUND_LEDGER" | cut -f1 | sort -u)"
    reasonless="$(awk -F'\t' '/^[[:space:]]*#/ { next } !/./ { next } NF < 2 || $2 ~ /^[[:space:]]*$/ { print $1 }' "$UNBOUND_LEDGER")"
    if [ -n "$reasonless" ]; then
      echo "ERROR: unbound-block ledger entries with NO reason:" >&2
      echo "$reasonless" | sed 's/^/         /' >&2
      echo "       An excuse without a reason is an allowlist entry. Say why the site" >&2
      echo "       cannot be bound, or bind it." >&2
      missing=$((missing + 1))
    fi
    run_unbound="$(evidence_lines "$BLOCK_LEDGER" | awk -F'\t' '$2 == "UNBOUND" { print $1 }' | sort -u)"
    run_bound="$(evidence_lines "$BLOCK_LEDGER" | awk -F'\t' '$2 == "bound" { print $1 }' | sort -u)"
    all_sites="$(evidence_lines "$BLOCK_LEDGER" | cut -f1 | sort -u)"

    unexcused="$(comm -23 <(printf '%s\n' "$run_unbound" | grep . || true) <(printf '%s\n' "$excused_blocks" | grep . || true))"
    if [ -n "$unexcused" ]; then
      echo "ERROR: assertion-block site(s) that NO runner bound to a tracker, and that" >&2
      echo "       $UNBOUND_LEDGER does not excuse:" >&2
      echo "$unexcused" | sed 's/^/         /' >&2
      echo "       Their keys are silent — not unread, because nothing reads them —" >&2
      echo "       so every other rung passes over them (#lznullformblind). Bind the" >&2
      echo "       block with AssertionKeys and assert its keys, or ledger the site" >&2
      echo "       with a real reason." >&2
      missing=$((missing + 1))
    fi

    stale_bound="$(comm -12 <(printf '%s\n' "$excused_blocks" | grep . || true) <(printf '%s\n' "$run_bound" | grep . || true))"
    if [ -n "$stale_bound" ]; then
      echo "ERROR: unbound-block ledger excuses site(s) this run BOUND:" >&2
      echo "$stale_bound" | sed 's/^/         /' >&2
      echo "       The excuse is stale — the gap it claims was closed. Delete these" >&2
      echo "       entries. Leaving them understates this binding's coverage and" >&2
      echo "       disarms the rung for those sites the day a bind really goes away." >&2
      missing=$((missing + 1))
    fi

    stale_gone="$(comm -23 <(printf '%s\n' "$excused_blocks" | grep . || true) <(printf '%s\n' "$all_sites" | grep . || true))"
    if [ -n "$stale_gone" ]; then
      echo "ERROR: unbound-block ledger names site(s) the run inventoried NOWHERE:" >&2
      echo "$stale_gone" | sed 's/^/         /' >&2
      echo "       Either the corpus moved that block and nobody updated the excuse," >&2
      echo "       or the walk stopped reaching it — and an excuse for a site nothing" >&2
      echo "       declares hides the second case completely." >&2
      missing=$((missing + 1))
    fi
    excused_block_count=0
    [ -n "$excused_blocks" ] && excused_block_count="$(printf '%s\n' "$excused_blocks" | grep -c . || true)"
    bound_block_count=0
    [ -n "$run_bound" ] && bound_block_count="$(printf '%s\n' "$run_bound" | grep -c . || true)"
  fi
fi

# --- the ledger's two reason CLASSES, enforced rather than documented -------
#
# The reconciliation above proves every unbound site is ledgered and every ledger
# entry is really unbound. That is not enough on its own: to those checks a
# `bind-pending` entry is just an accepted excuse, so the bind-pending count could
# GROW — a new runner area could add sites to the ledger and stay green, which is
# a slack floor wearing a reason string and exactly the rot this ladder refuses
# (#lzktblockwalk). Neither class is excused by a number any more: one is DERIVED
# and the other is covered by an EXACT SIZE PIN over the whole ledger
# (#lzledgerceiling, made exact in #lzledgerratchet).
#
#   unreachable    DERIVED, not counted. These sites exist because
#                  ReactiveGraphConformanceTest.EXPECTED_SKIPS names six fixtures
#                  whose replay stops on an op this binding does not model, so no
#                  tracker can reach any block in them. The expected set is read
#                  out of that map and compared BOTH ways against the run's own
#                  unbound sites. The day a `merge_cell` op lands upstream and a
#                  fixture leaves EXPECTED_SKIPS, its entries fail as stale
#                  instead of surviving as folklore — and there is no number here
#                  for anyone to re-pin.
#
#   bind-pending   carries no typed count of its OWN, as of #lzledgerceiling. A
#                  count of just this class would MIRROR the population the set
#                  equality above already fixes: if the ledger set and the run's
#                  unbound set are equal then their counts are equal, so a
#                  per-class number restates the equality instead of adding to it
#                  (#lzrsbindpending). The whole-ledger SIZE below is a different
#                  measurement — it compares the ledger against a committed
#                  constant, which the attack cannot move — and it covers BOTH
#                  classes, where a bind-pending count reached only the 423
#                  reachable ones.
#
#                  Dropping the per-class count is only safe because this ledger is
#                  ENUMERATED — one literal `<fixture>|<path>` per line, compared
#                  as a set with `comm` and with exact string membership in the
#                  readers below. There is no glob, prefix or regex anywhere in the
#                  excuse path, so the excused population cannot widen without the
#                  diff showing every new site. A ledger matched by PATTERN would
#                  still need a number, because a pattern can widen silently and
#                  set equality cannot see it.
# The ledger's SIZE, pinned as an EXACT EQUALITY, because set equality alone has a
# hole (#lzledgerceiling) and a one-sided bound on the size self-disables
# (#lzledgerratchet).
#
# The hole: both directions above only check that the ledger and the run AGREE,
# and that is satisfied by ANY CONSISTENT PAIR. A commit that detaches N binds and
# writes the N matching entries passes forward, backward and stale-gone. The
# magnitude rung does not see it either — the sites are still DECLARED, merely no
# longer bound. What is missing is therefore not a count of what IS excused but a
# comparison of the ledger against something the commit does not also get to
# rewrite. That is what a committed constant is.
#
# Why EQUALITY and not a ceiling. This rung first landed as `size > pin` fails.
# That operator refuses the attack only while slack is zero. One migration later
# the ledger has shrunk, the pin has not, slack is >= 1, and the same detach-plus-
# excuse commit passes again — so a ceiling STARTS at zero slack and ACCUMULATES
# slack with every migration, converging on the slack floor this whole ladder was
# built to replace (a floor of 30 against an actual 722 never fired, and so was
# never updated). An equality has no slack by construction and cannot drift
# quietly, because a stale value FAILS in both directions. A number that fails
# when stale is a ratchet, not drift.
#
# So both directions are things a person must see:
#
#   GROWTH  an excuse was added. Bind the block instead. Raising the pin is
#           legitimate but must be deliberate and visible in the diff — the real
#           case is a corpus that gains a genuinely unbindable fixture, which
#           belongs in the `unreachable` class with a reason, and expects to be
#           asked why the capability cannot exist. Never to park a `bind-pending`
#           site: that is the laundering this guard refuses.
#
#   SHRINK  sites were migrated and the pin was not lowered in the same commit.
#           Lower it. This half is the signal a ceiling discards, and it is the
#           reason the excused population cannot quietly acquire headroom.
#
# Pinned at 299 after the coordination and ingress migrations (#lzktbindpending). In normal work
# it only ever moves DOWNWARD, one step per migrated site, in the migrating commit.
# Env-overridable so the guard itself can be probed without editing the pin — but
# the override must be a number. An empty or malformed value FAILS CLOSED rather
# than silently reverting to the default, because a pin that can be switched off
# by exporting a typo is not pinned.
#
# ONE parse for the whole family (#lzpinparsestrict): a NON-EMPTY run of bare
# ASCII digits `0`-`9`, and nothing else. This binding already had the rule, and
# it is the only one of the ten that got the unset-versus-empty distinction right
# from the start: `${VAR-299}` substitutes the default only when the variable is
# UNSET, where `${VAR:-299}` would also have swallowed `export
# EXPECTED_LEDGERED_BLOCKS=` and a typo that expanded to nothing. That is why the
# empty case is spelled in the pattern below rather than left to the default.
#
# The digit class is enumerated rather than written `0-9`, because a bracket
# RANGE is resolved by the locale's collation and `0-9` is only guaranteed to be
# the ten ASCII digits under LC_COLLATE=C. An enumeration is the same ten in every
# locale, which is what the family rule says.
EXPECTED_LEDGERED_BLOCKS="${EXPECTED_LEDGERED_BLOCKS-299}"
case "$EXPECTED_LEDGERED_BLOCKS" in
  '' | *[!0123456789]*)
    echo "ERROR: EXPECTED_LEDGERED_BLOCKS is not a non-negative integer in bare" \
         "ASCII digits (#lzpinparsestrict):" \
         "'$EXPECTED_LEDGERED_BLOCKS'." >&2
    echo "       The ledger size is pinned as an exact equality, so an unreadable pin" >&2
    echo "       has nothing to compare against. Falling back to the built-in default" >&2
    echo "       here would let an exported typo disarm the rung while the build stayed" >&2
    echo "       green, so this fails closed instead." >&2
    exit 1
    ;;
esac
# Overridable ONLY so the derivation itself can be probed against a doctored copy
# of the map (a fixture removed, the map renamed). Never point it at anything but
# the real runner in a real run.
REACTIVE_GRAPH_RUNNER="${LAZILY_CONFORMANCE_SKIPS_SOURCE:-src/test/kotlin/io/github/lazily/ReactiveGraphConformanceTest.kt}"

BLOCK_CLASS_PY="$(cat <<'PY'
import os
import re
import sys

block_ledger, unbound_ledger, runner_src = sys.argv[1], sys.argv[2], sys.argv[3]
# The shell already refused a non-numeric pin. Re-check it on the SAME terms
# rather than assume, so this reader fails closed if it is ever called directly.
#
# ONE parse for the whole family (#lzpinparsestrict): a NON-EMPTY run of bare
# ASCII digits `0`-`9`, and nothing else. Deliberately stricter than the bare
# `int()` this used to be, and than `str.isdigit()`, because each of those
# silently accepts a number nobody wrote: `int("1_0")` is 10 (PEP 515
# separators), `int(" 7 ")` is 7, `int("+1")` is 1, and `"\u0663".isdigit()` is
# true for the Arabic-Indic three. The shell's `case` refuses all of those, so
# this only mattered on a direct call — but a fallback reader that is looser than
# the gate in front of it is the gate's real contract the moment anyone invokes
# it, which is how the ten bindings in this family ended up with ten different
# answers for one constant.
_pin_raw = sys.argv[4]
if not _pin_raw or _pin_raw.strip("0123456789"):
    print(
        "ERROR: the ledger-size pin %r is not a non-negative integer in bare ASCII\n"
        "       digits (#lzpinparsestrict). The size is pinned as an exact equality,\n"
        "       so there is nothing to compare against — and defaulting here would\n"
        "       disarm the rung silently." % (_pin_raw,),
        file=sys.stderr,
    )
    sys.exit(1)
expected_ledgered = int(_pin_raw)

# --- the run's own sites, by bind state ------------------------------------
run_unbound, run_sites = set(), set()
with open(block_ledger, encoding="utf-8") as handle:
    for line in handle:
        # The run-id stamp is a comment, not a site (#lzstalemanifest). Its
        # freshness is enforced by the shell before this reader is ever called;
        # here it is skipped by the same `#` rule the committed ledger below uses,
        # so the stamp never enters the site population.
        if line.startswith("#"):
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) != 3:
            continue
        run_sites.add(parts[0])
        if parts[1] == "UNBOUND":
            run_unbound.add(parts[0])

# --- the ledger, by reason CLASS ------------------------------------------
KNOWN_CLASSES = ("unreachable", "bind-pending")
by_class = {name: set() for name in KNOWN_CLASSES}
# Every site the ledger names, whatever class it claims. The size pin covers the
# excused population as a whole, so an entry with an unrecognised class still
# counts toward it — otherwise misspelling the class would buy headroom.
ledgered_sites = set()
unknown_class = []
with open(unbound_ledger, encoding="utf-8") as handle:
    for line in handle:
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        site, reason = parts[0], parts[1]
        ledgered_sites.add(site)
        name = reason.split(":", 1)[0].strip()
        if name in by_class:
            by_class[name].add(site)
        else:
            unknown_class.append((site, name))

failed = False

if unknown_class:
    print(
        "ERROR: unbound-block ledger entries whose reason names no known class:\n"
        + "".join("         %s  ->  %r\n" % (site, name) for site, name in sorted(unknown_class)[:20])
        + "       Every entry must open with one of %s, because each class is pinned\n"
        "       to a different thing and an unclassed entry is pinned to nothing."
        % (", ".join(KNOWN_CLASSES),),
        file=sys.stderr,
    )
    failed = True

# --- unreachable: DERIVED from EXPECTED_SKIPS -----------------------------
try:
    with open(runner_src, encoding="utf-8") as handle:
        source = handle.read()
except OSError as error:
    print(
        "ERROR: could not read %s: %s\n"
        "       The `unreachable` class is DERIVED from its EXPECTED_SKIPS map, so an\n"
        "       unreadable runner is missing EVIDENCE, not evidence of absence."
        % (runner_src, error),
        file=sys.stderr,
    )
    sys.exit(1)

start = source.find("val EXPECTED_SKIPS")
skip_fixtures = set()
if start != -1:
    open_paren = source.find("mapOf(", start)
    if open_paren != -1:
        depth, index = 0, open_paren + len("mapOf(") - 1
        while index < len(source):
            if source[index] == "(":
                depth += 1
            elif source[index] == ")":
                depth -= 1
                if depth == 0:
                    break
            index += 1
        body = source[open_paren:index]
        skip_fixtures = {name for name in re.findall(r'"([^"]+\.json)"\s*to\b', body, re.S)}

if not skip_fixtures:
    print(
        "ERROR: found no EXPECTED_SKIPS entries in %s.\n"
        "       The `unreachable` class is derived from that map. An empty parse would\n"
        "       make the derivation vacuously satisfied by an empty ledger class, which\n"
        "       is the shape this rung exists to refuse (#lzvacuousrun). The map was\n"
        "       renamed or reshaped — update this reader, do not type the set in."
        % runner_src,
        file=sys.stderr,
    )
    sys.exit(1)

# Every site of a skipped fixture that the run did not bind. The replay stops
# before any block in these fixtures, so this is the whole unreachable set.
derived_unreachable = {
    site
    for site in run_unbound
    if site.split("|", 1)[0].startswith("reactive-graph/")
    and site.split("|", 1)[0].split("/")[-1] in skip_fixtures
}

if by_class["unreachable"] != derived_unreachable:
    only_ledger = sorted(by_class["unreachable"] - derived_unreachable)
    only_derived = sorted(derived_unreachable - by_class["unreachable"])
    print(
        "ERROR: the `unreachable` ledger class does not match what EXPECTED_SKIPS\n"
        "       derives. It carries %d site(s); %s names %d fixture(s) whose unbound\n"
        "       sites number %d.\n"
        "       This class is DERIVED, so there is nothing to re-pin: either a fixture\n"
        "       left EXPECTED_SKIPS because its op is now modelled — in which case its\n"
        "       entries here are folklore and must go, and the sites must be BOUND —\n"
        "       or a fixture entered it and its sites belong in this class."
        % (len(by_class["unreachable"]), runner_src, len(skip_fixtures), len(derived_unreachable)),
        file=sys.stderr,
    )
    if only_ledger:
        print("       ledgered `unreachable`, but EXPECTED_SKIPS does not cover them:", file=sys.stderr)
        for site in only_ledger[:20]:
            print("         " + site, file=sys.stderr)
    if only_derived:
        print("       unreachable per EXPECTED_SKIPS, but not ledgered as such:", file=sys.stderr)
        for site in only_derived[:20]:
            print("         " + site, file=sys.stderr)
    failed = True

# --- bind-pending: reported, and covered by the SIZE pin below ------------
# Derived from the RUN, not from the ledger, so this is what nothing bound and
# EXPECTED_SKIPS does not excuse. There is deliberately no per-class count of it:
# with the set equality above holding, a count of this population equals a count
# of the ledger's `bind-pending` class, so it could only ever restate the equality
# or drift away from it (#lzledgerceiling). The whole-ledger size pin below is the
# independent measurement, and it fails on shrink as well as growth
# (#lzledgerratchet).
run_bind_pending = run_unbound - derived_unreachable

# --- the SIZE PIN: an EXACT equality against a committed constant ---------
# The set equality is satisfied by any CONSISTENT PAIR, so detaching N binds and
# writing the N matching entries passes every check above. This is the only rung
# that refuses it, because it is the only one comparing the ledger against
# something the same commit does not also rewrite. It covers BOTH classes:
# laundering a detached bind into `unreachable` by widening EXPECTED_SKIPS grows
# the ledger just the same.
#
# EXACT, in both directions (#lzledgerratchet). A one-sided `>` bound refuses the
# attack only while slack is zero, and accumulates slack with every migration that
# does not re-pin, converging on the slack floor this ladder replaced. An equality
# cannot go stale quietly: a stale pin FAILS.
if len(ledgered_sites) != expected_ledgered:
    sites = sorted(ledgered_sites)
    if len(ledgered_sites) > expected_ledgered:
        print(
            "ERROR: the unbound-block ledger GREW: it names %d site(s), the pin is %d.\n"
            "       An excuse was ADDED. The set equality above cannot see this, because\n"
            "       it only checks that the ledger and the run AGREE — a commit that\n"
            "       detaches binds and writes the matching entries satisfies it forward,\n"
            "       backward and stale-gone, and the magnitude rung misses it too because\n"
            "       those sites are still DECLARED, just no longer bound.\n"
            "       Bind the block. Raise EXPECTED_LEDGERED_BLOCKS in this commit only\n"
            "       for a genuinely unbindable one, with the `unreachable` class and a\n"
            "       reason. Ledgered now:"
            % (len(ledgered_sites), expected_ledgered),
            file=sys.stderr,
        )
    else:
        print(
            "ERROR: the unbound-block ledger SHRANK to %d site(s); the pin is still %d.\n"
            "       Sites were migrated and the pin was not lowered with them. LOWER\n"
            "       EXPECTED_LEDGERED_BLOCKS TO %d IN THIS COMMIT.\n"
            "       This is not bookkeeping. A pin left above the real size is SLACK,\n"
            "       and slack is what lets a later commit detach %d bind(s) and write\n"
            "       their excuses with every other rung still green. Re-pinning on\n"
            "       shrink is what keeps this a ratchet instead of a drifting ceiling.\n"
            "       Ledgered now:"
            % (
                len(ledgered_sites),
                expected_ledgered,
                len(ledgered_sites),
                expected_ledgered - len(ledgered_sites),
            ),
            file=sys.stderr,
        )
    for site in sites[:20]:
        print("         " + site, file=sys.stderr)
    if len(sites) > 20:
        print(
            "         ... and %d more — `git diff scripts/conformance-unbound-blocks.txt`\n"
            "         is the shortest way to see which entries this commit ADDED or\n"
            "         REMOVED."
            % (len(sites) - 20),
            file=sys.stderr,
        )
    failed = True

if failed:
    sys.exit(1)

print(
    "%d unreachable (derived from %d EXPECTED_SKIPS fixture(s), no number to re-pin) "
    "and %d bind-pending (no per-class count — the whole ledger is size-pinned); %d "
    "ledgered, pinned EXACTLY at %d"
    % (
        len(derived_unreachable),
        len(skip_fixtures),
        len(run_bind_pending),
        len(ledgered_sites),
        expected_ledgered,
    )
)
PY
)"
block_classes=""
if [ -f "$BLOCK_LEDGER" ] && [ -f "$UNBOUND_LEDGER" ]; then
  if ! block_classes="$(
    python3 -c "$BLOCK_CLASS_PY" "$BLOCK_LEDGER" "$UNBOUND_LEDGER" \
      "$REACTIVE_GRAPH_RUNNER" "$EXPECTED_LEDGERED_BLOCKS"
  )"; then
    missing=$((missing + 1))
  fi
fi

# --- rung 0, MAGNITUDE: how many blocks should that walk have found? ---------
#
# Everything above is a comparison between two RUNTIME facts: the blocks the
# loader inventoried and the blocks a tracker bound. That pair is self-consistent
# for any population, INCLUDING a tiny one — zero declared blocks means zero
# unbound blocks, and "18/18 bound" is a tautology that reports OK having
# compared nothing against the corpus (#lzvacuousrun). The zero-guard above only
# rules out the degenerate case; it says nothing about whether the walk still
# reaches every block the corpus puts in front of it.
#
# So the SAME walk rule is re-run here over the corpus on disk and the two
# magnitudes are asserted EQUAL. Two properties make that honest:
#
#   DERIVED, never typed. The expectation is the canonical corpus listing minus
#   this binding's own committed KNOWN_UNCOVERED ledger — the identical
#   corpus-minus-excuses partition the fixture rung above enforces in both
#   directions, so the opened set is a derivation and not a number anybody
#   re-pins when it drifts. It is deliberately NOT read off the runtime manifest:
#   an expectation taken from the run cannot disagree with the run.
#
#   TWO DIMENSIONS, both EQUAL. Sites and distinct digests are blind to opposite
#   things. A site count absorbs a CONTENT edit — respelling one block exactly
#   like another's leaves the site count untouched while the corpus has lost a
#   distinct claim. A digest count absorbs a DELETION of a block whose bytes
#   recur elsewhere. Both come off the one walk below; neither is a floor.
#
# NOTE on this binding's walk, and on reading it against lazily-spec. The walk
# below is the widest in the family's vocabulary: all FIVE tracked names at every
# depth, objects AND plain-object array elements (#lzktblockwalk,
# #lzarrayelementsites). The REQUIRED_AREAS / EXCUSED_AREAS partition above now
# models the suite's opened set exactly, including the protobuf fixture, so
# `lazily-spec/scripts/check-corpus-floors.mjs --report-blocks` independently
# derives the same 749 sites / 640 distinct digests over 148 fixtures.
#
# The numbers here stay DERIVED either way. They are computed under the walk this
# binding runs, so they pin it against detaching and they move on their own when
# the rule moves — do not "fix" a red by narrowing the walk to match, and there
# is no number in this block to hand-edit.
BLOCK_MAGNITUDE_PY="$(cat <<'PY'
import hashlib
import json
import os
import sys

ledger_path, spec_dir = sys.argv[1], sys.argv[2]


class RawNumber:
    """A JSON number kept as its SOURCE token.

    kotlinx hands `JsonPrimitive.content` back as the raw literal, so the digest
    on the Kotlin side folds `1` and `1.0` to different bytes. Python's decoder
    would fold both to the float 1.0 and the two sides would disagree on any
    fixture that spells a whole number with a decimal point.
    """

    __slots__ = ("token",)

    def __init__(self, token):
        self.token = token


def append_canonical(element, out):
    # The twin of ConformanceFixtures.appendCanonical. Tagged and self-delimiting
    # rather than re-serialized JSON: string ESCAPING is the one place two
    # implementations reliably disagree, so strings carry a UTF-8 BYTE LENGTH
    # prefix instead and no value can be confused with its punctuation. Object
    # keys are sorted because JsonObject equality, which the bind side matches on,
    # is order-insensitive.
    if element is None:
        out.append("z")
    elif element is True:
        out.append("ntrue;")
    elif element is False:
        out.append("nfalse;")
    elif isinstance(element, RawNumber):
        out.append("n" + element.token + ";")
    elif isinstance(element, str):
        append_canonical_string(element, out)
    elif isinstance(element, dict):
        out.append("o" + str(len(element)) + "{")
        for key in sorted(element):
            append_canonical_string(key, out)
            append_canonical(element[key], out)
        out.append("}")
    elif isinstance(element, list):
        out.append("a" + str(len(element)) + "[")
        for value in element:
            append_canonical(value, out)
        out.append("]")
    else:
        raise TypeError("unreachable JSON type %r" % type(element))


def append_canonical_string(value, out):
    out.append("s" + str(len(value.encode("utf-8"))) + ":" + value)


def block_digest(block):
    out = []
    append_canonical(block, out)
    return hashlib.sha256("".join(out).encode("utf-8")).hexdigest()


# All five names the canonical corpus gives an assertion-bearing block. Taken
# from the corpus, not from what kt's runners happen to read: a name set derived
# from the runners could never surface a block the runners do not reach, which is
# the whole question rung 0 asks.
BLOCK_NAMES = frozenset(("assertions", "expect", "expect_after", "expect_initial", "expected"))


def walk_for_blocks(fixture_id, element, path, sites, digests):
    """The twin of ConformanceFixtures.walkForBlocks.

    A tracked NAME whose value is a JSON OBJECT is a site, emitted and NOT
    descended into. An ARRAY-valued tracked key emits one site per plain-OBJECT
    ELEMENT, labelled `<path>[<index>]` (`#lzarrayelementsites`): a runner binds
    such an array's ELEMENTS and never the array, and until this widened the
    parenthetical pointed at a site nobody emitted — all 12 elements of
    `signaling/anti_spoof_session.json`'s eight `expect` arrays were invisible.

    ONE level, PLAIN OBJECTS, TRUE indexes: the element pass is entered only from
    the tracked-key branch, so `expect[0][1]` is not a site; a scalar, null or
    array element emits nothing but is still descended into; and the index is the
    element's real position, so `[{...}, 3, {...}]` gives `[0]` and `[2]`.
    """
    if isinstance(element, dict):
        for key, value in element.items():
            child_path = key if not path else path + "." + key
            if key in BLOCK_NAMES and isinstance(value, dict):
                sites.add(fixture_id + "|" + child_path)
                digests.add(block_digest(value))
            elif key in BLOCK_NAMES and isinstance(value, list):
                for index, item in enumerate(value):
                    item_path = "%s[%d]" % (child_path, index)
                    if isinstance(item, dict):
                        sites.add(fixture_id + "|" + item_path)
                        digests.add(block_digest(item))
                    else:
                        walk_for_blocks(fixture_id, item, item_path, sites, digests)
            else:
                walk_for_blocks(fixture_id, value, child_path, sites, digests)
    elif isinstance(element, list):
        for index, value in enumerate(element):
            walk_for_blocks(fixture_id, value, "%s[%d]" % (path, index), sites, digests)


# --- the RUN side: read back what the loader inventoried --------------------
ledger_sites = set()
ledger_digests = set()
with open(ledger_path, encoding="utf-8") as handle:
    for line_no, line in enumerate(handle, 1):
        line = line.rstrip("\n")
        if not line:
            continue
        # The run-id stamp is a comment, not a site (#lzstalemanifest). Skipped
        # explicitly rather than left to the digest-column check below: that
        # check would fire on it and blame a stale recorder, which is the wrong
        # diagnosis for a line that is supposed to be there. Freshness is
        # enforced by the shell before this reader runs.
        if line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 3 or not parts[2]:
            print(
                "ERROR: assertion-block ledger line %d of %s carries no digest column:\n"
                "         %r\n"
                "       The recorder that writes it is older than this guard (or the\n"
                "       build directory is stale). Re-run the suite so the ledger is\n"
                "       rewritten — a missing dimension is missing EVIDENCE, and\n"
                "       skipping the digest equality here would report OK about a\n"
                "       dimension nobody measured (#lzvacuousrun)." % (line_no, ledger_path, line),
                file=sys.stderr,
            )
            sys.exit(1)
        ledger_sites.add(parts[0])
        ledger_digests.add(parts[2])

# --- the CORPUS side: ONE walk, feeding BOTH dimensions ---------------------
excused = {
    entry.strip()
    for entry in os.environ.get("KNOWN_UNCOVERED_LEDGER", "").splitlines()
    if entry.strip()
}

corpus = []
for walk_root, _walk_dirs, walk_names in os.walk(spec_dir):
    for walk_name in walk_names:
        if walk_name.endswith(".json"):
            corpus.append(
                os.path.relpath(os.path.join(walk_root, walk_name), spec_dir).replace(os.sep, "/")
            )
corpus.sort()

expected_sites = set()
expected_digests = set()
walked = 0
for fixture_id in corpus:
    if fixture_id in excused:
        continue
    walked += 1
    try:
        with open(os.path.join(spec_dir, fixture_id), encoding="utf-8") as handle:
            document = json.load(handle, parse_int=RawNumber, parse_float=RawNumber)
    except (OSError, ValueError) as error:
        print(
            "ERROR: could not read canonical fixture '%s' out of %s: %s\n"
            "       The expected block magnitude is derived from these bytes, so an\n"
            "       unreadable fixture is missing EVIDENCE, not evidence of absence.\n"
            "       Fix the checkout." % (fixture_id, spec_dir, error),
            file=sys.stderr,
        )
        sys.exit(1)
    # THE WALK. Keep this identical to ConformanceFixtures.walkForBlocks, which
    # carries the full rationale for the weight-bearing clauses (#lzktblockwalk,
    # #lzarrayelementsites): an ARRAY-valued tracked key emits one site per
    # plain-object ELEMENT at its true index, one level only, and a site is
    # EMITTED AND NOT DESCENDED INTO. The two sides are pinned against each other
    # by the equalities below — if either were the wider, a green run would be
    # impossible, because a recorded site this walk does not enumerate fails as a
    # corrupted evidence channel and an enumerated site nobody records fails as
    # unbound.
    walk_for_blocks(fixture_id, document, "", expected_sites, expected_digests)

# Positive-evidence floor on EACH dimension (#lzvacuousrun). A derivation of zero
# is matched trivially by a run that inventoried nothing, on either axis.
if walked == 0 or not expected_sites or not expected_digests:
    print(
        "ERROR: the corpus at %s minus KNOWN_UNCOVERED derived %d opened fixture(s)\n"
        "       carrying %d assertion-block site(s) and %d distinct digest(s).\n"
        "       A zero on either dimension makes this rung vacuously green: zero is\n"
        "       trivially matched by a run that inventoried nothing (#lzvacuousrun).\n"
        "       The checkout is partial, or LAZILY_SPEC_CONFORMANCE_DIR points somewhere\n"
        "       that is not the corpus."
        % (spec_dir, walked, len(expected_sites), len(expected_digests)),
        file=sys.stderr,
    )
    sys.exit(1)

failed = False

if len(ledger_sites) != len(expected_sites):
    direction = "FEWER than" if len(ledger_sites) < len(expected_sites) else "MORE than"
    only_corpus = sorted(expected_sites - ledger_sites)[:10]
    only_run = sorted(ledger_sites - expected_sites)[:10]
    print(
        "ERROR: the run inventoried %d assertion-block SITE(S); the canonical corpus at\n"
        "       %s minus KNOWN_UNCOVERED derives %d over %d opened fixtures.\n"
        "       The run has %s the corpus declares.\n"
        "       This is an EQUALITY, not a floor. Either the corpus moved under this\n"
        "       checkout (re-pull the lazily-spec sibling so both sides read the same\n"
        "       bytes), or ConformanceFixtures.walkForBlocks detached from the walk\n"
        "       spelled out beside it and stopped declaring sites it should.\n"
        "       There is no number to re-pin here — fix whichever side moved."
        % (len(ledger_sites), spec_dir, len(expected_sites), walked, direction),
        file=sys.stderr,
    )
    if only_corpus:
        print("       declared by the corpus, absent from the run:", file=sys.stderr)
        for rel in only_corpus:
            print("         " + rel, file=sys.stderr)
    if only_run:
        print("       recorded by the run, absent from the corpus:", file=sys.stderr)
        for rel in only_run:
            print("         " + rel, file=sys.stderr)
    failed = True

if len(ledger_digests) != len(expected_digests):
    direction = "FEWER than" if len(ledger_digests) < len(expected_digests) else "MORE than"
    print(
        "ERROR: the run inventoried %d DISTINCT assertion-block digest(s); the canonical\n"
        "       corpus at %s minus KNOWN_UNCOVERED derives %d over %d opened\n"
        "       fixtures. The run has %s the corpus declares.\n"
        "       The SITE count above can agree while this does not: two blocks spelled\n"
        "       identically share one digest, so a content edit that collapses two\n"
        "       distinct claims into one leaves the site count untouched.\n"
        "       Either the corpus moved under this checkout, or\n"
        "       ConformanceFixtures.blockDigest and the twin in this script stopped\n"
        "       agreeing — fix whichever moved, and do not re-pin a number."
        % (len(ledger_digests), spec_dir, len(expected_digests), walked, direction),
        file=sys.stderr,
    )
    failed = True
elif ledger_digests != expected_digests:
    # Same COUNT, different MEMBERS. The counts are the two named dimensions, and
    # they can agree while the two digest implementations have drifted apart — a
    # disagreement over number tokens or string escaping renames every digest
    # without changing how many there are, and then the digest dimension is
    # measuring nothing. Cheap to check here because the fixture rung above has
    # already forced the run's opened set and corpus-minus-excuses to be the same
    # set of files, so these two digest sets must be the same members too.
    print(
        "ERROR: the run and the corpus each derived %d distinct assertion-block digests,\n"
        "       but they are not the SAME digests. ConformanceFixtures.blockDigest and\n"
        "       the twin in this script have drifted apart (number tokens or string\n"
        "       length-prefixing are where they diverge), so the count equality above\n"
        "       is comparing two different measurements that happen to agree in size.\n"
        "       %d only in the run, %d only in the corpus."
        % (
            len(ledger_digests),
            len(ledger_digests - expected_digests),
            len(expected_digests - ledger_digests),
        ),
        file=sys.stderr,
    )
    failed = True

if failed:
    sys.exit(1)

print(
    "derived %d site(s) AND %d distinct digest(s) from %d opened fixtures, both "
    "asserted EQUAL" % (len(expected_sites), len(expected_digests), walked)
)
PY
)"
block_magnitude=""
if [ -f "$BLOCK_LEDGER" ]; then
  if ! block_magnitude="$(
    KNOWN_UNCOVERED_LEDGER="$(printf '%s\n' ${KNOWN_UNCOVERED[@]+"${KNOWN_UNCOVERED[@]}"})" \
      python3 -c "$BLOCK_MAGNITUDE_PY" "$BLOCK_LEDGER" "$SPEC_DIR"
  )"; then
    missing=$((missing + 1))
  fi
fi

if [ "$missing" -gt 0 ]; then
  echo "conformance coverage FAILED: $missing problem(s)" >&2
  exit 1
fi

echo "conformance coverage OK: $covered/$total canonical fixtures OPENED by the suite" \
     "($excused_count of ${#KNOWN_UNCOVERED[@]} KNOWN_UNCOVERED entries applied;" \
     "runtime manifest — these bytes were really read)"
echo "scenario coverage OK: $sc_replayed/$sc_total scenarios across $sc_fixtures opened" \
     "scenario-bearing fixtures were REPLAYED ($sc_excused excused of" \
     "${#KNOWN_UNREPLAYED_SCENARIOS[@]} excuseScenario entries; runtime ledger —" \
     "these scenarios really ran)"
echo "assertion-block coverage OK: $bound_block_count/$blocks_total assertion-block site(s)" \
     "opened by the suite were BOUND to a tracker ($excused_block_count ledgered per SITE in" \
     "$UNBOUND_LEDGER, both stale directions enforced — $block_classes; runtime ledger — a" \
     "block nobody binds is silent to every other rung; $block_magnitude)"
# Printed so MIN_OPENED_AREAS can be re-pinned from a CI log instead of being
# guessed or probed locally — a floor nobody can read the real number for is a
# floor that drifts.
echo "area coverage OK: $opened_area_count corpus area(s) OPENED by the suite" \
     "(${#REQUIRED_AREAS[@]} required; floor $MIN_OPENED_AREAS)"
