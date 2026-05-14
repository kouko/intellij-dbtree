"""Parallel version of [walker.walk_full_lineage].

Each `(uid, col)` upstream step and each `(parent, child)` downstream
step is a CPU-bound sqlglot parse — independent across the BFS frontier.
Threading is useless here (GIL), so we use a `ProcessPoolExecutor`:
workers reload manifest once via the pool's `initializer` and then
process tasks from the main process's frontier.

Output order is no longer strictly DFS — `as_completed` returns
whatever finishes first — but the React side has always been
order-independent (it dedups by edgeKey).

Falls back to the serial [walker.walk_full_lineage] when `workers <= 1`.
"""

from __future__ import annotations

import functools
import os
from collections.abc import Callable
from concurrent.futures import ProcessPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from pathlib import Path

import sqlglot
from sqlglot import exp

from .lineage import collect_source_columns, extract_column_lineage, list_output_columns
from .manifest import DbtManifest
from .walker import (
    ColumnEdge,
    _extract_table_name,
    _resolve_table,
    _strip_quotes,
    walk_full_lineage,
)

# ---- Worker-process globals -----------------------------------------------
# Each worker process owns its own copy. The `_worker_init` initializer
# populates these once per process (paid once across the whole walk),
# then every task reads from them without re-loading the manifest.
_W_MANIFEST: DbtManifest | None = None
_W_DIALECT: str | None = None
_W_SCHEMA: dict | None = None
_W_MODELS_BY_NAME: dict[str, str] = {}
_W_SOURCES_BY_NAME: dict[str, str] = {}


@functools.lru_cache(maxsize=512)
def _parse_cached(sql: str, dialect: str | None) -> exp.Expression:
    """Per-worker parsed-AST cache.

    The downstream walker queries lineage for *every* output column of
    each child model, and each query calls sqlglot.parse_one(sql)
    internally — that's a fresh O(50ms) parse per column on the same
    SQL. Caching the parse keyed by (sql, dialect) drops this to one
    parse per (child_uid, dialect) within a single worker process.
    Upstream calls benefit transitively when the same model is reached
    via multiple columns. Cache lives for the worker's lifetime,
    bounded by maxsize to avoid unbounded growth on huge manifests.
    """
    return sqlglot.parse_one(sql, dialect=dialect)


def _worker_init(manifest_path: str, dialect: str | None) -> None:
    global _W_MANIFEST, _W_DIALECT, _W_SCHEMA
    global _W_MODELS_BY_NAME, _W_SOURCES_BY_NAME
    _W_MANIFEST = DbtManifest.load(Path(manifest_path))
    _W_DIALECT = dialect
    schema = _W_MANIFEST.build_sqlglot_schema()
    _W_SCHEMA = schema if schema else None
    _W_MODELS_BY_NAME = _W_MANIFEST.models_by_name()
    _W_SOURCES_BY_NAME = _W_MANIFEST.sources_by_name()
    _parse_cached.cache_clear()


@dataclass
class _StepResult:
    """Common shape for both upstream and downstream task returns.

    Workers report (a) the new edges they discovered and (b) the
    next-frontier tasks the main process should enqueue. Carrying
    `saw_compiled_sql` / `attempted_resolve` flags lets the main
    process reconstruct the same `notice` heuristic the serial walker
    uses.
    """

    edges: list[dict]
    next_tasks: list[tuple[str, str]]
    saw_compiled_sql: bool
    attempted_resolve: bool


def _worker_walk_up_step(uid: str, col: str) -> _StepResult:
    manifest = _W_MANIFEST
    if manifest is None:
        return _StepResult([], [], False, False)

    try:
        model = manifest.resolve_model(uid)
    except Exception:
        return _StepResult([], [], False, True)
    if not model.compiled_sql:
        return _StepResult([], [], False, True)
    try:
        parsed = _parse_cached(model.compiled_sql, _W_DIALECT)
    except Exception:
        return _StepResult([], [], True, True)
    try:
        tree = extract_column_lineage(
            column=col,
            sql=parsed.copy(),
            dialect=_W_DIALECT,
            schema=_W_SCHEMA,
        )
    except Exception:
        return _StepResult([], [], True, True)

    root_expression = (
        tree.expression if tree.expression and tree.expression.strip() else None
    )
    edges: list[dict] = []
    next_tasks: list[tuple[str, str]] = []
    for table_expr, src_col in collect_source_columns(tree):
        src_uid = _resolve_table(table_expr, _W_MODELS_BY_NAME, _W_SOURCES_BY_NAME)
        if src_uid is None:
            continue
        src_col_clean = _strip_quotes(src_col.split(".")[-1])
        edge = ColumnEdge(
            source_unique_id=src_uid,
            source_column=src_col_clean,
            target_unique_id=uid,
            target_column=col,
            expression=root_expression,
        )
        edges.append(asdict(edge))
        if not src_uid.startswith("source."):
            next_tasks.append((src_uid, src_col_clean))
    return _StepResult(edges, next_tasks, True, True)


def _worker_walk_down_child(
    parent_uid: str,
    parent_col: str,
    child_uid: str,
) -> _StepResult:
    manifest = _W_MANIFEST
    if manifest is None:
        return _StepResult([], [], False, False)

    target_name = manifest.model_name(parent_uid)
    if not target_name:
        return _StepResult([], [], False, True)
    try:
        child_model = manifest.resolve_model(child_uid)
    except Exception:
        return _StepResult([], [], False, True)
    if not child_model.compiled_sql:
        return _StepResult([], [], False, True)

    child_columns = manifest.list_model_columns(child_uid)
    if not child_columns:
        try:
            child_columns = list_output_columns(
                sql=child_model.compiled_sql,
                dialect=_W_DIALECT,
                schema=_W_SCHEMA,
            )
        except Exception:
            child_columns = []
        child_columns = [c for c in child_columns if c and c != "*"]
    if not child_columns:
        return _StepResult([], [], True, True)

    # Parse the child's SQL ONCE for the whole column loop. Without
    # this, each extract_column_lineage call re-parses internally —
    # ~50ms × child_column_count, the dominant cost in downstream
    # tasks. `.copy()` per iteration is mandatory: sqlglot.lineage
    # qualifies the expression in-place and would carry mutations
    # across calls otherwise.
    try:
        parsed_child = _parse_cached(child_model.compiled_sql, _W_DIALECT)
    except Exception:
        return _StepResult([], [], True, True)

    edges: list[dict] = []
    next_tasks: list[tuple[str, str]] = []
    for child_col in child_columns:
        try:
            child_tree = extract_column_lineage(
                column=child_col,
                sql=parsed_child.copy(),
                dialect=_W_DIALECT,
                schema=_W_SCHEMA,
            )
        except Exception:
            continue
        cites_seed = False
        for table_expr, src_col in collect_source_columns(child_tree):
            if _extract_table_name(table_expr) != target_name:
                continue
            if _strip_quotes(src_col.split(".")[-1]) != parent_col:
                continue
            cites_seed = True
            break
        if not cites_seed:
            continue
        root_expression = (
            child_tree.expression
            if child_tree.expression and child_tree.expression.strip()
            else None
        )
        edges.append(
            asdict(
                ColumnEdge(
                    source_unique_id=parent_uid,
                    source_column=parent_col,
                    target_unique_id=child_uid,
                    target_column=child_col,
                    expression=root_expression,
                )
            )
        )
        next_tasks.append((child_uid, child_col))
    return _StepResult(edges, next_tasks, True, True)


def walk_full_lineage_parallel(
    manifest: DbtManifest,
    manifest_path: Path,
    seed_uid: str,
    seed_column: str,
    workers: int,
    dialect: str | None = None,
    schema: dict | None = None,
    on_edge: Callable[[dict], None] | None = None,
) -> tuple[list[dict], str | None]:
    """Parallel BFS over both directions. Falls back to serial below 2 workers.

    The pool is opened once and reused across both upstream and
    downstream phases so worker init cost is paid at most once per CLI
    invocation. Edges are flushed to [on_edge] as soon as each task
    completes — `as_completed` order, not BFS order; the receiver
    must be order-independent (the React-side trace BFS is).
    """
    if workers <= 1:
        return walk_full_lineage(
            manifest=manifest,
            seed_uid=seed_uid,
            seed_column=seed_column,
            dialect=dialect,
            schema=schema,
            on_edge=on_edge,
        )

    all_edges: list[dict] = []
    saw_compiled_sql = False
    attempted_resolve = False
    children_by_parent = manifest.children_by_parent()

    def absorb(result: _StepResult) -> list[tuple[str, str]]:
        nonlocal saw_compiled_sql, attempted_resolve
        attempted_resolve = attempted_resolve or result.attempted_resolve
        saw_compiled_sql = saw_compiled_sql or result.saw_compiled_sql
        for edge in result.edges:
            all_edges.append(edge)
            if on_edge is not None:
                on_edge(edge)
        return result.next_tasks

    with ProcessPoolExecutor(
        max_workers=workers,
        initializer=_worker_init,
        initargs=(str(manifest_path), dialect),
    ) as pool:
        # ---- Upstream BFS --------------------------------------------------
        visited_up: set[tuple[str, str]] = {(seed_uid, seed_column)}
        frontier: list[tuple[str, str]] = [(seed_uid, seed_column)]
        while frontier:
            futures = [pool.submit(_worker_walk_up_step, u, c) for u, c in frontier]
            frontier = []
            for fut in as_completed(futures):
                for nu, nc in absorb(fut.result()):
                    key = (nu, nc)
                    if key in visited_up:
                        continue
                    visited_up.add(key)
                    frontier.append(key)

        # ---- Downstream BFS ------------------------------------------------
        # Task granularity is (parent_uid, parent_col, child_uid) — finer
        # than upstream because a single parent's children are independent
        # and each child requires its own sqlglot pass over its compiled
        # SQL. Lets one wide-fanout parent saturate the pool instead of
        # being processed serially inside one worker.
        visited_down: set[tuple[str, str]] = {(seed_uid, seed_column)}
        frontier = [(seed_uid, seed_column)]
        while frontier:
            tasks: list[tuple[str, str, str]] = []
            for parent_uid, parent_col in frontier:
                for child_uid in children_by_parent.get(parent_uid, []):
                    tasks.append((parent_uid, parent_col, child_uid))
            frontier = []
            if not tasks:
                break
            futures = [
                pool.submit(_worker_walk_down_child, p, c, ch) for p, c, ch in tasks
            ]
            for fut in as_completed(futures):
                for nu, nc in absorb(fut.result()):
                    key = (nu, nc)
                    if key in visited_down:
                        continue
                    visited_down.add(key)
                    frontier.append(key)

    # ---- Notice (mirrors walker.walk_full_lineage exactly) ----------------
    notice: str | None = None
    if not all_edges and attempted_resolve and not saw_compiled_sql:
        compiled, total = manifest.compiled_model_stats()
        if total > 0 and compiled == 0:
            notice = (
                "No compiled SQL found in the manifest "
                "(0 of {} models compiled). Run `dbt compile` in the "
                "project root and re-open the model."
            ).format(total)
        elif compiled < total:
            notice = (
                "Only {} of {} models have compiled SQL — this column's "
                "lineage path doesn't reach any compiled model. Run "
                "`dbt compile` in the project root and check the output "
                "for failed models."
            ).format(compiled, total)
        else:
            notice = (
                "No compiled SQL reachable from this column. The lineage "
                "path may go entirely through dbt sources."
            )

    return all_edges, notice


def default_worker_count() -> int:
    """Conservative default — multiprocessing init has fixed cost, and
    sqlglot parses don't scale linearly past a handful of cores."""
    return min(os.cpu_count() or 1, 4)
