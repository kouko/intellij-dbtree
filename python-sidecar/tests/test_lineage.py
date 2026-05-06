"""Tests for column-level lineage extraction.

Each test feeds a SQL string to ``extract_column_lineage`` and asserts the
shape of the resulting tree. We do NOT assert exact rendered SQL because
sqlglot canonicalizes whitespace and identifier quoting per dialect — that's
brittle. We assert structural properties instead.
"""

from __future__ import annotations

from dbtree_lineage.lineage import (
    LineageNode,
    collect_source_columns,
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
