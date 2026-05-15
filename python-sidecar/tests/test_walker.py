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
    # 0 of 2 models compiled — the "no compile ever" variant.
    assert "0 of 2" in notice


def test_walk_notice_distinguishes_partial_compile(tmp_path: Path) -> None:
    """When SOME models have compiled SQL but the trace path doesn't
    reach them, the notice should surface the partial-compile count
    rather than the generic "dbt compile" hint — same actionable text
    points the user to look for compile failures."""
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    # 3 models, 1 compiled, 0 of them on the trace path
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.a": {
                "unique_id": "model.demo.a", "name": "a",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "depends_on": {"nodes": []},
            },
            "model.demo.b": {
                "unique_id": "model.demo.b", "name": "b",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "depends_on": {"nodes": ["model.demo.a"]},
            },
            "model.demo.c": {
                "unique_id": "model.demo.c", "name": "c",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM unrelated",
                "depends_on": {"nodes": []},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )
    edges, notice = _walk(project, "a", "id")
    assert edges == []
    assert notice is not None
    # Partial-compile variant surfaces the actual ratio.
    assert "1 of 3" in notice
    assert "failed models" in notice


def test_walk_no_notice_when_some_edges_found(dbt_project_inline: Path) -> None:
    """A successful trace should not include a notice — partial results
    are their own signal."""
    edges, notice = _walk(dbt_project_inline, "fct_orders", "id")
    assert edges, "fixture should produce at least one edge"
    assert notice is None


def test_walk_falls_back_to_compiled_files_on_disk(tmp_path: Path) -> None:
    """When manifest lacks compiled_code AND compiled_path (because a
    subsequent `dbt parse` overwrote them), but the previously-compiled
    SQL files are still on disk, the walker should fall back to reading
    them via the standard `target/compiled/<pkg>/<original_file_path>`
    path. Reflects the iCHEF case where IDE-driven dbt commands kept
    wiping the manifest compile metadata."""
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    # Lay the compiled SQL files on disk under the canonical path.
    stg_dir = project / "target" / "compiled" / "demo" / "models"
    stg_dir.mkdir(parents=True, exist_ok=True)
    (stg_dir / "stg_orders.sql").write_text(
        "SELECT id, customer_id, amount FROM raw.orders", encoding="utf-8",
    )
    (stg_dir / "fct_orders.sql").write_text(
        "SELECT id, customer_id FROM analytics.stg_orders", encoding="utf-8",
    )
    # Manifest has the models registered but NO compiled_code / compiled_path —
    # mimicking what dbt parse leaves behind.
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.stg_orders": {
                "unique_id": "model.demo.stg_orders", "name": "stg_orders",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "original_file_path": "models/stg_orders.sql",
                "depends_on": {"nodes": []},
            },
            "model.demo.fct_orders": {
                "unique_id": "model.demo.fct_orders", "name": "fct_orders",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": None, "compiled_path": None,
                "original_file_path": "models/fct_orders.sql",
                "depends_on": {"nodes": ["model.demo.stg_orders"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )
    edges, notice = _walk(project, "fct_orders", "id")
    # Walker should successfully resolve compiled SQL from disk and trace
    # the upstream edge — no notice should fire.
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders"
        and e["target_unique_id"] == "model.demo.fct_orders"
        for e in edges
    )
    assert notice is None


def test_walk_invokes_on_edge_callback_for_each_edge(dbt_project_inline: Path) -> None:
    """The streaming walker variant: ``on_edge`` fires synchronously as
    each edge is discovered. The final returned list is identical to
    the non-streaming contract, so callers can compare incremental vs
    batch output without divergence."""
    manifest = DbtManifest.from_project(dbt_project_inline)
    schema = manifest.build_sqlglot_schema() or None

    streamed: list[dict] = []
    edges, _ = walk_full_lineage(
        manifest=manifest,
        seed_uid=manifest.resolve_unique_id("fct_orders"),
        seed_column="id",
        dialect=manifest.dialect,
        schema=schema,
        on_edge=lambda e: streamed.append(e),
    )

    # Every edge in the final list was also emitted via on_edge.
    assert streamed == edges
    # At least one edge — regression guard that the callback was wired
    # into the walker, not silently dropped.
    assert len(streamed) >= 1


def test_walk_returns_empty_for_unknown_column(dbt_project_inline: Path) -> None:
    # sqlglot will raise; walker swallows per-call failures and continues.
    edges = _edges(dbt_project_inline, "fct_orders", "nonexistent_column")
    # Walker never crashes; either returns [] or surfaces edges where
    # sqlglot succeeded for downstream-only branches. For a leaf model
    # (fct_orders has no downstream in the fixture), we expect empty.
    assert edges == []


# ---------------------------------------------------------------------------
# New behaviour 3: walker caches + relaxed qualify
# ---------------------------------------------------------------------------


def test_walk_is_idempotent_with_cache(dbt_project_inline: Path) -> None:
    """Two successive walk_full_lineage calls on the same project produce
    identical edges — each call builds its own closure caches and they
    don't bleed into each other."""
    edges_a = _edges(dbt_project_inline, "fct_orders", "id")
    edges_b = _edges(dbt_project_inline, "fct_orders", "id")

    # Both runs must be non-empty (regression guard)
    assert edges_a, "first walk produced no edges"

    # Edge lists must be identical (same dicts, same order is not required
    # since walk order is deterministic — but identical set is required)
    def _edge_key(e: dict) -> tuple:
        return (
            e["source_unique_id"],
            e["source_column"],
            e["target_unique_id"],
            e["target_column"],
        )

    assert sorted(edges_a, key=_edge_key) == sorted(edges_b, key=_edge_key), (
        "second walk returned different edges — caches leaked state"
    )


def test_walk_cache_does_not_taint_different_column(dbt_project_inline: Path) -> None:
    """Walking column A then column B must give the same result for B as
    walking B standalone (the per-walk caches must not confuse columns)."""
    edges_id_first = _edges(dbt_project_inline, "fct_orders", "id")
    edges_tax_after_id = _edges(dbt_project_inline, "fct_orders", "amount_with_tax")
    edges_tax_standalone = _edges(dbt_project_inline, "fct_orders", "amount_with_tax")

    def _edge_key(e: dict) -> tuple:
        return (
            e["source_unique_id"],
            e["source_column"],
            e["target_unique_id"],
            e["target_column"],
        )

    # amount_with_tax result must be the same regardless of prior id walk
    assert sorted(edges_tax_after_id, key=_edge_key) == sorted(
        edges_tax_standalone, key=_edge_key
    ), "walking 'id' first tainted the 'amount_with_tax' result"

    # Sanity: id and amount_with_tax are different columns — edges differ
    assert edges_id_first != edges_tax_standalone, (
        "fixture unexpectedly returned the same edges for two distinct columns"
    )


def test_walk_tolerates_redshift_dateadd_syntax(tmp_path: Path) -> None:
    """Walker must not crash and must produce upstream/downstream edges when
    a model's WHERE clause uses Redshift-specific DATEADD(day, -30, …).

    The strict qualify pass raised UnexpectedTokenError on 'day' as an
    unresolved column reference; the relaxed qualify (validate_qualify_columns
    =False, identify=False) accepts it, allowing lineage extraction to succeed.
    """
    import json

    project = tmp_path / "redshift_demo"
    (project / "target").mkdir(parents=True)

    stg_sql = "SELECT id, customer_id, amount FROM raw.orders"
    # DATEADD(day, …) is Redshift dialect-specific and would crash strict qualify
    fct_sql = """\
SELECT
  id,
  customer_id,
  amount
FROM analytics.stg_orders
WHERE created_at > DATEADD(day, -30, CURRENT_DATE)
"""
    manifest_dict = {
        "metadata": {"adapter_type": "redshift"},
        "nodes": {
            "model.demo.stg_orders": {
                "unique_id": "model.demo.stg_orders",
                "name": "stg_orders",
                "package_name": "demo",
                "resource_type": "model",
                "compiled_code": stg_sql,
                "compiled_path": None,
                "depends_on": {"nodes": []},
            },
            "model.demo.fct_orders": {
                "unique_id": "model.demo.fct_orders",
                "name": "fct_orders",
                "package_name": "demo",
                "resource_type": "model",
                "compiled_code": fct_sql,
                "compiled_path": None,
                "depends_on": {"nodes": ["model.demo.stg_orders"]},
            },
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
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8"
    )

    manifest = DbtManifest.from_project(project)
    schema = manifest.build_sqlglot_schema() or None

    # Must not raise; must produce at least the downstream edge
    # stg_orders.id → fct_orders.id
    edges, notice = walk_full_lineage(
        manifest=manifest,
        seed_uid=manifest.resolve_unique_id("stg_orders"),
        seed_column="id",
        dialect="redshift",
        schema=schema,
    )

    assert notice is None, f"unexpected notice: {notice}"
    assert any(
        e["source_unique_id"] == "model.demo.stg_orders"
        and e["source_column"] == "id"
        and e["target_unique_id"] == "model.demo.fct_orders"
        and e["target_column"] == "id"
        for e in edges
    ), f"expected downstream edge not found; got: {edges}"
