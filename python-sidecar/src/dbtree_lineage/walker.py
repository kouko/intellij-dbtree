"""Full upstream + downstream column-lineage walker.

Runs the entire (uid, col) recursion in-process so the JetBrains plugin
can compute a column's full lineage with ONE sidecar invocation instead
of one subprocess per (uid, col) pair. Saves ~90% of wall time on
column clicks for projects with deep lineage chains (measured on iCHEF
dbt project: 89 sidecar calls × 170 ms each = 15 s collapsed to a
single 2.5 s call).

The walker mirrors the previous Kotlin walker exactly so output edges
are wire-compatible.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import asdict, dataclass

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


@dataclass
class ColumnEdge:
    source_unique_id: str
    source_column: str
    target_unique_id: str
    target_column: str
    expression: str | None


def _strip_quotes(s: str) -> str:
    return s.strip().strip('"').strip("'").strip("`")


def _extract_table_name(table_expr: str) -> str | None:
    """Extract the bare table name from a sqlglot table expression.

    Mirrors the Kotlin extractTableName so output is identical.
    Examples:
        '"jaffle_shop"."main"."stg_orders" AS stg_orders' -> 'stg_orders'
        '"main"."orders"'                                  -> 'orders'
        'orders'                                           -> 'orders'
    """
    without_alias = table_expr.split(" AS ")[0].strip()
    if not without_alias:
        return None
    last = without_alias.split(".")[-1]
    bare = _strip_quotes(last)
    return bare or None


def _resolve_table(
    table_expr: str,
    models_by_name: dict[str, str],
    sources_by_name: dict[str, str],
) -> str | None:
    """Resolve a sqlglot table expression to a manifest unique_id."""
    name = _extract_table_name(table_expr)
    if not name:
        return None
    return models_by_name.get(name) or sources_by_name.get(name)


def walk_full_lineage(
    manifest: DbtManifest,
    seed_uid: str,
    seed_column: str,
    dialect: str | None = None,
    schema: dict | None = None,
    on_edge: Callable[[dict], None] | None = None,
) -> tuple[list[dict], str | None]:
    """Walk both upstream and downstream from (seed_uid, seed_column).

    Returns (edges, notice):
        edges: flat list of edge dicts with shape
            {source_unique_id, source_column, target_unique_id,
             target_column, expression}
        notice: optional soft hint to surface to the user (e.g. "Run
            dbt compile first"). Set only when the walker hit a state
            it's confident is misconfiguration rather than absent
            lineage, so the plugin can banner it.

    Each (uid, col) is visited at most once per direction, protecting
    against cycles. Source-prefix uids (`source.*`) are terminal: dbt
    sources have no compiled SQL.

    If [on_edge] is provided, it is invoked synchronously with each
    edge dict immediately upon discovery — used by streaming callers
    (CLI ``--stream`` mode) to flush partial results to stdout for
    progressive UI updates. The final return value still contains the
    full list regardless of callback presence, so non-streaming
    callers see the same contract.
    """
    edges: list[ColumnEdge] = []
    models_by_name = manifest.models_by_name()
    sources_by_name = manifest.sources_by_name()
    children_by_parent = manifest.children_by_parent()
    # Track whether we ever encountered compiled SQL during the walk.
    # If we visit nodes but none had usable SQL, the manifest most
    # likely needs `dbt compile` rather than being a genuine zero-
    # lineage case.
    saw_compiled_sql = False
    attempted_resolve = False

    # ---- Per-walk caches --------------------------------------------------
    # Two shared caches drive the bulk of the speedup over a naive
    # call-lineage-per-column loop:
    #
    # 1. qualified_cache: same SQL is queried for many columns and from
    #    many call sites during a walk. Caching parse+qualify+scope per
    #    SQL collapses ~70% of the per-call cost to a one-shot.
    # 2. bulk_lineage_cache: sqlglot.lineage(column=None, …) returns
    #    lineage for every output column in one call, with an internal
    #    sub-path cache shared across columns — another ~1.8x over
    #    per-column even with a pre-built scope. Downstream queries
    #    every output column of every child, so this dominates.
    qualified_cache: dict[str, tuple[exp.Expression, Scope] | None] = {}
    bulk_lineage_cache: dict[str, dict | None] = {}

    def get_qualified(sql: str) -> tuple[exp.Expression, Scope] | None:
        if sql in qualified_cache:
            return qualified_cache[sql]
        try:
            parsed = sqlglot.parse_one(sql, dialect=dialect)
            # Match sqlglot.lineage's own qualify flags — validate=False
            # tolerates the dialect-specific edge cases (e.g. Redshift
            # DATEADD(day, …) where `day` is a unit literal but strict
            # qualify reads it as an unresolved column).
            qualified = sqlglot_qualify(
                parsed,
                schema=schema,
                dialect=dialect,
                validate_qualify_columns=False,
                identify=False,
            )
            scope = build_scope(qualified)
        except Exception:
            qualified_cache[sql] = None
            return None
        qualified_cache[sql] = (qualified, scope)
        return qualified, scope

    def get_bulk_lineage(sql: str):
        if sql in bulk_lineage_cache:
            return bulk_lineage_cache[sql]
        qs = get_qualified(sql)
        if qs is None:
            bulk_lineage_cache[sql] = None
            return None
        qualified, scope = qs
        try:
            result = extract_all_column_lineage(
                sql=qualified, dialect=dialect, scope=scope,
            )
        except Exception:
            result = None
        bulk_lineage_cache[sql] = result
        return result

    def single_lineage(column: str, sql: str):
        """Per-column lineage using the qualify cache; falls back to the
        raw `sql=str` path when qualify can't handle the SQL."""
        qs = get_qualified(sql)
        if qs is not None:
            qualified, scope = qs
            try:
                return extract_column_lineage(
                    column=column, sql=qualified, dialect=dialect, scope=scope,
                )
            except Exception:
                pass
        return extract_column_lineage(
            column=column, sql=sql, dialect=dialect, schema=schema,
        )

    # ---- Upstream ----------------------------------------------------
    visited_up: set[tuple[str, str]] = set()

    def walk_up(uid: str, col: str) -> None:
        nonlocal saw_compiled_sql, attempted_resolve
        if (uid, col) in visited_up:
            return
        visited_up.add((uid, col))
        attempted_resolve = True
        try:
            model = manifest.resolve_model(uid)
        except Exception:
            return
        if not model.compiled_sql:
            return
        saw_compiled_sql = True
        try:
            tree = single_lineage(col, model.compiled_sql)
        except Exception:
            return
        root_expression = tree.expression if tree.expression and tree.expression.strip() else None
        for table_expr, src_col in collect_source_columns(tree):
            src_uid = _resolve_table(table_expr, models_by_name, sources_by_name)
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
            edges.append(edge)
            if on_edge is not None:
                on_edge(asdict(edge))
            if src_uid.startswith("source."):
                continue
            walk_up(src_uid, src_col_clean)

    # ---- Downstream ---------------------------------------------------
    # For each child of (uid, col), parse the child's compiled SQL once
    # for ALL its output columns and check which cite the seed. This is
    # the same strategy the previous Kotlin walker used (--all-columns).
    visited_down: set[tuple[str, str]] = set()

    def walk_down(uid: str, col: str) -> None:
        if (uid, col) in visited_down:
            return
        visited_down.add((uid, col))
        target_name = manifest.model_name(uid)
        if not target_name:
            return
        nonlocal saw_compiled_sql, attempted_resolve
        for child_uid in children_by_parent.get(uid, []):
            attempted_resolve = True
            try:
                child_model = manifest.resolve_model(child_uid)
            except Exception:
                continue
            if not child_model.compiled_sql:
                continue
            saw_compiled_sql = True
            # Prefer manifest yml / catalog columns (cheap lookup). Fall
            # back to parsing SQL output columns when neither has any —
            # required for projects without catalog.json or yml docs.
            child_columns = manifest.list_model_columns(child_uid)
            if not child_columns:
                try:
                    child_columns = list_output_columns(
                        sql=child_model.compiled_sql, dialect=dialect, schema=schema,
                    )
                except Exception:
                    child_columns = []
                child_columns = [c for c in child_columns if c and c != "*"]
            if not child_columns:
                continue

            # Try bulk lineage (all output columns in one call with a
            # shared sub-path cache); falls back to per-column on
            # qualify failure. The bulk dict is then keyed by
            # output-column name.
            bulk = get_bulk_lineage(child_model.compiled_sql)

            for child_col in child_columns:
                try:
                    if bulk is not None:
                        child_tree = bulk.get(child_col)
                        if child_tree is None:
                            continue
                    else:
                        child_tree = single_lineage(child_col, child_model.compiled_sql)
                except Exception:
                    continue
                child_sources = collect_source_columns(child_tree)
                # Does this child column cite (uid, col)?
                cites_seed = False
                for table_expr, src_col in child_sources:
                    if _extract_table_name(table_expr) != target_name:
                        continue
                    if _strip_quotes(src_col.split(".")[-1]) != col:
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
                edge = ColumnEdge(
                    source_unique_id=uid,
                    source_column=col,
                    target_unique_id=child_uid,
                    target_column=child_col,
                    expression=root_expression,
                )
                edges.append(edge)
                if on_edge is not None:
                    on_edge(asdict(edge))
                walk_down(child_uid, child_col)

    walk_up(seed_uid, seed_column)
    walk_down(seed_uid, seed_column)

    notice: str | None = None
    # Surface a hint when the walker had work to do but every node it
    # touched lacked compiled SQL. Without this, the plugin renders
    # empty highlights with no signal to the user that the manifest
    # needs `dbt compile` rather than the column genuinely having no
    # lineage. Skipped when edges exist (partial result is its own
    # signal) or when no resolve was attempted (e.g. seed had no
    # children and no SQL — could also be "leaf source", which is a
    # legitimate empty result).
    if not edges and attempted_resolve and not saw_compiled_sql:
        compiled, total = manifest.compiled_model_stats()
        if total > 0 and compiled == 0:
            # No model in the manifest is compiled — most likely the user
            # ran `dbt parse` / `dbt deps` instead of `dbt compile`, or
            # the compile never succeeded.
            notice = (
                "No compiled SQL found in the manifest "
                "(0 of {} models compiled). Run `dbt compile` in the "
                "project root and re-open the model."
            ).format(total)
        elif compiled < total:
            # Partial compile — common when a few models have Jinja /
            # ref / dependency errors that abort or skip them. Surface
            # the count so the user knows it's not a "did I compile?"
            # question, it's a "which models failed compile?" question.
            notice = (
                "Only {} of {} models have compiled SQL — this column's "
                "lineage path doesn't reach any compiled model. Run "
                "`dbt compile` in the project root and check the output "
                "for failed models."
            ).format(compiled, total)
        else:
            # Defensive: every model compiled but the walker still found
            # no SQL on its path. Most likely a bug or a path that goes
            # entirely through sources (which have no SQL by design).
            notice = (
                "No compiled SQL reachable from this column. The lineage "
                "path may go entirely through dbt sources."
            )

    return [asdict(e) for e in edges], notice
