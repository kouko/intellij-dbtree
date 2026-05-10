"""Tests for the in-process full-lineage walker."""

from __future__ import annotations

from pathlib import Path

from dbtree_lineage.manifest import DbtManifest
from dbtree_lineage.walker import walk_full_lineage


def _walk(
    project: Path, seed_model: str, seed_col: str,
) -> tuple[list[dict], str | None]:
    manifest = DbtManifest.from_project(project)
    schema = manifest.build_sqlglot_schema() or None
    return walk_full_lineage(
        manifest=manifest,
        seed_uid=manifest.resolve_unique_id(seed_model),
        seed_column=seed_col,
        dialect=manifest.dialect,
        schema=schema,
    )


def _edges(project: Path, seed_model: str, seed_col: str) -> list[dict]:
    return _walk(project, seed_model, seed_col)[0]


def test_walk_collects_upstream_edge_for_passthrough_column(dbt_project_inline: Path) -> None:
    edges = _edges(dbt_project_inline, "fct_orders", "id")
    # fct_orders.id <- stg_orders.id <- raw.orders.id
    assert any(
        e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "id"
        and e["source_unique_id"] == "model.demo.stg_orders"
        and e["source_column"] == "id"
        for e in edges
    )


def test_walk_collects_downstream_edge_from_seed(dbt_project_inline: Path) -> None:
    edges = _edges(dbt_project_inline, "stg_orders", "id")
    # downstream from stg_orders.id should reach fct_orders.id
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders"
        and e["source_column"] == "id"
        and e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "id"
        for e in edges
    )


def test_walk_records_expression_when_column_is_derived(dbt_project_inline: Path) -> None:
    edges = _edges(dbt_project_inline, "fct_orders", "amount_with_tax")
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
    edges = _edges(dbt_project_file, "fct_orders", "id")
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders" for e in edges
    )


def test_walk_handles_seed_without_compiled_sql(tmp_path: Path) -> None:
    """When the seed model has no compiled SQL but its children do, the
    walker must still produce downstream edges. Regression for the case
    where dbt compile was run only for some models."""
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    fct_sql = "SELECT id, customer_id FROM analytics.stg_orders"
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.stg_orders": {
                "unique_id": "model.demo.stg_orders",
                "name": "stg_orders",
                "package_name": "demo",
                "resource_type": "model",
                "compiled_code": None,
                "compiled_path": None,
                "depends_on": {"nodes": []},
            },
            "model.demo.fct_orders": {
                "unique_id": "model.demo.fct_orders",
                "name": "fct_orders",
                "package_name": "demo",
                "resource_type": "model",
                "compiled_code": fct_sql,
                "depends_on": {"nodes": ["model.demo.stg_orders"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )
    edges = _edges(project, "stg_orders", "id")
    # Seed has no SQL, so no upstream edges. But downstream walk through
    # fct_orders should still find the stg_orders.id -> fct_orders.id edge.
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders"
        and e["target_unique_id"] == "model.demo.fct_orders"
        for e in edges
    )


def test_walk_emits_notice_when_no_model_has_compiled_sql(tmp_path: Path) -> None:
    """When the entire manifest is uncompiled (a dbt parse / deps run
    leaves compiled_code=null), the walker should surface a notice so
    the plugin can hint the user to run dbt compile."""
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.parent": {
                "unique_id": "model.demo.parent", "name": "parent",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "depends_on": {"nodes": []},
            },
            "model.demo.child": {
                "unique_id": "model.demo.child", "name": "child",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "depends_on": {"nodes": ["model.demo.parent"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )
    edges, notice = _walk(project, "parent", "id")
    assert edges == []
    assert notice is not None
    assert "dbt compile" in notice


def test_walk_no_notice_when_some_edges_found(dbt_project_inline: Path) -> None:
    """A successful trace should not include a notice — partial results
    are their own signal."""
    edges, notice = _walk(dbt_project_inline, "fct_orders", "id")
    assert edges, "fixture should produce at least one edge"
    assert notice is None


def test_walk_returns_empty_for_unknown_column(dbt_project_inline: Path) -> None:
    # sqlglot will raise; walker swallows per-call failures and continues.
    edges = _edges(dbt_project_inline, "fct_orders", "nonexistent_column")
    # Walker never crashes; either returns [] or surfaces edges where
    # sqlglot succeeded for downstream-only branches. For a leaf model
    # (fct_orders has no downstream in the fixture), we expect empty.
    assert edges == []
