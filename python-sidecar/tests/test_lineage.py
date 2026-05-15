"""Tests for column-level lineage extraction.

Each test feeds a SQL string to ``extract_column_lineage`` and asserts the
shape of the resulting tree. We do NOT assert exact rendered SQL because
sqlglot canonicalizes whitespace and identifier quoting per dialect — that's
brittle. We assert structural properties instead.
"""

from __future__ import annotations

import sqlglot
from sqlglot.optimizer.qualify import qualify
from sqlglot.optimizer.scope import build_scope

from dbtree_lineage.lineage import (
    LineageNode,
    collect_source_columns,
    extract_all_column_lineage,
    extract_column_lineage,
)


def _leaf_columns(node: LineageNode) -> list[tuple[str, str]]:
    return collect_source_columns(node)


def test_simple_passthrough() -> None:
    sql = "SELECT id FROM orders"
    node = extract_column_lineage("id", sql, dialect="postgres")

    assert node.name == "id"
    assert _leaf_columns(node) == [("orders AS orders", "orders.id")]


def test_alias_no_expression() -> None:
    sql = "SELECT name AS customer_name FROM customers"
    node = extract_column_lineage("customer_name", sql, dialect="postgres")

    assert node.name == "customer_name"
    leaves = _leaf_columns(node)
    assert len(leaves) == 1
    table, col = leaves[0]
    assert "customers" in table
    assert col.endswith("name")


def test_expression_arithmetic() -> None:
    sql = "SELECT amount * 1.05 AS amount_with_tax FROM orders"
    node = extract_column_lineage("amount_with_tax", sql, dialect="postgres")

    assert node.name == "amount_with_tax"
    assert node.expression is not None
    assert "1.05" in node.expression
    leaves = _leaf_columns(node)
    assert any(col.endswith("amount") for _, col in leaves)


def test_join_traces_correct_table() -> None:
    sql = """
    SELECT
      o.id,
      c.name AS customer_name,
      o.amount * 1.05 AS amount_with_tax
    FROM orders o
    JOIN customers c ON o.customer_id = c.id
    """
    node = extract_column_lineage("customer_name", sql, dialect="postgres")
    leaves = _leaf_columns(node)

    # customer_name should come from customers, not orders
    assert len(leaves) == 1
    table, col = leaves[0]
    assert "customers" in table
    assert "orders" not in table
    assert col.endswith("name")


def test_cte_chain_resolves_through() -> None:
    sql = """
    WITH order_totals AS (
      SELECT customer_id, SUM(amount) AS total
      FROM orders
      GROUP BY customer_id
    ),
    enriched AS (
      SELECT c.id, c.name, ot.total AS lifetime_value
      FROM customers c
      LEFT JOIN order_totals ot ON ot.customer_id = c.id
    )
    SELECT id, name, lifetime_value FROM enriched
    """
    node = extract_column_lineage("lifetime_value", sql, dialect="postgres")
    leaves = _leaf_columns(node)

    # Through both CTEs the leaf must resolve to orders.amount
    assert len(leaves) == 1
    table, col = leaves[0]
    assert "orders" in table
    assert col.endswith("amount")


def test_union_combines_sources() -> None:
    sql = """
    SELECT id FROM orders_2024
    UNION ALL
    SELECT id FROM orders_2025
    """
    node = extract_column_lineage("id", sql, dialect="postgres")
    leaves = _leaf_columns(node)
    tables = sorted(t for t, _ in leaves)

    assert any("orders_2024" in t for t in tables)
    assert any("orders_2025" in t for t in tables)


def test_window_function_traces_argument() -> None:
    sql = """
    SELECT
      id,
      ROW_NUMBER() OVER (PARTITION BY customer_id ORDER BY created_at) AS rn
    FROM orders
    """
    node = extract_column_lineage("rn", sql, dialect="postgres")
    leaves = _leaf_columns(node)
    cols = {col.split(".")[-1] for _, col in leaves}

    assert "customer_id" in cols
    assert "created_at" in cols


def test_serializable() -> None:
    """``to_dict`` must be JSON-serializable end-to-end."""
    import json

    sql = "SELECT a + b AS sum FROM t"
    node = extract_column_lineage("sum", sql, dialect="postgres")
    payload = json.dumps(node.to_dict())

    assert "downstream" in payload
    parsed = json.loads(payload)
    assert parsed["name"] == "sum"
    assert parsed["source_type"] == "select"


# ---------------------------------------------------------------------------
# New behaviour 1: extract_column_lineage with pre-built scope
# ---------------------------------------------------------------------------

_SCOPE_SQL = """\
WITH base AS (
  SELECT id, amount, customer_id
  FROM raw.orders
)
SELECT id, amount * 1.05 AS amount_with_tax, customer_id
FROM base
"""


def _build_scope(sql: str, dialect: str | None = None):
    """Helper: parse + qualify + build_scope for a SQL string."""
    parsed = sqlglot.parse_one(sql, dialect=dialect)
    qualified = qualify(
        parsed,
        dialect=dialect,
        validate_qualify_columns=False,
        identify=False,
    )
    return qualified, build_scope(qualified)


def test_extract_column_lineage_scope_param_produces_same_result() -> None:
    """Passing a pre-built scope yields the same LineageNode tree as the
    unscoped (raw-string) path."""
    sql = _SCOPE_SQL
    qualified, scope = _build_scope(sql, dialect="postgres")

    for col in ("id", "amount_with_tax", "customer_id"):
        node_raw = extract_column_lineage(col, sql, dialect="postgres")
        node_scoped = extract_column_lineage(
            col, qualified, dialect="postgres", scope=scope
        )
        assert node_raw.to_dict() == node_scoped.to_dict(), (
            f"scope vs no-scope mismatch for column {col!r}"
        )


def test_extract_column_lineage_shared_scope_is_safe_across_calls() -> None:
    """Re-using the same scope object across multiple column calls must not
    mutate the scope — the result for column A after calling for B must equal
    the result for A from the first call."""
    sql = _SCOPE_SQL
    qualified, scope = _build_scope(sql, dialect="postgres")

    first_id = extract_column_lineage("id", qualified, dialect="postgres", scope=scope)
    # interleave with a different column
    _customer = extract_column_lineage(
        "customer_id", qualified, dialect="postgres", scope=scope
    )
    second_id = extract_column_lineage("id", qualified, dialect="postgres", scope=scope)

    assert first_id.to_dict() == second_id.to_dict(), (
        "scope was mutated between calls — third call result differs from first"
    )


# ---------------------------------------------------------------------------
# New behaviour 2: extract_all_column_lineage
# ---------------------------------------------------------------------------

_MULTI_COL_SQL = """\
SELECT
  order_id,
  customer_id,
  amount * 1.1 AS amount_with_markup,
  created_at
FROM raw.orders
"""


def test_extract_all_column_lineage_returns_one_entry_per_output_column() -> None:
    """Bulk lineage dict must have exactly the top-level output column names."""
    expected_cols = {"order_id", "customer_id", "amount_with_markup", "created_at"}

    result = extract_all_column_lineage(_MULTI_COL_SQL, dialect="postgres")

    assert set(result.keys()) == expected_cols
    assert len(result) == len(expected_cols), "no duplicate keys expected"


def test_extract_all_column_lineage_entries_match_per_column_results() -> None:
    """Each bulk[col] tree must be structurally equivalent to a dedicated
    extract_column_lineage(col, …) call on the same SQL."""
    sql = _MULTI_COL_SQL

    bulk = extract_all_column_lineage(sql, dialect="postgres")
    for col, bulk_node in bulk.items():
        per_col_node = extract_column_lineage(col, sql, dialect="postgres")
        assert bulk_node.to_dict() == per_col_node.to_dict(), (
            f"bulk vs per-column mismatch for column {col!r}"
        )


def test_extract_all_column_lineage_with_scope_matches_without_scope() -> None:
    """Passing a pre-built scope to extract_all_column_lineage produces the
    same result as the unscoped path."""
    sql = _MULTI_COL_SQL
    qualified, scope = _build_scope(sql, dialect="postgres")

    bulk_raw = extract_all_column_lineage(sql, dialect="postgres")
    bulk_scoped = extract_all_column_lineage(
        qualified, dialect="postgres", scope=scope
    )

    assert set(bulk_raw.keys()) == set(bulk_scoped.keys())
    for col in bulk_raw:
        assert bulk_raw[col].to_dict() == bulk_scoped[col].to_dict(), (
            f"scope vs no-scope mismatch for column {col!r}"
        )
