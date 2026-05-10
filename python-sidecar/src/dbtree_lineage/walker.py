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

from dataclasses import asdict, dataclass

from .lineage import collect_source_columns, extract_column_lineage, list_output_columns
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
) -> list[dict]:
    """Walk both upstream and downstream from (seed_uid, seed_column).

    Returns a flat list of edge dicts with shape:
        {source_unique_id, source_column, target_unique_id, target_column, expression}

    Each (uid, col) is visited at most once per direction, protecting
    against cycles. Source-prefix uids (`source.*`) are terminal: dbt
    sources have no compiled SQL.
    """
    edges: list[ColumnEdge] = []
    models_by_name = manifest.models_by_name()
    sources_by_name = manifest.sources_by_name()
    children_by_parent = manifest.children_by_parent()

    # ---- Upstream ----------------------------------------------------
    visited_up: set[tuple[str, str]] = set()

    def walk_up(uid: str, col: str) -> None:
        if (uid, col) in visited_up:
            return
        visited_up.add((uid, col))
        try:
            model = manifest.resolve_model(uid)
        except Exception:
            return
        if not model.compiled_sql:
            return
        try:
            tree = extract_column_lineage(
                column=col, sql=model.compiled_sql, dialect=dialect, schema=schema,
            )
        except Exception:
            return
        root_expression = tree.expression if tree.expression and tree.expression.strip() else None
        for table_expr, src_col in collect_source_columns(tree):
            src_uid = _resolve_table(table_expr, models_by_name, sources_by_name)
            if src_uid is None:
                continue
            src_col_clean = _strip_quotes(src_col.split(".")[-1])
            edges.append(ColumnEdge(
                source_unique_id=src_uid,
                source_column=src_col_clean,
                target_unique_id=uid,
                target_column=col,
                expression=root_expression,
            ))
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
        for child_uid in children_by_parent.get(uid, []):
            try:
                child_model = manifest.resolve_model(child_uid)
            except Exception:
                continue
            if not child_model.compiled_sql:
                continue
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
            for child_col in child_columns:
                try:
                    child_tree = extract_column_lineage(
                        column=child_col,
                        sql=child_model.compiled_sql,
                        dialect=dialect,
                        schema=schema,
                    )
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
                edges.append(ColumnEdge(
                    source_unique_id=uid,
                    source_column=col,
                    target_unique_id=child_uid,
                    target_column=child_col,
                    expression=root_expression,
                ))
                walk_down(child_uid, child_col)

    walk_up(seed_uid, seed_column)
    walk_down(seed_uid, seed_column)
    return [asdict(e) for e in edges]
