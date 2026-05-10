"""Tests for the in-process full-lineage walker."""

from __future__ import annotations

from pathlib import Path

from dbtree_lineage.manifest import DbtManifest
from dbtree_lineage.walker import walk_full_lineage


def _walk(project: Path, seed_model: str, seed_col: str) -> list[dict]:
    manifest = DbtManifest.from_project(project)
    schema = manifest.build_sqlglot_schema() or None
    return walk_full_lineage(
        manifest=manifest,
        seed_uid=manifest.resolve_model(seed_model).unique_id,
        seed_column=seed_col,
        dialect=manifest.dialect,
        schema=schema,
    )


def test_walk_collects_upstream_edge_for_passthrough_column(dbt_project_inline: Path) -> None:
    edges = _walk(dbt_project_inline, "fct_orders", "id")
    # fct_orders.id <- stg_orders.id <- raw.orders.id
    assert any(
        e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "id"
        and e["source_unique_id"] == "model.demo.stg_orders"
        and e["source_column"] == "id"
        for e in edges
    )


def test_walk_collects_downstream_edge_from_seed(dbt_project_inline: Path) -> None:
    edges = _walk(dbt_project_inline, "stg_orders", "id")
    # downstream from stg_orders.id should reach fct_orders.id
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders"
        and e["source_column"] == "id"
        and e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "id"
        for e in edges
    )


def test_walk_records_expression_when_column_is_derived(dbt_project_inline: Path) -> None:
    edges = _walk(dbt_project_inline, "fct_orders", "amount_with_tax")
    # fct_orders.amount_with_tax = stg_orders.amount * 1.05
    derived = [
        e for e in edges
        if e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "amount_with_tax"
    ]
    assert derived, "expected at least one upstream edge for amount_with_tax"
    assert all(e["expression"] for e in derived), (
        "derived columns must record the SQL expression"
    )


def test_walk_with_compiled_code_on_disk_works(dbt_project_file: Path) -> None:
    edges = _walk(dbt_project_file, "fct_orders", "id")
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders" for e in edges
    )


def test_walk_returns_empty_for_unknown_column(dbt_project_inline: Path) -> None:
    # sqlglot will raise; walker swallows per-call failures and continues.
    edges = _walk(dbt_project_inline, "fct_orders", "nonexistent_column")
    # Walker never crashes; either returns [] or surfaces edges where
    # sqlglot succeeded for downstream-only branches. For a leaf model
    # (fct_orders has no downstream in the fixture), we expect empty.
    assert edges == []
