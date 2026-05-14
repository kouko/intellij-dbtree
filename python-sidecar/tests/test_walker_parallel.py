"""Equivalence tests for the parallel walker.

The point of these tests is not to exercise every lineage edge case
(``test_walker.py`` already covers those for the serial walker). It's
to assert that for a given project + seed, the *set* of edges
returned by [walk_full_lineage_parallel] matches the serial
[walk_full_lineage] — i.e. parallelisation didn't drop, duplicate, or
fabricate edges.

We compare sets (or sorted lists) because the parallel walker
deliberately returns edges in completion order, not BFS order.
"""

from __future__ import annotations

from pathlib import Path

from dbtree_lineage.manifest import DbtManifest
from dbtree_lineage.walker import walk_full_lineage
from dbtree_lineage.walker_parallel import walk_full_lineage_parallel


def _edge_key(e: dict) -> tuple[str, str, str, str]:
    return (
        e["source_unique_id"],
        e["source_column"],
        e["target_unique_id"],
        e["target_column"],
    )


def _run_pair(
    project: Path, seed_model: str, seed_col: str, workers: int,
) -> tuple[set[tuple[str, str, str, str]], set[tuple[str, str, str, str]]]:
    manifest = DbtManifest.from_project(project)
    manifest_path = project / "target" / "manifest.json"
    schema = manifest.build_sqlglot_schema() or None
    seed_uid = manifest.resolve_unique_id(seed_model)

    serial_edges, _ = walk_full_lineage(
        manifest=manifest,
        seed_uid=seed_uid,
        seed_column=seed_col,
        dialect=manifest.dialect,
        schema=schema,
    )
    parallel_edges, _ = walk_full_lineage_parallel(
        manifest=manifest,
        manifest_path=manifest_path,
        seed_uid=seed_uid,
        seed_column=seed_col,
        workers=workers,
        dialect=manifest.dialect,
        schema=schema,
    )
    return (
        {_edge_key(e) for e in serial_edges},
        {_edge_key(e) for e in parallel_edges},
    )


def test_parallel_matches_serial_upstream(dbt_project_inline: Path) -> None:
    serial, parallel = _run_pair(dbt_project_inline, "fct_orders", "id", workers=2)
    assert serial == parallel


def test_parallel_matches_serial_downstream(dbt_project_inline: Path) -> None:
    serial, parallel = _run_pair(dbt_project_inline, "stg_orders", "id", workers=2)
    assert serial == parallel


def test_parallel_with_one_worker_uses_serial_path(dbt_project_inline: Path) -> None:
    # workers=1 short-circuits to walk_full_lineage. We only assert the
    # output still matches — coverage that the fallback wiring works.
    serial, fallback = _run_pair(dbt_project_inline, "fct_orders", "id", workers=1)
    assert serial == fallback


def test_parallel_streams_edges_via_callback(dbt_project_inline: Path) -> None:
    manifest = DbtManifest.from_project(dbt_project_inline)
    manifest_path = dbt_project_inline / "target" / "manifest.json"
    schema = manifest.build_sqlglot_schema() or None
    seed_uid = manifest.resolve_unique_id("fct_orders")

    streamed: list[dict] = []
    final, _ = walk_full_lineage_parallel(
        manifest=manifest,
        manifest_path=manifest_path,
        seed_uid=seed_uid,
        seed_column="id",
        workers=2,
        dialect=manifest.dialect,
        schema=schema,
        on_edge=streamed.append,
    )
    # The streaming callback should fire exactly once per returned edge.
    assert {_edge_key(e) for e in streamed} == {_edge_key(e) for e in final}
    assert len(streamed) == len(final)
