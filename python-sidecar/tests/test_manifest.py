"""Tests for manifest reader: model resolution, dialect mapping, compiled SQL routes."""

from __future__ import annotations

from pathlib import Path

import pytest

from dbtree_lineage.manifest import DbtManifest, ManifestError


def test_dialect_from_adapter(dbt_project_inline: Path) -> None:
    m = DbtManifest.from_project(dbt_project_inline)
    assert m.adapter_type == "postgres"
    assert m.dialect == "postgres"


def test_resolve_by_short_name(dbt_project_inline: Path) -> None:
    m = DbtManifest.from_project(dbt_project_inline)
    ref = m.resolve_model("fct_orders")
    assert ref.unique_id == "model.demo.fct_orders"
    assert ref.name == "fct_orders"
    assert "amount * 1.05" in ref.compiled_sql


def test_resolve_by_unique_id(dbt_project_inline: Path) -> None:
    m = DbtManifest.from_project(dbt_project_inline)
    ref = m.resolve_model("model.demo.stg_orders")
    assert ref.unique_id == "model.demo.stg_orders"
    assert ref.depends_on == []


def test_resolve_unknown_raises(dbt_project_inline: Path) -> None:
    m = DbtManifest.from_project(dbt_project_inline)
    with pytest.raises(ManifestError, match="No model named"):
        m.resolve_model("does_not_exist")


def test_inline_compiled_code_route(dbt_project_inline: Path) -> None:
    m = DbtManifest.from_project(dbt_project_inline)
    ref = m.resolve_model("fct_orders")
    # When manifest has compiled_code embedded, we should not need to read from disk.
    # Verify by deleting the file and re-resolving.
    (dbt_project_inline / "target" / "compiled" / "demo" / "models" / "fct_orders.sql").unlink()
    m2 = DbtManifest.from_project(dbt_project_inline)
    ref2 = m2.resolve_model("fct_orders")
    assert ref2.compiled_sql == ref.compiled_sql


def test_compiled_path_route(dbt_project_file: Path) -> None:
    m = DbtManifest.from_project(dbt_project_file)
    ref = m.resolve_model("fct_orders")
    assert "amount * 1.05" in ref.compiled_sql


def test_missing_manifest_raises(tmp_path: Path) -> None:
    with pytest.raises(ManifestError, match="not found"):
        DbtManifest.from_project(tmp_path)
