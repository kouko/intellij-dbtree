# dbtree-lineage (Python sidecar)

Phase C of [intellij-dbtree](../README.md). Reads dbt artifacts and emits column-level lineage JSON via [sqlglot](https://github.com/tobymao/sqlglot).

## Setup

```bash
cd python-sidecar
uv sync --extra dev
uv run pytest
```

## CLI

```bash
# After Phase C is implemented:
uv run dbtree-lineage \
  --project-dir /path/to/dbt-project \
  --model orders \
  --column amount_with_tax
```

Outputs JSON describing the upstream column lineage tree.

## Why a sidecar

JVM has no column-level SQL lineage library at sqlglot's level. The Kotlin plugin (Phase A) will spawn this as a subprocess and talk JSON-RPC over stdio.
