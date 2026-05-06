"""Shared fixtures: synthetic dbt project layouts on tmp_path."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# Two models:
#   stg_orders : raw passthrough from source.raw.orders
#   fct_orders : SELECT id, amount * 1.05 AS amount_with_tax FROM stg_orders
#
# We test both compiled_code-inline and compiled_path-on-disk routes.


_STG_ORDERS_SQL = """\
SELECT id, customer_id, amount, created_at
FROM raw.orders
"""

_FCT_ORDERS_SQL = """\
SELECT
  id,
  customer_id,
  amount * 1.05 AS amount_with_tax
FROM analytics.stg_orders
"""


def _make_manifest(
    project_dir: Path,
    *,
    inline_compiled_code: bool,
) -> dict[str, object]:
    compiled_root = project_dir / "target" / "compiled" / "demo" / "models"
    compiled_root.mkdir(parents=True, exist_ok=True)
    stg_compiled = compiled_root / "stg_orders.sql"
    fct_compiled = compiled_root / "fct_orders.sql"
    stg_compiled.write_text(_STG_ORDERS_SQL, encoding="utf-8")
    fct_compiled.write_text(_FCT_ORDERS_SQL, encoding="utf-8")

    def node(name: str, sql: str, compiled_path: Path, depends_on: list[str]) -> dict[str, object]:
        return {
            "unique_id": f"model.demo.{name}",
            "name": name,
            "package_name": "demo",
            "resource_type": "model",
            "path": f"{name}.sql",
            "original_file_path": f"models/{name}.sql",
            "compiled_path": str(compiled_path.relative_to(project_dir)),
            "compiled_code": sql if inline_compiled_code else None,
            "depends_on": {"nodes": depends_on},
        }

    return {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.stg_orders": node(
                "stg_orders", _STG_ORDERS_SQL, stg_compiled, depends_on=[]
            ),
            "model.demo.fct_orders": node(
                "fct_orders",
                _FCT_ORDERS_SQL,
                fct_compiled,
                depends_on=["model.demo.stg_orders"],
            ),
        },
        "child_map": {
            "model.demo.stg_orders": ["model.demo.fct_orders"],
            "model.demo.fct_orders": [],
        },
        "parent_map": {
            "model.demo.stg_orders": [],
            "model.demo.fct_orders": ["model.demo.stg_orders"],
        },
    }


@pytest.fixture
def dbt_project_inline(tmp_path: Path) -> Path:
    """A dbt project where compiled SQL is embedded in manifest (dbt 1.7+ default)."""
    project = tmp_path / "demo_project"
    (project / "target").mkdir(parents=True)
    manifest = _make_manifest(project, inline_compiled_code=True)
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    return project


@pytest.fixture
def dbt_project_file(tmp_path: Path) -> Path:
    """A dbt project where compiled SQL must be read from disk via compiled_path."""
    project = tmp_path / "demo_project"
    (project / "target").mkdir(parents=True)
    manifest = _make_manifest(project, inline_compiled_code=False)
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    return project
