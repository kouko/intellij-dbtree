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
from sqlglot.optimizer.qualify import qualify as sqlglot_qualify
from sqlglot.optimizer.scope import Scope, build_scope

from .lineage import (
    collect_source_columns,
    extract_all_column_lineage,
    extract_column_lineage,
    list_output_columns,
)
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
def _qualified_cached(
    sql: str, dialect: str | None
) -> tuple[exp.Expression, Scope | None]:
    """Per-worker (qualified expression, scope) cache.

    sqlglot.lineage decomposes into ~3 phases per call: parse, qualify
    + build-scope, walk. For dbt's CTE-heavy compiled SQL the first
    two phases dominate (~70% of total call time on real benchmarks).
    The walk phase is the only one that varies per column, so caching
    the qualified expression and scope and passing them to every
    lineage() call collapses the per-column cost to just the walk.

    Cache key is (sql, dialect). The worker-local _W_SCHEMA is read
    inside the function body — schema is immutable per worker process
    (set in _worker_init), so it doesn't need to be in the key.

    Safe to share the cached scope across lineage() calls — sqlglot's
    lineage walker does not mutate scope or the qualified expression.
    Verified by re-running the same column against the same cached
    scope and comparing outputs.

    qualify can raise OptimizeError on SQL the resolver can't handle
    (unresolved columns, dialect-specific syntax). The caller catches
    that and falls back to the slow `sql=str` path so we don't lose
    coverage on models qualify can't handle.
    """
    parsed = sqlglot.parse_one(sql, dialect=dialect)
    # Match sqlglot.lineage's own qualify flags: validate=False so we
    # don't blow up on the unresolved-column edge cases lineage tolerates
    # at runtime, identify=False so we don't pay the quoting normalization
    # cost (cosmetic, irrelevant to lineage output). Without these flags
    # we were falling back to the slow path on most real dbt SQL and
    # losing the qualify-cache win entirely.
    qualified = sqlglot_qualify(
        parsed,
        schema=_W_SCHEMA,
        dialect=dialect,
        validate_qualify_columns=False,
        identify=False,
    )
    scope = build_scope(qualified)
    return qualified, scope


def _lineage_with_cache(column: str, sql: str):
    """Wrap extract_column_lineage with the qualified-scope cache.

    Tries the fast path: parse + qualify + build_scope is cached per
    (sql, dialect), then sqlglot.lineage reuses the shared scope.
    If qualify raises (e.g. unresolvable column, dialect-specific
    syntax it can't normalize), fall back to the slow `sql=str` path
    so the caller still gets a lineage result on hard-to-qualify SQL.
    """
    try:
        qualified, scope = _qualified_cached(sql, _W_DIALECT)
        return extract_column_lineage(
            column=column,
            sql=qualified,
            dialect=_W_DIALECT,
            scope=scope,
        )
    except Exception:
        return extract_column_lineage(
            column=column,
            sql=sql,
            dialect=_W_DIALECT,
            schema=_W_SCHEMA,
        )


def _bulk_lineage_with_cache(sql: str):
    """One-shot lineage for every output column of ``sql``.

    Returns a dict[output_col_name -> LineageNode]. Used by walk_down
    where each task iterates every output column of a child model —
    bulk mode shares sqlglot's internal sub-path cache across columns,
    saving ~1.8x over calling lineage once per column even with a
    pre-built scope.

    Returns None on qualify failure; caller falls back to per-column
    [_lineage_with_cache] which has its own slow-path fallback.
    """
    try:
        qualified, scope = _qualified_cached(sql, _W_DIALECT)
        return extract_all_column_lineage(
            sql=qualified,
            dialect=_W_DIALECT,
            scope=scope,
        )
    except Exception:
        return None


def _worker_init(manifest_path: str, dialect: str | None) -> None:
    global _W_MANIFEST, _W_DIALECT, _W_SCHEMA
    global _W_MODELS_BY_NAME, _W_SOURCES_BY_NAME
    _W_MANIFEST = DbtManifest.load(Path(manifest_path))
    _W_DIALECT = dialect
    schema = _W_MANIFEST.build_sqlglot_schema()
    _W_SCHEMA = schema if schema else None
    _W_MODELS_BY_NAME = _W_MANIFEST.models_by_name()
    _W_SOURCES_BY_NAME = _W_MANIFEST.sources_by_name()
    _qualified_cached.cache_clear()


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
        tree = _lineage_with_cache(col, model.compiled_sql)
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

    # Compute lineage for ALL output columns in one bulk call when
    # qualify succeeds — internally shares sub-path computation across
    # columns. Falls back to per-column path when qualify can't handle
    # the SQL (e.g. dialect-specific syntax sqlglot's resolver chokes
    # on).
    bulk_trees = _bulk_lineage_with_cache(child_model.compiled_sql)

    edges: list[dict] = []
    next_tasks: list[tuple[str, str]] = []
    for child_col in child_columns:
        try:
            if bulk_trees is not None:
                child_tree = bulk_trees.get(child_col)
                if child_tree is None:
                    continue
            else:
                child_tree = _lineage_with_cache(child_col, child_model.compiled_sql)
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
