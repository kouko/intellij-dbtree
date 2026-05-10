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

from .lineage import collect_source_columns, extract_column_lineage, list_output_columns
from .manifest import DbtManifest, ManifestError
from .walker import walk_full_lineage


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
    p.add_argument(
        "--column",
        required=False,
        help="Column name to trace. Required unless --all-columns is set.",
    )
    p.add_argument(
        "--all-columns",
        action="store_true",
        help="Return lineage for every column in the model (single Python startup).",
    )
    p.add_argument(
        "--list-columns",
        action="store_true",
        help="Return only the names of columns the model's compiled SQL "
             "produces (no lineage). Used by the plugin to populate cards "
             "for models lacking schema.yml docs.",
    )
    p.add_argument(
        "--full-walk",
        action="store_true",
        help="Walk both upstream and downstream from --column entirely "
             "in-process and return a flat list of column edges. One "
             "sidecar invocation does the work of N-per-(uid,col) calls "
             "the plugin used to make. Requires --column.",
    )
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

    dialect = args.dialect or manifest.dialect
    schema = manifest.build_sqlglot_schema()
    schema_arg = schema if schema else None

    # --full-walk only needs the seed unique_id; per-node SQL is loaded
    # lazily by the walker and missing-SQL nodes are skipped gracefully.
    # Resolving the seed via [resolve_model] would prematurely fail on
    # uncompiled seed models even though their downstream children may
    # still produce useful edges.
    if args.full_walk:
        if not args.column:
            raise SystemExit("--column is required with --full-walk")
        seed_uid = manifest.resolve_unique_id(args.model)
        meta = manifest.node_metadata(seed_uid)
        base = {
            "model": {
                "unique_id": seed_uid,
                "name": meta["name"],
                "package_name": meta["package_name"],
            },
            "dialect": dialect,
        }
        edges = walk_full_lineage(
            manifest=manifest,
            seed_uid=seed_uid,
            seed_column=args.column,
            dialect=dialect,
            schema=schema_arg,
        )
        return {**base, "column": args.column, "edges": edges}

    model = manifest.resolve_model(args.model)
    base = {
        "model": {
            "unique_id": model.unique_id,
            "name": model.name,
            "package_name": model.package_name,
        },
        "dialect": dialect,
    }

    if args.list_columns:
        # Parse the model's SELECT and return its output column names.
        # Used by the plugin to populate model cards whose dbt yml docs
        # don't list columns. Cheap operation (no per-column lineage walk),
        # so suitable for lazy on-demand calls when the user expands a card.
        try:
            names = list_output_columns(
                sql=model.compiled_sql,
                dialect=dialect,
                schema=schema_arg,
            )
            return {**base, "columns": names}
        except Exception as e:  # noqa: BLE001 — surface error to plugin, don't crash
            return {**base, "columns": [], "error": str(e)}

    if args.all_columns:
        # Single Python startup, parse the SQL once via sqlglot, query each
        # column's lineage. Used by the JetBrains plugin's downstream walk.
        column_names = manifest.list_model_columns(model.unique_id)
        results: list[dict[str, Any]] = []
        for col in column_names:
            try:
                tree = extract_column_lineage(
                    column=col,
                    sql=model.compiled_sql,
                    dialect=dialect,
                    schema=schema_arg,
                )
            except Exception as e:  # noqa: BLE001 — record per-column failures, keep going
                results.append({"column": col, "error": str(e)})
                continue
            results.append(
                {
                    "column": col,
                    "lineage": tree.to_dict(),
                    "source_columns": [
                        {"table": t, "column": c} for t, c in collect_source_columns(tree)
                    ],
                }
            )
        return {**base, "columns": results}

    if not args.column:
        raise SystemExit("--column is required unless --all-columns is set")

    tree = extract_column_lineage(
        column=args.column,
        sql=model.compiled_sql,
        dialect=dialect,
        schema=schema_arg,
    )
    return {
        **base,
        "column": args.column,
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
