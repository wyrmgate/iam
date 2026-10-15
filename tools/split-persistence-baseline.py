#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

CAPABILITIES = [
    "platform",
    "identity",
    "catalog",
    "access",
    "governance",
    "credential",
    "integration",
    "administration",
    "audit",
]

OBJECT_SCHEMA_PATTERNS = [
    re.compile(r"^CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^CREATE\s+(?:MATERIALIZED\s+)?VIEW\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^CREATE\s+TABLE\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^CREATE\s+SEQUENCE\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^ALTER\s+TABLE(?:\s+ONLY)?\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^ALTER\s+SEQUENCE\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^ALTER\s+FUNCTION\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^CREATE\s+(?:UNIQUE\s+)?INDEX\b.*?\s+ON\s+(?:ONLY\s+)?([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^CREATE\s+TRIGGER\b.*?\s+ON\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
    re.compile(r"^COMMENT\s+ON\s+(?:TABLE|COLUMN|FUNCTION|VIEW|MATERIALIZED\s+VIEW|SEQUENCE)\s+([a-z_][a-z0-9_]*)\.", re.I | re.S),
]


def split_statements(sql: str) -> list[str]:
    statements: list[str] = []
    start = 0
    i = 0
    single = False
    double = False
    line_comment = False
    block_comment = False
    dollar_tag: str | None = None

    while i < len(sql):
        if line_comment:
            if sql[i] == "\n":
                line_comment = False
            i += 1
            continue
        if block_comment:
            if sql.startswith("*/", i):
                block_comment = False
                i += 2
            else:
                i += 1
            continue
        if dollar_tag is not None:
            if sql.startswith(dollar_tag, i):
                i += len(dollar_tag)
                dollar_tag = None
            else:
                i += 1
            continue
        if single:
            if sql[i] == "'":
                if i + 1 < len(sql) and sql[i + 1] == "'":
                    i += 2
                else:
                    single = False
                    i += 1
            else:
                i += 1
            continue
        if double:
            if sql[i] == '"':
                if i + 1 < len(sql) and sql[i + 1] == '"':
                    i += 2
                else:
                    double = False
                    i += 1
            else:
                i += 1
            continue

        if sql.startswith("--", i):
            line_comment = True
            i += 2
            continue
        if sql.startswith("/*", i):
            block_comment = True
            i += 2
            continue
        if sql[i] == "'":
            single = True
            i += 1
            continue
        if sql[i] == '"':
            double = True
            i += 1
            continue
        if sql[i] == "$":
            match = re.match(r"\$[A-Za-z_][A-Za-z0-9_]*\$|\$\$", sql[i:])
            if match:
                dollar_tag = match.group(0)
                i += len(dollar_tag)
                continue
        if sql[i] == ";":
            statement = sql[start : i + 1].strip()
            if statement:
                statements.append(statement)
            start = i + 1
        i += 1

    tail = sql[start:].strip()
    if tail:
        raise ValueError(f"unterminated SQL statement: {tail[:120]!r}")
    return statements


def owner_schema(statement: str) -> str | None:
    for pattern in OBJECT_SCHEMA_PATTERNS:
        match = pattern.match(statement)
        if match:
            return match.group(1).lower()
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    statements = split_statements(args.source.read_text())
    args.output.mkdir(parents=True, exist_ok=True)

    schema_statements: list[str] = []
    buckets: dict[str, list[str]] = {cap: [] for cap in CAPABILITIES}
    unknown: list[str] = []

    for statement in statements:
        schema_match = re.match(r"^CREATE\s+SCHEMA\s+([a-z_][a-z0-9_]*)\s*;?$", statement, re.I | re.S)
        schema_comment = re.match(r"^COMMENT\s+ON\s+SCHEMA\s+([a-z_][a-z0-9_]*)\b", statement, re.I | re.S)
        if schema_match:
            schema = schema_match.group(1).lower()
            if schema not in CAPABILITIES:
                unknown.append(statement)
            else:
                schema_statements.append(statement)
            continue
        if schema_comment:
            schema = schema_comment.group(1).lower()
            if schema not in CAPABILITIES:
                unknown.append(statement)
            else:
                schema_statements.append(statement)
            continue

        schema = owner_schema(statement)
        if schema in buckets:
            buckets[schema].append(statement)
        else:
            unknown.append(statement)

    if unknown:
        print("Unclassified SQL statements:")
        for statement in unknown:
            print("---")
            print(statement[:1000])
        raise SystemExit(2)

    v1 = args.output / "V1__create_capability_schemas.sql"
    v1.write_text(
        "-- Current Wyrmgate IAM pre-release schema baseline.\n"
        "-- Capability schemas are physical organization only; capability ownership remains semantic.\n\n"
        + "\n\n".join(schema_statements)
        + "\n"
    )

    for version, capability in enumerate(CAPABILITIES, start=2):
        statements_for_capability = buckets[capability]
        has_function = any(re.match(r"^CREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\b", s, re.I) for s in statements_for_capability)
        header = [
            f"-- {capability.capitalize()} capability current-state persistence baseline.",
            "-- Generated once from the verified pre-rebaseline schema, then maintained as reviewed source.",
        ]
        if has_function:
            header += [
                "-- Some function bodies reference tables created later in this same capability baseline.",
                "SET check_function_bodies = false;",
            ]
        path = args.output / f"V{version}__{capability}_baseline.sql"
        path.write_text("\n".join(header) + "\n\n" + "\n\n".join(statements_for_capability) + "\n")
        print(f"{path.name}: {len(statements_for_capability)} statements")

    print(f"{v1.name}: {len(schema_statements)} statements")
    print(f"total statements: {len(statements)}")


if __name__ == "__main__":
    main()
