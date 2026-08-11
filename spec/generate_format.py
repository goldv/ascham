#!/usr/bin/env python3
"""Generate the per-language format-contract artifacts from spec/format-manifest.toml.

The manifest is the single source of truth for every byte constant the ascham segment format
defines. This script derives, deterministically:

  --lang java   SegmentFormat.java, PhysicalKind.java, MetadataKeys.java,
                conformance/type_profile_vectors.json, and the marked tables in
                docs/segment-format.md (run from the ascham repo root)
  --lang cpp    src/format/arena_format_gen.hpp and the marked tables in
                docs/segment-format.md (run from the arrow_rdb repo root, against the
                vendored copy of the manifest)

Generated files are checked in. `--check` regenerates in memory and diffs against the working
tree instead of writing, exiting 1 on drift — CI runs this so a hand-edit to a generated file
(or a manifest change without regeneration) fails fast, before the golden corpus even runs.

Algorithms (Layouts.compute, LayoutCodec encode/decode) are deliberately NOT generated; they are
hand-written per language and pinned by conformance vectors.

Requires Python >= 3.11 (stdlib tomllib). No third-party dependencies.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import pathlib
import re
import sys
import tomllib

SPEC_DIR = pathlib.Path(__file__).resolve().parent
MANIFEST = SPEC_DIR / "format-manifest.toml"

TYPE_SIZES = {"i32": 4, "i64": 8, "u64": 8}
TYPE_DOC = {"i32": "int32", "i64": "int64", "u64": "uint64", "bytes": "bytes"}


def const_name(ident: str) -> str:
    return ident.replace("-", "_").replace(".", "_").upper()


def field_size(field: dict) -> int:
    if field["type"] == "bytes":
        return int(field["size"])
    return TYPE_SIZES[field["type"]]


def jdoc(text: str) -> str:
    """Markdown backticks -> javadoc {@code ...}."""
    return re.sub(r"`([^`]+)`", r"{@code \1}", text)


# ----------------------------------------------------------------------------------------------
# Validation


def fail(errors: list[str]) -> None:
    for e in errors:
        print(f"manifest error: {e}", file=sys.stderr)
    raise SystemExit(1)


def validate(m: dict) -> None:
    errors: list[str] = []
    fmt = m["format"]
    header_len = fmt["header-length"]
    if len(fmt["magic"]) != 8:
        errors.append(f"magic must be 8 ASCII bytes, got {len(fmt['magic'])}")
    if fmt["endianness"] != "little":
        errors.append("only little-endian is modeled")

    # Header fields: in-range, non-overlapping, ordered fields 8-aligned, below the region table.
    region_tab = m["header"]["region-table"]
    prev_end, prev_name = 0, None
    for name, f in m["header"]["fields"].items():
        off, size = f["offset"], field_size(f)
        if off < prev_end:
            errors.append(f"header field {name} at {off} overlaps {prev_name}")
        if off + size > header_len:
            errors.append(f"header field {name} exceeds header-length")
        if off + size > region_tab["offset"]:
            errors.append(f"header field {name} overlaps the region table")
        if f.get("ordering") and off % 8 != 0:
            errors.append(f"ordered header field {name} is not 8-byte aligned")
        prev_end, prev_name = off + size, name
    region_end = region_tab["offset"] + len(region_tab["regions"]) * region_tab["entry-size"]
    if region_end > header_len:
        errors.append("region table exceeds header-length")
    for r in region_tab["reserved"]:
        if r not in region_tab["regions"]:
            errors.append(f"reserved region {r} not in region list")

    # Catalog fields: in-range, non-overlapping, clear of reserved ranges.
    cat = m["catalog"]
    prev_end, prev_name = 0, None
    publication_points = 0
    for name, f in cat["fields"].items():
        off, size = f["offset"], field_size(f)
        if off < prev_end:
            errors.append(f"catalog field {name} at {off} overlaps {prev_name}")
        if off + size > cat["entry-size"]:
            errors.append(f"catalog field {name} exceeds entry-size")
        for lo, hi in cat["reserved-ranges"]:
            if off < hi and off + size > lo:
                errors.append(f"catalog field {name} overlaps reserved range [{lo},{hi})")
        if f.get("ordering") and off % 8 != 0:
            errors.append(f"ordered catalog field {name} is not 8-byte aligned")
        if f.get("publication-point"):
            publication_points += 1
            bit = f.get("in-progress-bit")
            if bit is None or not 0 <= bit <= 63:
                errors.append(f"publication point {name} needs in-progress-bit in [0,63]")
        prev_end, prev_name = off + size, name
    if publication_points != 1:
        errors.append(f"expected exactly one catalog publication point, got {publication_points}")

    # Enums: int values, unique.
    for enum_name, members in m["enums"].items():
        values = [v["value"] for v in members.values()]
        if len(set(values)) != len(values):
            errors.append(f"enum {enum_name} has duplicate wire values")

    # Type profile: kinds resolve, FIXED rows have widths, VARLEN rows require varlen_bytes.
    kinds = set(m["enums"]["physical-kind"])
    ids = set()
    for row in m["types"]["accepted"] + m["types"]["rejected"]:
        if row["id"] in ids:
            errors.append(f"duplicate type row id {row['id']}")
        ids.add(row["id"])
    for row in m["types"]["accepted"]:
        if row["kind"] not in kinds:
            errors.append(f"type {row['id']}: unknown kind {row['kind']}")
        if row["kind"] == "FIXED" and "width" not in row:
            errors.append(f"type {row['id']}: FIXED requires width")
        if row["kind"] != "FIXED" and "width" in row:
            errors.append(f"type {row['id']}: width only valid on FIXED")

    # Metadata keys: prefix discipline, unique ids.
    seen = set()
    for key in m["metadata"]["keys"]:
        if key["id"] in seen:
            errors.append(f"duplicate metadata key {key['id']}")
        seen.add(key["id"])
        if key["level"] not in ("schema", "field"):
            errors.append(f"metadata key {key['id']}: bad level {key['level']}")

    # The filename pattern must at least compile as a Python regex (a conservative common subset
    # of java.util.regex and ECMAScript is assumed; the corpus fixtures exercise both engines).
    try:
        re.compile(m["filenames"]["segment-pattern"])
    except re.error as e:
        errors.append(f"segment-pattern does not compile: {e}")

    if errors:
        fail(errors)


# ----------------------------------------------------------------------------------------------
# Java emission


def java_banner(m: dict, manifest_sha: str) -> str:
    return (
        f"// GENERATED from spec/format-manifest.toml (sha256 {manifest_sha}) by"
        " spec/generate_format.py — DO NOT EDIT.\n"
        "// Regenerate with: python3 spec/generate_format.py --lang java --repo .\n"
    )


def emit_java_segment_format(m: dict, manifest_sha: str) -> str:
    fmt = m["format"]
    hf = m["header"]["fields"]
    region_tab = m["header"]["region-table"]
    cat = m["catalog"]
    length_field = cat["fields"]["length"]
    pattern_java = m["filenames"]["segment-pattern"].replace("\\", "\\\\")

    out = [java_banner(m, manifest_sha)]
    out.append("package io.ascham.segment;\n\n")
    out.append("import java.nio.charset.StandardCharsets;\n")
    out.append("import java.util.regex.Pattern;\n\n")
    out.append(
        "/**\n"
        " * Every segment-format constant in one place, generated from the machine-readable contract\n"
        " * in {@code spec/format-manifest.toml}; each field below cites its offset in\n"
        " * {@code docs/segment-format.md}. Nothing in this file may change without a\n"
        " * {@link #FORMAT_VERSION} bump once any segment has been written — it is the cross-language\n"
        " * byte contract.\n"
        " *\n"
        " * <p>All multi-byte values are little-endian. All offsets written into the header region table and\n"
        " * every catalog {@code base_offset} are segment-relative (spec invariant 4: never a pointer).\n"
        " */\n"
    )
    out.append("public final class SegmentFormat {\n\n")
    out.append(
        "    /** 8-byte segment magic. Confirmed at the ascham rename; changing it again is a format break. */\n"
    )
    out.append(f'    public static final byte[] MAGIC = "{fmt["magic"]}".getBytes(StandardCharsets.US_ASCII);\n\n')
    out.append(f"    public static final int MAGIC_LENGTH = {len(fmt['magic'])};\n")
    out.append(f"    public static final int FORMAT_VERSION = {fmt['version']};\n")
    out.append(f"    public static final int HEADER_LENGTH = {fmt['header-length']};\n\n")

    out.append('    // --- Header field offsets (see docs/segment-format.md "Header"). ---\n')
    for name, f in hf.items():
        cname = "HDR_" + const_name(name)
        if f.get("ordering"):
            summary = f["doc"].split(" — ", 1)[1].replace(" (own cache line)", "")
            summary = summary[0].upper() + summary[1:]
            out.append(f"    /** {jdoc(summary)}, alone on its cache line (release/acquire). */\n")
        line = f"    public static final int {cname} = {f['offset']};"
        if f["type"] == "bytes" and name != "magic":
            line += f"   // {f['size']} bytes"
        out.append(line + "\n")
    out.append("\n")

    out.append(
        f"    // Region table (offset,length) pairs, starting at {region_tab['offset']}.\n"
    )
    out.append(f"    public static final int HDR_REGION_TABLE = {region_tab['offset']};\n")
    for i, region in enumerate(region_tab["regions"]):
        if region in region_tab["reserved"]:
            out.append(
                "    /** Reserved in v1 (offset/length both 0); populated by a future format version (invariant 8). */\n"
            )
        out.append(f"    public static final int REGION_{const_name(region)} = {i};\n")
    out.append(f"    public static final int REGION_COUNT = {len(region_tab['regions'])};\n")
    out.append(
        f"    public static final int REGION_ENTRY_SIZE = {region_tab['entry-size']}; // int64 offset + int64 length\n\n"
    )

    out.append('    // --- Catalog entry offsets (see docs/segment-format.md "Batch catalog"). ---\n')
    out.append(
        f"    public static final int CATALOG_ENTRY_SIZE = {cat['entry-size']}; // one cache line\n"
    )
    last_end = 0
    for name, f in cat["fields"].items():
        out.append(f"    public static final int ENT_{const_name(name)} = {f['offset']};\n")
        last_end = f["offset"] + field_size(f)
    for lo, hi in cat["reserved-ranges"]:
        out.append(f"    // bytes {lo}..{hi} reserved\n")
    out.append("\n")

    bit = length_field["in-progress-bit"]
    out.append(
        "    /**\n"
        f"     * Catalog {{@code length}} bit {bit}: set means the batch is still accumulating. Row count is\n"
        "     * {@code length & ROW_COUNT_MASK}. A negative sentinel is not used: {@code -0 == 0} would make a\n"
        "     * zero-row in-progress batch indistinguishable from a sealed empty one (spec).\n"
        "     */\n"
    )
    out.append(f"    public static final long IN_PROGRESS_BIT = 1L << {bit};\n")
    out.append("    public static final long ROW_COUNT_MASK = Long.MAX_VALUE;\n\n")

    out.append(
        '    /** Layout descriptor codec version (docs/segment-format.md "Layout descriptor region"). */\n'
    )
    out.append(f"    public static final int LAYOUT_CODEC_VERSION = {m['layout-codec']['version']};\n\n")

    out.append(
        "    /** Buffer-base alignment (spec invariant 5). Mirrored by {@code util.Alignment}; asserted equal there. */\n"
    )
    out.append(f"    public static final int BUFFER_ALIGN = {m['alignment']['buffer-align']};\n\n")
    out.append(
        "    /** Batch-stride alignment (spec invariant 6). Mirrored by {@code util.Alignment}; asserted equal there. */\n"
    )
    out.append(f"    public static final int PAGE_ALIGN = {m['alignment']['page-align']};\n\n")

    out.append(
        "    /**\n"
        "     * Segment filename grammar: {@code <yyyyMMdd>.<seq>.ascham} (daily cycle) or\n"
        "     * {@code <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham} (sub-day). Groups: date, start HHmm,\n"
        "     * cycle minutes, sequence. The {1,9} bounds keep every numeric group inside int32 before any\n"
        "     * parse; in-flight {@code *.tmp.*} files are excluded by non-match.\n"
        "     */\n"
    )
    out.append(
        f'    public static final Pattern SEGMENT_FILENAME_PATTERN = Pattern.compile("{pattern_java}");\n\n'
    )

    out.append("    private SegmentFormat() {\n    }\n\n")
    out.append(
        "    /** Segment-relative byte offset of the {@code offset} slot of region {@code regionIndex}. */\n"
        "    public static int regionOffsetField(int regionIndex) {\n"
        "        return HDR_REGION_TABLE + regionIndex * REGION_ENTRY_SIZE;\n"
        "    }\n\n"
        "    /** Segment-relative byte offset of the {@code length} slot of region {@code regionIndex}. */\n"
        "    public static int regionLengthField(int regionIndex) {\n"
        "        return regionOffsetField(regionIndex) + Long.BYTES;\n"
        "    }\n\n"
        "    /** Row count encoded in a catalog {@code length} value (masks off the in-progress bit). */\n"
        "    public static long rowCount(long length) {\n"
        "        return length & ROW_COUNT_MASK;\n"
        "    }\n\n"
        "    /** Whether a catalog {@code length} value denotes an in-progress (unsealed) batch. */\n"
        "    public static boolean isInProgress(long length) {\n"
        "        return (length & IN_PROGRESS_BIT) != 0;\n"
        "    }\n"
        "}\n"
    )
    return "".join(out)


def emit_java_physical_kind(m: dict, manifest_sha: str) -> str:
    members = m["enums"]["physical-kind"]
    out = [java_banner(m, manifest_sha)]
    out.append("package io.ascham.layout;\n\n")
    out.append(
        "/**\n"
        " * The physical storage class of a column, derived from its Arrow type. This is the axis the layout\n"
        " * function branches on, not the logical Arrow type.\n"
        " *\n"
        " * <p>The wire value is what {@code LayoutCodec} writes into every segment's layout descriptor. It\n"
        " * is format contract, assigned explicitly here (never the enum ordinal) so reordering or inserting\n"
        " * declarations cannot silently change the format.\n"
        " */\n"
    )
    out.append("public enum PhysicalKind {\n")
    decls = []
    for name, member in members.items():
        decls.append(f"    /** {jdoc(member['doc'])} */\n    {name}({member['value']})")
    out.append(",\n".join(decls) + ";\n\n")
    out.append("    private final int wireValue;\n\n")
    out.append("    PhysicalKind(int wireValue) {\n        this.wireValue = wireValue;\n    }\n\n")
    out.append(
        "    /** The value written to / read from the layout descriptor. Format contract. */\n"
        "    public int wireValue() {\n        return wireValue;\n    }\n\n"
    )
    out.append(
        "    /** Resolves a descriptor {@code kind} wire value; throws on an unknown value. */\n"
        "    public static PhysicalKind fromWire(int value) {\n"
        "        for (PhysicalKind kind : values()) {\n"
        "            if (kind.wireValue == value) {\n"
        "                return kind;\n"
        "            }\n"
        "        }\n"
        '        throw new IllegalArgumentException("unknown PhysicalKind wire value: " + value);\n'
        "    }\n"
        "}\n"
    )
    return "".join(out)


def emit_java_metadata_keys(m: dict, manifest_sha: str) -> str:
    md = m["metadata"]
    prefix = md["prefix"]
    schema_keys = [k for k in md["keys"] if k["level"] == "schema"]
    field_keys = [k for k in md["keys"] if k["level"] == "field"]
    defaults = {k["id"]: k["default"] for k in md["keys"] if "default" in k}

    out = [java_banner(m, manifest_sha)]
    out.append("package io.ascham.schema;\n\n")
    out.append("import java.util.Set;\n\n")
    out.append(
        "/**\n"
        " * The {@code ascham.*} metadata keys carried in the Arrow schema's {@code custom_metadata} (schema\n"
        " * level) and each field's metadata (field level). Arrow schemas cannot express capacity or pruning\n"
        " * intent, so it is carried here rather than in a sidecar file — one artifact, and generic Arrow\n"
        " * tooling can still read it.\n"
        " *\n"
        " * <p>The prefix was confirmed at the ascham rename; changing it again is a format break, since the\n"
        " * keys are part of the canonical schema bytes hashed into every segment header.\n"
        " */\n"
    )
    out.append("public final class MetadataKeys {\n\n")
    out.append(
        "    /** Prefix owned by this module; any unknown {@code ascham.*} key is a validation error. */\n"
    )
    out.append(f'    public static final String PREFIX = "{prefix}";\n\n')
    out.append("    // Schema-level.\n")
    for k in schema_keys:
        out.append(f'    public static final String {const_name(k["id"])} = "{prefix}{k["id"]}";\n')
    out.append("\n    // Field-level.\n")
    for k in field_keys:
        out.append(f'    public static final String {const_name(k["id"])} = "{prefix}{k["id"]}";\n')
    out.append("\n")
    out.append("    /** Default target rows per sealed batch when {@link #BATCH_ROWS} is absent. */\n")
    out.append(f"    public static final int DEFAULT_BATCH_ROWS = {defaults['batch_rows']};\n\n")
    out.append("    /** Default column family when {@link #FAMILY} is absent. */\n")
    out.append(f'    public static final String DEFAULT_FAMILY = "{defaults["family"]}";\n\n')
    out.append("    static final Set<String> SCHEMA_LEVEL = Set.of(\n            ")
    out.append(", ".join(const_name(k["id"]) for k in schema_keys))
    out.append(");\n\n")
    out.append("    static final Set<String> FIELD_LEVEL = Set.of(\n            ")
    out.append(", ".join(const_name(k["id"]) for k in field_keys))
    out.append(");\n\n")
    out.append("    private MetadataKeys() {\n    }\n}\n")
    return "".join(out)


# ----------------------------------------------------------------------------------------------
# C++ emission


def emit_cpp_header(m: dict, manifest_sha: str) -> str:
    fmt = m["format"]
    hf = m["header"]["fields"]
    region_tab = m["header"]["region-table"]
    cat = m["catalog"]
    length_field = cat["fields"]["length"]
    md = m["metadata"]
    magic_chars = ", ".join(f"'{c}'" for c in fmt["magic"])

    out = [
        f"// GENERATED from spec/format-manifest.toml (sha256 {manifest_sha}) by"
        " spec/generate_format.py — DO NOT EDIT.\n"
        "// Regenerate with: python3 spec/generate_format.py --lang cpp --repo .\n"
        "//\n"
        "// Every segment-format constant, mirrored mechanically from the same manifest that generates\n"
        "// the Java SegmentFormat/PhysicalKind/MetadataKeys. Nothing here may change without a\n"
        "// format-version bump. Header-only, no dependencies beyond <cstdint>.\n"
        "#pragma once\n\n"
        "#include <cstdint>\n\n"
        "namespace arena {\n\n"
        "namespace fmt {\n\n"
    ]
    out.append('// Header (segment-format.md "Header").\n')
    out.append(f"inline constexpr char MAGIC[{len(fmt['magic'])}] = {{{magic_chars}}};\n")
    out.append(f"inline constexpr int MAGIC_LENGTH = {len(fmt['magic'])};\n")
    out.append(f"inline constexpr int FORMAT_VERSION = {fmt['version']};\n")
    out.append(f"inline constexpr int HEADER_LENGTH = {fmt['header-length']};\n\n")
    for name, f in hf.items():
        cname = "HDR_" + const_name(name)
        line = f"inline constexpr int {cname} = {f['offset']};"
        if f["type"] == "bytes" and name != "magic":
            line += f"   // {f['size']} bytes"
        if f.get("ordering"):
            line += " // ordered (release/acquire)"
        out.append(line + "\n")
    out.append("\n")
    out.append(
        f"// Region table: {len(region_tab['regions'])} (offset,length) int64 pairs starting at"
        f" {region_tab['offset']}.\n"
    )
    out.append(f"inline constexpr int HDR_REGION_TABLE = {region_tab['offset']};\n")
    out.append(f"inline constexpr int REGION_ENTRY_SIZE = {region_tab['entry-size']};\n")
    for i, region in enumerate(region_tab["regions"]):
        line = f"inline constexpr int REGION_{const_name(region)} = {i};"
        if region in region_tab["reserved"]:
            line += " // reserved in v1 (offset/length both 0)"
        out.append(line + "\n")
    out.append(f"inline constexpr int REGION_COUNT = {len(region_tab['regions'])};\n\n")
    out.append(
        f'// Catalog entry (segment-format.md "Batch catalog"): {cat["entry-size"]} bytes, one cache line.\n'
    )
    out.append(f"inline constexpr int CATALOG_ENTRY_SIZE = {cat['entry-size']};\n")
    for name, f in cat["fields"].items():
        line = f"inline constexpr int ENT_{const_name(name)} = {f['offset']};"
        if f.get("publication-point"):
            line += f"        // ordered (release/acquire); bit {f['in-progress-bit']} = in progress"
        out.append(line + "\n")
    for lo, hi in cat["reserved-ranges"]:
        out.append(f"// bytes {lo}..{hi} reserved\n")
    out.append("\n")
    bit = length_field["in-progress-bit"]
    mask = (1 << bit) - 1
    out.append(f"inline constexpr std::int64_t IN_PROGRESS_BIT = std::int64_t{{1}} << {bit};\n")
    out.append(f"inline constexpr std::int64_t ROW_COUNT_MASK = 0x{mask:x}LL;\n\n")
    sha_len = hf["schema-sha256"]["size"]
    out.append(f"inline constexpr int SHA256_LENGTH = {sha_len};\n\n")
    out.append('// Layout descriptor codec (segment-format.md "Layout descriptor region").\n')
    out.append(f"inline constexpr int LAYOUT_CODEC_VERSION = {m['layout-codec']['version']};\n\n")
    out.append("// Alignment (spec invariants 5 and 6).\n")
    out.append(f"inline constexpr int BUFFER_ALIGN = {m['alignment']['buffer-align']};\n")
    out.append(f"inline constexpr int PAGE_ALIGN = {m['alignment']['page-align']};\n\n")
    out.append(
        "// ascham.* metadata keys (Arrow schema custom_metadata). The writer validates these strictly;\n"
        "// this reader consumes a subset and deliberately ignores the rest.\n"
    )
    out.append(f'inline constexpr const char* METADATA_PREFIX = "{md["prefix"]}";\n')
    for k in md["keys"]:
        out.append(
            f'inline constexpr const char* META_{const_name(k["id"])} = "{md["prefix"]}{k["id"]}";\n'
        )
    out.append("\n")
    out.append(
        "// Segment filename grammar: <yyyyMMdd>.<seq>.ascham (daily cycle) or\n"
        "// <yyyyMMdd>.<HHmm>.<minutes>m.<seq>.ascham (sub-day). Groups: date, start HHmm, cycle\n"
        "// minutes, sequence. The {1,9} bounds keep every numeric group inside int32 before any parse;\n"
        "// in-flight *.tmp.* files are excluded by non-match.\n"
    )
    out.append(
        f'inline constexpr const char* SEGMENT_FILENAME_PATTERN = R"re({m["filenames"]["segment-pattern"]})re";\n\n'
    )
    out.append("}  // namespace fmt\n\n")
    out.append(
        "// Physical storage class of a column (layout descriptor `kind`). Wire values are format\n"
        "// contract, shared with the Java PhysicalKind.\n"
    )
    out.append("enum class PhysicalKind : int {\n")
    for name, member in m["enums"]["physical-kind"].items():
        out.append(f"    {name} = {member['value']},\n")
    out.append("};\n\n")
    out.append("}  // namespace arena\n")
    return "".join(out)


# ----------------------------------------------------------------------------------------------
# Type-profile conformance vectors


def emit_type_vectors(m: dict, manifest_sha: str) -> str:
    """One row object per line so consumers without a JSON library (the C++ reader-core test)
    can scan it the same way they scan conformance/manifest.json."""
    kind_wire = {name: member["value"] for name, member in m["enums"]["physical-kind"].items()}

    def row_json(entry: dict) -> str:
        return json.dumps(entry, separators=(", ", ": "))

    out = ["{"]
    out.append(
        '  "_generated": '
        + json.dumps(
            f"by spec/generate_format.py from spec/format-manifest.toml (sha256 {manifest_sha})"
            " — do not edit"
        )
        + ","
    )
    out.append('  "physical-kind-wire": ' + row_json(kind_wire) + ",")
    for section in ("accepted", "rejected"):
        rows = []
        for row in m["types"][section]:
            entry = dict(row)
            if section == "accepted":
                entry["kind-wire"] = kind_wire[row["kind"]]
            rows.append("    " + row_json(entry))
        out.append(f'  "{section}": [')
        out.append(",\n".join(rows))
        out.append("  ]," if section == "accepted" else "  ]")
    out.append("}")
    return "\n".join(out) + "\n"


# ----------------------------------------------------------------------------------------------
# Spec-table rewriting (marked blocks in docs/segment-format.md)


def spec_header_table(m: dict) -> str:
    fmt = m["format"]
    hf = m["header"]["fields"]
    region_tab = m["header"]["region-table"]
    written_words = {"create": "at create", "steady-state": "steady state", "batch-open": "per batch open"}
    rows = ["| Offset | Size | Type | Field | Written | Ordering |", "|---|---|---|---|---|---|"]
    pos = 0
    for name, f in hf.items():
        off, size = f["offset"], field_size(f)
        if off > pos:
            rows.append(f"| {pos} | {off - pos} | — | reserved (zero) | | |")
        type_doc = "8×ASCII" if name == "magic" else TYPE_DOC[f["type"]]
        ordering = "**release** / acquire" if f.get("ordering") else "plain"
        rows.append(
            f"| {off} | {size} | {type_doc} | {f['doc']} | {written_words[f['written']]} | {ordering} |"
        )
        pos = off + size
    tab_off = region_tab["offset"]
    if tab_off > pos:
        rows.append(f"| {pos} | {tab_off - pos} | — | reserved (zero) | | |")
    pos = tab_off
    for region in region_tab["regions"]:
        reserved = " (reserved, 0 in v1)" if region in region_tab["reserved"] else ""
        base = region.replace("-", "_")
        rows.append(f"| {pos} | 8 | int64 | `{base}_region_offset`{reserved} | at create | plain |")
        rows.append(f"| {pos + 8} | 8 | int64 | `{base}_region_length`{reserved} | at create | plain |")
        pos += region_tab["entry-size"]
    rows.append(f"| {pos} | {fmt['header-length'] - pos} | — | reserved (zero) | | |")
    return "\n".join(rows)


def spec_catalog_table(m: dict) -> str:
    cat = m["catalog"]
    rows = ["| Offset | Size | Type | Field |", "|---|---|---|---|"]
    pos = 0
    for name, f in cat["fields"].items():
        off, size = f["offset"], field_size(f)
        if off > pos:
            rows.append(f"| {pos} | {off - pos} | — | reserved (zero) |")
        rows.append(f"| {off} | {size} | {TYPE_DOC[f['type']]}  | {f['doc']} |")
        pos = off + size
    if pos < cat["entry-size"]:
        rows.append(f"| {pos} | {cat['entry-size'] - pos} | — | reserved (zero) |")
    return "\n".join(rows)


def spec_type_profile(m: dict) -> str:
    rows = ["| Arrow type | Physical kind | Element width | Notes |", "|---|---|---|---|"]
    for row in m["types"]["accepted"]:
        arrow = row["arrow"]
        attrs = []
        if "unit" in row:
            attrs.append(row["unit"].lower())
        if "timezone" in row:
            attrs.append(f"tz={row['timezone']}")
        if "precision" in row:
            attrs.append(f"{row['precision']},{row['scale']}")
        if "byte-width" in row:
            attrs.append(str(row["byte-width"]))
        shown = f"`{arrow}({', '.join(attrs)})`" if attrs else f"`{arrow}`"
        width = str(row["width"]) if "width" in row else "—"
        notes = (
            "requires " + ", ".join(f"`{k}`" for k in row["requires-metadata"])
            if "requires-metadata" in row
            else ""
        )
        rows.append(f"| {shown} | {row['kind']} | {width} | {notes} |")
    rejected = ", ".join(f"`{row['arrow']}`" for row in m["types"]["rejected"])
    return "\n".join(rows) + f"\n\nRejected in v1: {rejected}.\n"


def spec_metadata_tables(m: dict) -> str:
    md = m["metadata"]
    out = ["**Schema-level:**", "", "| Key | Meaning |", "|---|---|"]
    for k in md["keys"]:
        if k["level"] == "schema":
            out.append(f"| `{md['prefix']}{k['id']}` | {k['doc']} |")
    out += ["", "**Field-level:**", "", "| Key | Meaning |", "|---|---|"]
    for k in md["keys"]:
        if k["level"] == "field":
            out.append(f"| `{md['prefix']}{k['id']}` | {k['doc']} |")
    return "\n".join(out)


SPEC_BLOCKS = {
    "header-table": spec_header_table,
    "catalog-table": spec_catalog_table,
    "type-profile": spec_type_profile,
    "metadata-tables": spec_metadata_tables,
}


def rewrite_spec(m: dict, text: str) -> str:
    for block, emit in SPEC_BLOCKS.items():
        pattern = re.compile(
            rf"(<!-- BEGIN GENERATED: {block} -->\n).*?(<!-- END GENERATED: {block} -->)",
            re.DOTALL,
        )
        if pattern.search(text):
            replacement = emit(m).rstrip("\n")
            text = pattern.sub(lambda match: match.group(1) + replacement + "\n" + match.group(2), text)
    return text


# ----------------------------------------------------------------------------------------------
# Driver


def outputs(m: dict, manifest_sha: str, lang: str, repo: pathlib.Path) -> dict[pathlib.Path, str]:
    core = "ascham-core/src/main/java/io/ascham"
    result: dict[pathlib.Path, str] = {}
    if lang == "java":
        result[repo / core / "segment/SegmentFormat.java"] = emit_java_segment_format(m, manifest_sha)
        result[repo / core / "layout/PhysicalKind.java"] = emit_java_physical_kind(m, manifest_sha)
        result[repo / core / "schema/MetadataKeys.java"] = emit_java_metadata_keys(m, manifest_sha)
        result[repo / "conformance/type_profile_vectors.json"] = emit_type_vectors(m, manifest_sha)
    elif lang == "cpp":
        result[repo / "src/format/arena_format_gen.hpp"] = emit_cpp_header(m, manifest_sha)
    else:
        raise SystemExit(f"unknown --lang {lang}")
    spec_doc = repo / "docs/segment-format.md"
    if spec_doc.exists():
        rewritten = rewrite_spec(m, spec_doc.read_text())
        result[spec_doc] = rewritten
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--lang", required=True, choices=["java", "cpp"])
    parser.add_argument("--repo", required=True, type=pathlib.Path, help="repo root to write into")
    parser.add_argument("--manifest", type=pathlib.Path, default=MANIFEST)
    parser.add_argument(
        "--check", action="store_true", help="diff generated output against the working tree; exit 1 on drift"
    )
    args = parser.parse_args()

    raw = args.manifest.read_bytes()
    manifest_sha = hashlib.sha256(raw).hexdigest()[:12]
    m = tomllib.loads(raw.decode())
    if m.get("manifest-schema") != 1:
        raise SystemExit(f"unsupported manifest-schema {m.get('manifest-schema')} (generator understands 1)")
    validate(m)

    drift = False
    for path, content in outputs(m, manifest_sha, args.lang, args.repo.resolve()).items():
        if args.check:
            actual = path.read_text() if path.exists() else ""
            if actual != content:
                drift = True
                diff = difflib.unified_diff(
                    actual.splitlines(keepends=True),
                    content.splitlines(keepends=True),
                    fromfile=f"{path} (working tree)",
                    tofile=f"{path} (generated)",
                )
                sys.stderr.writelines(diff)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            print(f"wrote {path}")
    if drift:
        print(
            "error: generated files do not match spec/format-manifest.toml —"
            " run spec/generate_format.py without --check (or revert the hand-edit)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
