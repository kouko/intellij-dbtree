"""CLI entry point: ``dbtree-lineage --project-dir … --model … --column …``.

Reads ``target/manifest.json``, locates the requested model's compiled SQL,
runs sqlglot column lineage, and writes JSON to stdout.

Exit codes:
  0 — success
  1 — usage / argument error
  2 — manifest or model not found
  3 — sqlglot lineage failure (e.g. column not present in compiled SQL)
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .lineage import collect_source_columns, extract_column_lineage
from .manifest import DbtManifest, ManifestError


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="dbtree-lineage",
        description="Extract column-level lineage from a dbt project using sqlglot.",
    )
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument(
        "--project-dir",
        type=Path,
        help="Path to the dbt project root (expects target/manifest.json inside).",
    )
    src.add_argument(
        "--manifest",
        type=Path,
        help="Direct path to manifest.json (alternative to --project-dir).",
    )
    p.add_argument("--model", required=True, help="Model name or unique_id.")
    p.add_argument("--column", required=True, help="Column name to trace.")
    p.add_argument(
        "--dialect",
        default=None,
        help="Override sqlglot dialect (default: derived from manifest adapter_type).",
    )
    p.add_argument(
        "--pretty",
        action="store_true",
        help="Pretty-print the JSON output.",
    )
    return p


def run(args: argparse.Namespace) -> dict[str, Any]:
    if args.project_dir is not None:
        manifest = DbtManifest.from_project(args.project_dir)
    else:
        manifest = DbtManifest.load(args.manifest)

    model = manifest.resolve_model(args.model)
    dialect = args.dialect or manifest.dialect

    tree = extract_column_lineage(
        column=args.column,
        sql=model.compiled_sql,
        dialect=dialect,
    )

    return {
        "model": {
            "unique_id": model.unique_id,
            "name": model.name,
            "package_name": model.package_name,
        },
        "column": args.column,
        "dialect": dialect,
        "lineage": tree.to_dict(),
        "source_columns": [
            {"table": t, "column": c} for t, c in collect_source_columns(tree)
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        result = run(args)
    except ManifestError as e:
        print(f"manifest error: {e}", file=sys.stderr)
        return 2
    except Exception as e:  # noqa: BLE001 — surfaced to user via stderr
        print(f"lineage error: {e}", file=sys.stderr)
        return 3

    indent = 2 if args.pretty else None
    json.dump(result, sys.stdout, indent=indent, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
