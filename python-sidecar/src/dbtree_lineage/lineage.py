"""Column-level lineage extraction.

Wraps sqlglot.lineage and emits a clean, JSON-serializable tree.
The tree mirrors sqlglot's Node tree but normalizes:
  - leaf vs intermediate (leaf = Table source)
  - qualified column names (table.column) at leaves
  - SQL expressions stringified back in the requested dialect
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any

import sqlglot
from sqlglot import exp
from sqlglot.lineage import Node as SqlglotNode
from sqlglot.lineage import lineage as sqlglot_lineage
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import Scope


@dataclass
class LineageNode:
    """A node in the column-lineage tree.

    For a non-leaf node, ``source_type == "select"`` and ``table`` is None.
    For a leaf node, ``source_type == "table"`` and ``table`` is the source table name.
    """

    name: str
    source_type: str
    expression: str | None
    table: str | None
    downstream: list["LineageNode"] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def extract_column_lineage(
    column: str,
    sql: str | exp.Expression,
    dialect: str | None = None,
    schema: dict[str, Any] | None = None,
    scope: Scope | None = None,
) -> LineageNode:
    """Compute the column-lineage tree for ``column`` in ``sql``.

    Args:
        column: The output column to trace (e.g. ``"amount_with_tax"``).
        sql: The compiled SQL of the model. Accepts either a raw string
            (sqlglot will parse it) or a pre-parsed/pre-qualified
            ``exp.Expression``.
        dialect: sqlglot dialect (e.g. ``"redshift"``, ``"postgres"``, ``"bigquery"``).
        schema: Optional column schema mapping for ``SELECT *`` resolution,
            in sqlglot's nested-dict form: ``{"db": {"table": {"col": "TYPE"}}}``.
            Ignored when ``scope`` is supplied (the scope already encodes
            the qualified column references).
        scope: Optional pre-built scope from
            ``sqlglot.optimizer.scope.build_scope`` over a pre-qualified
            expression. When provided, sqlglot.lineage skips the per-call
            qualify + scope-build phase (~70% of the per-call cost for
            CTE-heavy dbt SQL) and reuses the shared scope. Safe to share
            across multiple lineage() calls — sqlglot's lineage walker
            doesn't mutate the scope or qualified expression.

    Returns:
        A ``LineageNode`` rooted at the requested column.
    """
    root = sqlglot_lineage(column, sql, schema=schema, dialect=dialect, scope=scope)
    return _convert(root, dialect)


def _convert(node: SqlglotNode, dialect: str | None) -> LineageNode:
    source = node.source
    if isinstance(source, exp.Table):
        source_type = "table"
        table = source.sql(dialect=dialect)
    else:
        source_type = "select"
        table = None

    expression_sql: str | None = None
    if node.expression is not None and node.expression is not source:
        expression_sql = node.expression.sql(dialect=dialect)

    return LineageNode(
        name=node.name,
        source_type=source_type,
        expression=expression_sql,
        table=table,
        downstream=[_convert(child, dialect) for child in node.downstream],
    )


def list_output_columns(
    sql: str,
    dialect: str | None = None,
    schema: dict[str, Any] | None = None,
) -> list[str]:
    """Return the names of columns the top-level SELECT in ``sql`` produces.

    For ``SELECT a, b, c FROM ...`` returns ``["a", "b", "c"]``. For
    ``SELECT *`` we run sqlglot's qualifier with the supplied [schema] to
    expand the star — without a schema for the source, ``*`` cannot be
    resolved and we return ``["*"]`` (the caller should treat that as "no
    columns recoverable").

    Args:
        sql: Compiled SQL of the model.
        dialect: sqlglot dialect (e.g. ``"redshift"``, ``"postgres"``).
        schema: Optional nested-dict schema for ``SELECT *`` expansion.

    Returns:
        Ordered list of output column names. Unique within a single SELECT,
        but the caller is responsible for deduping if it merges multiple
        sources.
    """
    tree = sqlglot.parse_one(sql, dialect=dialect)
    if schema is not None:
        try:
            tree = qualify(tree, schema=schema, dialect=dialect)
        except Exception:
            # qualify can fail on aggressive normalization; fall back to the
            # un-qualified tree rather than crashing the whole call.
            pass
    if not isinstance(tree, exp.Query):
        return []
    # `named_selects` handles Select, Union, and CTE-wrapped queries
    # uniformly; for Union it returns the left branch's column names,
    # which matches SQL's union-output-naming rule.
    return list(tree.named_selects)


def collect_source_columns(node: LineageNode) -> list[tuple[str, str]]:
    """Walk a lineage tree and return the unique ``(table, column)`` leaves.

    Useful for the plugin's column-edge rendering: which upstream columns does
    the requested column ultimately depend on?
    """
    seen: set[tuple[str, str]] = set()
    out: list[tuple[str, str]] = []

    def walk(n: LineageNode) -> None:
        if n.source_type == "table" and n.table is not None:
            key = (n.table, n.name)
            if key not in seen:
                seen.add(key)
                out.append(key)
        for child in n.downstream:
            walk(child)

    walk(node)
    return out
