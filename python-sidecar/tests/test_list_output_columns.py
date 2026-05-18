"""Tests for `list_output_columns`: extract column names from a model's SQL.

Covers the lazy-card-expand fallback used by the plugin when a dbt project
has neither schema.yml docs nor a `target/catalog.json`.
"""

from __future__ import annotations

import pytest

from dbtree_lineage.lineage import list_output_columns


class TestExplicitProjections:
    def test_simple_select(self) -> None:
        cols = list_output_columns("SELECT id, name, email FROM users")
        assert cols == ["id", "name", "email"]

    def test_select_with_aliases_returns_aliases(self) -> None:
        cols = list_output_columns(
            "SELECT id AS user_id, first_name || ' ' || last_name AS full_name FROM users"
        )
        assert cols == ["user_id", "full_name"]

    def test_qualified_columns_strip_table_prefix(self) -> None:
        # "users.id" → output column is just "id"
        cols = list_output_columns("SELECT users.id, users.email FROM users")
        assert cols == ["id", "email"]

    def test_aggregations_with_aliases(self) -> None:
        cols = list_output_columns(
            "SELECT customer_id, COUNT(*) AS order_count, SUM(amount) AS total FROM orders GROUP BY customer_id"
        )
        assert cols == ["customer_id", "order_count", "total"]


class TestComplexShapes:
    def test_cte_chain_returns_top_level_select(self) -> None:
        # The user's typical dbt model: WITH x AS (...), y AS (...) SELECT ... FROM y
        sql = """
            WITH staging AS (SELECT id, amount FROM raw_orders),
                 enriched AS (SELECT id, amount * 1.1 AS adjusted FROM staging)
            SELECT id, adjusted, amount FROM enriched JOIN staging USING (id)
        """
        cols = list_output_columns(sql)
        assert cols == ["id", "adjusted", "amount"]

    def test_union_returns_first_branch_columns(self) -> None:
        # UNION's output column names come from the first branch in standard SQL
        sql = """
            SELECT id, 'A' AS source FROM t_a
            UNION ALL
            SELECT id, 'B' FROM t_b
        """
        cols = list_output_columns(sql)
        assert cols == ["id", "source"]


class TestStarExpansion:
    def test_select_star_without_schema_returns_star(self) -> None:
        # No schema → cannot expand `*` → return ["*"]. Caller treats this
        # as "no columns recoverable" and stays empty.
        cols = list_output_columns("SELECT * FROM orders")
        assert cols == ["*"]

    def test_select_star_with_schema_expands_to_columns(self) -> None:
        cols = list_output_columns(
            "SELECT * FROM orders",
            schema={"orders": {"id": "INT", "amount": "DECIMAL"}},
        )
        assert cols == ["id", "amount"]

    def test_select_star_qualified_with_dotted_schema(self) -> None:
        cols = list_output_columns(
            "SELECT * FROM main.orders",
            schema={"main": {"orders": {"id": "INT", "amount": "DECIMAL"}}},
        )
        assert cols == ["id", "amount"]

    def test_partial_star_with_schema(self) -> None:
        # `SELECT u.*, o.id` — when schema provides `u`'s columns
        cols = list_output_columns(
            "SELECT u.*, o.id AS order_id FROM users u JOIN orders o ON u.id = o.user_id",
            schema={
                "users": {"id": "INT", "name": "TEXT"},
                "orders": {"id": "INT", "user_id": "INT"},
            },
        )
        # Order can vary but both expanded user columns + order_id should be present
        assert "name" in cols
        assert "order_id" in cols

    def test_select_star_from_final_cte_expands_without_schema(self) -> None:
        # Standard dbt convention: a `final` CTE projects the model's
        # output columns and the top-level statement is `SELECT * FROM final`.
        # No external schema is needed because the star resolves against
        # an in-query CTE — qualify must run unconditionally for this to
        # work (regression for the iCHEF pl_account_metric_mapping bug,
        # where 15 columns silently collapsed to ['*']).
        sql = """
            WITH src AS (SELECT 1 AS id, 'a' AS name),
                 final AS (SELECT id, name, id * 2 AS doubled FROM src)
            SELECT * FROM final
        """
        cols = list_output_columns(sql)
        assert cols == ["id", "name", "doubled"]

    def test_select_star_from_cte_with_declared_column_list(self) -> None:
        # The exact iCHEF pattern: `WITH name (col1, col2, ...) AS (
        # SELECT lit1, lit2, ... UNION ALL ...)` — column names declared
        # at the CTE header, inner SELECT uses bare literals. A downstream
        # `SELECT * FROM name` must still resolve to the declared list.
        sql = """
            WITH mapping (account_number, pl_category, metric_l1) AS (
                          SELECT '410001', 'PL_POS', 'Revenue'
                UNION ALL SELECT '410002', 'PL_POS', 'Revenue'
            )
            SELECT * FROM mapping
        """
        cols = list_output_columns(sql)
        assert cols == ["account_number", "pl_category", "metric_l1"]


class TestDialects:
    def test_redshift_dialect(self) -> None:
        cols = list_output_columns(
            "SELECT id, AVG(amount) AS avg_amt FROM orders GROUP BY id",
            dialect="redshift",
        )
        assert cols == ["id", "avg_amt"]

    def test_bigquery_struct_field_aliased(self) -> None:
        cols = list_output_columns(
            "SELECT user.id AS user_id, user.email FROM events",
            dialect="bigquery",
        )
        assert cols == ["user_id", "email"]


class TestEdgeCases:
    def test_non_query_returns_empty(self) -> None:
        # An UPDATE or DDL statement isn't a Query — no output columns to list.
        cols = list_output_columns("UPDATE orders SET amount = 0 WHERE id = 1")
        assert cols == []

    def test_malformed_sql_raises(self) -> None:
        # sqlglot.parse_one raises on syntactically broken SQL. Caller is
        # expected to catch and surface as an error to the user.
        with pytest.raises(Exception):
            list_output_columns("SELECT FROM WHERE")
