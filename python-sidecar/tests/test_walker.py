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


def test_walk_resolves_select_star_from_undocumented_upstream(tmp_path: Path) -> None:
    """When a model does ``SELECT * FROM {{ ref(upstream) }}`` and the
    upstream has no schema.yml docs and no catalog row, column-lineage
    tracing should still resolve the leaf to the upstream model's
    column. The fix needs two coordinated pieces:

      1. ``build_sqlglot_schema`` parses each model's compiled SQL when
         no docs exist, so the upstream's output column names get into
         the schema.
      2. The walker excludes the current model's own entry from the
         schema before qualifying its SQL — otherwise sqlglot treats a
         bare column reference as ambiguously belonging to the model
         itself and stops adding the upstream's table prefix, breaking
         the trace.

    Without either piece, this regression for the iCHEF business-denom
    file (lots of ``SELECT * FROM ...``-via-CTE plumbing) would
    silently produce zero column edges.
    """
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    upstream_sql = "SELECT id, amount, customer_id FROM raw.orders"
    # `passthrough` projects upstream via SELECT * inside a CTE.
    passthrough_sql = (
        "WITH src AS (SELECT * FROM analytics.upstream) "
        "SELECT * FROM src"
    )
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.upstream": {
                "unique_id": "model.demo.upstream", "name": "upstream",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": upstream_sql, "compiled_path": None,
                "depends_on": {"nodes": []},
                # Crucially: no `columns` block at all — schema.yml absent
            },
            "model.demo.passthrough": {
                "unique_id": "model.demo.passthrough", "name": "passthrough",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": passthrough_sql, "compiled_path": None,
                "depends_on": {"nodes": ["model.demo.upstream"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8"
    )

    edges = _edges(project, "upstream", "amount")
    assert any(
        e["source_unique_id"] == "model.demo.upstream"
        and e["source_column"] == "amount"
        and e["target_unique_id"] == "model.demo.passthrough"
        and e["target_column"] == "amount"
        for e in edges
    ), f"upstream.amount → passthrough.amount not traced; edges={edges}"


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


# ---------------------------------------------------------------------------
# BFS traversal + priority ordering (0.4.6 behaviour)
# ---------------------------------------------------------------------------


def _edge_streamed_order(project: Path, seed_model: str, seed_col: str) -> list[dict]:
    """Return the streamed (in-flight) edge order — what the JCEF panel
    would see live, before any post-walk reordering."""
    streamed: list[dict] = []
    manifest = DbtManifest.from_project(project)
    schema = manifest.build_sqlglot_schema() or None
    walk_full_lineage(
        manifest=manifest,
        seed_uid=manifest.resolve_unique_id(seed_model),
        seed_column=seed_col,
        dialect=manifest.dialect,
        schema=schema,
        on_edge=lambda e: streamed.append(e),
    )
    return streamed


def test_walk_down_emits_hop1_edges_before_hop2(tmp_path: Path) -> None:
    """BFS guarantee: every edge at hop=1 from the seed is emitted before
    any edge at hop=2. Under the old DFS walker, the deepest leaf of the
    first branch came out before the second hop-1 sibling — disorienting
    UX. The BFS rewrite makes the stream feel like a ripple expanding
    radially from the seed.
    """
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    #  seed → child_a (hop 1) → grand_a (hop 2)
    #  seed → child_b (hop 1) → grand_b (hop 2)
    # Under DFS the order would be: seed→child_a, child_a→grand_a,
    # seed→child_b, child_b→grand_b. Under BFS we expect both hop=1
    # edges before any hop=2 edge.
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.seed": {
                "unique_id": "model.demo.seed", "name": "seed",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM raw.orders",
                "depends_on": {"nodes": []},
            },
            "model.demo.child_a": {
                "unique_id": "model.demo.child_a", "name": "child_a",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM analytics.seed",
                "depends_on": {"nodes": ["model.demo.seed"]},
            },
            "model.demo.child_b": {
                "unique_id": "model.demo.child_b", "name": "child_b",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM analytics.seed",
                "depends_on": {"nodes": ["model.demo.seed"]},
            },
            "model.demo.grand_a": {
                "unique_id": "model.demo.grand_a", "name": "grand_a",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM analytics.child_a",
                "depends_on": {"nodes": ["model.demo.child_a"]},
            },
            "model.demo.grand_b": {
                "unique_id": "model.demo.grand_b", "name": "grand_b",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id FROM analytics.child_b",
                "depends_on": {"nodes": ["model.demo.child_b"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )

    streamed = _edge_streamed_order(project, "seed", "id")

    def _hop(edge: dict) -> int:
        # hop number = how many edges from seed to the target
        if edge["target_unique_id"] in {"model.demo.child_a", "model.demo.child_b"}:
            return 1
        if edge["target_unique_id"] in {"model.demo.grand_a", "model.demo.grand_b"}:
            return 2
        return 99

    hops = [_hop(e) for e in streamed]
    # Find the first hop=2 emission, assert everything before it is hop=1.
    first_hop2 = next((i for i, h in enumerate(hops) if h == 2), None)
    assert first_hop2 is not None, f"no hop-2 edge emitted; got hops={hops}"
    assert all(h == 1 for h in hops[:first_hop2]), (
        f"hop-2 edge emitted before hop-1 was drained — DFS leaked: hops={hops}"
    )


def test_walk_down_prioritizes_children_whose_sql_mentions_col(tmp_path: Path) -> None:
    """Priority signal: among children of a single (uid, col), the ones
    whose compiled SQL textually mentions [col] are processed before the
    ones whose SQL doesn't (e.g. `SELECT *` passthrough). Both groups
    still emit edges — this is a *priority* hint, never a filter — but
    the high-confidence matches surface in earlier debouncer windows.
    """
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    # Both children consume seed.amount, but:
    #   explicit_child names 'amount' directly in its SQL → high priority
    #   star_child uses SELECT *, no mention of 'amount' → low priority
    # The dict order below puts star_child FIRST so a naive iteration
    # would emit star_child's edge before explicit_child's. The priority
    # sort must flip them.
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.seed": {
                "unique_id": "model.demo.seed", "name": "seed",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id, amount FROM raw.orders",
                "depends_on": {"nodes": []},
            },
            "model.demo.star_child": {
                "unique_id": "model.demo.star_child", "name": "star_child",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": (
                    "WITH src AS (SELECT * FROM analytics.seed) "
                    "SELECT * FROM src"
                ),
                "depends_on": {"nodes": ["model.demo.seed"]},
            },
            "model.demo.explicit_child": {
                "unique_id": "model.demo.explicit_child", "name": "explicit_child",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT amount FROM analytics.seed",
                "depends_on": {"nodes": ["model.demo.seed"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )

    streamed = _edge_streamed_order(project, "seed", "amount")

    # Sanity: both children get edges (priority is reorder, not filter).
    targets = {e["target_unique_id"] for e in streamed}
    assert "model.demo.explicit_child" in targets, (
        f"explicit_child edge missing; got: {streamed}"
    )
    assert "model.demo.star_child" in targets, (
        f"star_child edge missing (priority became a filter); got: {streamed}"
    )

    # The explicit-mention child's edge must come BEFORE the star child's.
    explicit_idx = next(
        i for i, e in enumerate(streamed)
        if e["target_unique_id"] == "model.demo.explicit_child"
    )
    star_idx = next(
        i for i, e in enumerate(streamed)
        if e["target_unique_id"] == "model.demo.star_child"
    )
    assert explicit_idx < star_idx, (
        f"priority sort did not reorder: explicit_idx={explicit_idx}, "
        f"star_idx={star_idx}, streamed={streamed}"
    )


def test_bfs_does_not_drop_edges_through_select_star(tmp_path: Path) -> None:
    """Regression guard for the 0.4.5 fix: priority reordering must not
    silently drop the `SELECT * FROM undocumented_upstream` case. The
    low-priority bucket still has to be processed.
    """
    import json
    project = tmp_path / "demo"
    (project / "target").mkdir(parents=True)
    manifest_dict = {
        "metadata": {"adapter_type": "postgres"},
        "nodes": {
            "model.demo.upstream": {
                "unique_id": "model.demo.upstream", "name": "upstream",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": "SELECT id, amount FROM raw.orders",
                "depends_on": {"nodes": []},
                # No `columns` block — undocumented.
            },
            "model.demo.passthrough": {
                "unique_id": "model.demo.passthrough", "name": "passthrough",
                "package_name": "demo", "resource_type": "model",
                "compiled_code": (
                    "WITH src AS (SELECT * FROM analytics.upstream) "
                    "SELECT * FROM src"
                ),
                "depends_on": {"nodes": ["model.demo.upstream"]},
            },
        },
    }
    (project / "target" / "manifest.json").write_text(
        json.dumps(manifest_dict), encoding="utf-8",
    )
    edges = _edges(project, "upstream", "amount")
    assert any(
        e["source_unique_id"] == "model.demo.upstream"
        and e["source_column"] == "amount"
        and e["target_unique_id"] == "model.demo.passthrough"
        and e["target_column"] == "amount"
        for e in edges
    ), f"SELECT * edge dropped — priority became filter: {edges}"
