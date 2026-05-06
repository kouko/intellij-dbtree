"""Coverage for the manifest helpers not exercised by ``test_manifest.py``:

- ``DbtManifest.list_model_columns`` (catalog vs schema.yml precedence)
- ``DbtManifest.build_sqlglot_schema`` (the nested
  ``{db: {schema: {table: {col: type}}}}`` payload sqlglot expects)
- ``DbtManifest.dialect`` fallback when the adapter is not in our
  built-in mapping
- Ambiguous ``resolve_model`` raising ``ManifestError``
- Malformed ``catalog.json`` silently ignored

These tests build minimal manifest dicts directly (no on-disk dbt project
needed) so each test stays one screenful and reads top-down.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from dbtree_lineage.manifest import DbtManifest, ManifestError


# ----- helpers -----------------------------------------------------------

def _model(
    unique_id: str,
    *,
    name: str,
    relation_name: str | None = None,
    columns: dict[str, dict[str, object]] | None = None,
    package: str = "demo",
) -> dict[str, object]:
    """Build a minimal model node entry."""
    node: dict[str, object] = {
        "unique_id": unique_id,
        "name": name,
        "package_name": package,
        "resource_type": "model",
        "compiled_code": "SELECT 1",
        "depends_on": {"nodes": []},
    }
    if relation_name is not None:
        node["relation_name"] = relation_name
    if columns is not None:
        node["columns"] = columns
    return node


def _source(
    unique_id: str,
    *,
    name: str,
    relation_name: str,
    columns: dict[str, dict[str, object]] | None = None,
) -> dict[str, object]:
    src: dict[str, object] = {
        "unique_id": unique_id,
        "name": name,
        "package_name": "demo",
        "resource_type": "source",
        "relation_name": relation_name,
    }
    if columns is not None:
        src["columns"] = columns
    return src


def _catalog_columns(*pairs: tuple[str, str]) -> dict[str, dict[str, str]]:
    """Build the catalog.json columns map from (name, type) tuples."""
    return {name: {"type": ty} for name, ty in pairs}


# ----- list_model_columns ------------------------------------------------

class TestListModelColumns:
    def test_catalog_wins_when_both_present(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    columns={"id": {}, "manifest_only": {}},
                ),
            },
        }
        catalog = {
            "nodes": {
                "model.demo.orders": {"columns": _catalog_columns(("id", "bigint"), ("from_warehouse", "text"))},
            },
        }
        m = DbtManifest(manifest, catalog=catalog)
        # Catalog list wins entirely — manifest-only columns NOT merged in
        # by `list_model_columns` (the `build_sqlglot_schema` call does its
        # own merge for type/description). This contract is what the plugin
        # uses to decide which columns to expand-show.
        assert m.list_model_columns("model.demo.orders") == ["id", "from_warehouse"]

    def test_falls_back_to_manifest_when_catalog_empty(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    columns={"id": {}, "amount": {}},
                ),
            },
        }
        m = DbtManifest(manifest, catalog={})
        assert m.list_model_columns("model.demo.orders") == ["id", "amount"]

    def test_returns_empty_when_no_columns_anywhere(self) -> None:
        manifest = {
            "nodes": {"model.demo.orders": _model("model.demo.orders", name="orders")},
        }
        m = DbtManifest(manifest)
        assert m.list_model_columns("model.demo.orders") == []

    def test_unknown_uid_returns_empty(self) -> None:
        m = DbtManifest({"nodes": {}})
        assert m.list_model_columns("model.does.not.exist") == []


# ----- build_sqlglot_schema ----------------------------------------------

class TestBuildSqlglotSchema:
    def test_three_part_relation_builds_nested_dict(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"warehouse"."public"."orders"',
                    columns={"id": {"data_type": "INTEGER"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        schema = m.build_sqlglot_schema()
        assert schema == {"warehouse": {"public": {"orders": {"id": "INTEGER"}}}}

    def test_two_part_relation_uses_empty_db_wrapper(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"public"."orders"',
                    columns={"id": {"data_type": "INTEGER"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        schema = m.build_sqlglot_schema()
        # 2-part: db part is empty string, schema = "public", table = "orders".
        # The single-tier collapse below applies since only one outer entry.
        # But the inner has "public" as a non-empty schema, so it doesn't
        # fully collapse — we keep the empty-db wrapper.
        assert schema == {"public": {"orders": {"id": "INTEGER"}}}

    def test_one_part_relation_collapses_to_flat_table_map(self) -> None:
        # Single-tier warehouses (e.g. duckdb without a schema) end up with
        # only a table name. The function strips the empty-string outer
        # wrappers so callers get a flat {table: {col: type}} dict.
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"orders"',
                    columns={"id": {"data_type": "INTEGER"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        schema = m.build_sqlglot_schema()
        assert schema == {"orders": {"id": "INTEGER"}}

    def test_catalog_type_wins_over_manifest_data_type(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"db"."schema"."orders"',
                    # manifest types come from schema.yml — often coarse / wrong
                    columns={"id": {"data_type": "INT"}, "amount": {"data_type": "FLOAT"}},
                ),
            },
        }
        catalog = {
            "nodes": {
                "model.demo.orders": {"columns": _catalog_columns(("id", "BIGINT"))},
            },
        }
        m = DbtManifest(manifest, catalog=catalog)
        schema = m.build_sqlglot_schema()
        # id: catalog wins; amount: catalog has no entry, falls back to manifest
        assert schema == {"db": {"schema": {"orders": {"id": "BIGINT", "amount": "FLOAT"}}}}

    def test_skips_models_without_relation_name(self) -> None:
        # An unbuilt model (never compiled / materialized) has no
        # relation_name — skip it rather than crash.
        manifest = {
            "nodes": {
                "model.demo.never_built": _model(
                    "model.demo.never_built",
                    name="never_built",
                    columns={"x": {"data_type": "INT"}},
                ),
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"db"."s"."orders"',
                    columns={"id": {"data_type": "INT"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        schema = m.build_sqlglot_schema()
        assert schema == {"db": {"s": {"orders": {"id": "INT"}}}}

    def test_skips_models_with_empty_columns(self) -> None:
        # If we have neither catalog rows nor manifest data_type, the entry
        # is omitted (sqlglot can't use a typeless schema row anyway).
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"db"."s"."orders"',
                    columns={"id": {}},  # no data_type, no catalog
                ),
            },
        }
        m = DbtManifest(manifest)
        assert m.build_sqlglot_schema() == {}

    def test_includes_sources_with_catalog_types(self) -> None:
        manifest = {
            "nodes": {},
            "sources": {
                "source.demo.app.users": _source(
                    "source.demo.app.users",
                    name="users",
                    relation_name='"db"."raw"."users"',
                    columns={"id": {"data_type": "TEXT"}},
                ),
            },
        }
        catalog = {
            "sources": {
                "source.demo.app.users": {"columns": _catalog_columns(("id", "UUID"))},
            },
        }
        m = DbtManifest(manifest, catalog=catalog)
        schema = m.build_sqlglot_schema()
        assert schema == {"db": {"raw": {"users": {"id": "UUID"}}}}

    def test_models_and_sources_coexist_in_same_db_schema(self) -> None:
        manifest = {
            "nodes": {
                "model.demo.orders": _model(
                    "model.demo.orders",
                    name="orders",
                    relation_name='"db"."public"."orders"',
                    columns={"id": {"data_type": "INT"}},
                ),
            },
            "sources": {
                "source.demo.app.users": _source(
                    "source.demo.app.users",
                    name="users",
                    relation_name='"db"."public"."users"',
                    columns={"id": {"data_type": "TEXT"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        schema = m.build_sqlglot_schema()
        assert schema == {
            "db": {
                "public": {
                    "orders": {"id": "INT"},
                    "users": {"id": "TEXT"},
                },
            },
        }

    def test_strips_only_outer_wrapper_when_inner_schema_is_named(self) -> None:
        # When 2-part identifiers leave db="" but schema is non-empty, we
        # collapse the outer empty-db wrapper but keep the schema layer.
        manifest = {
            "nodes": {
                "model.demo.a": _model(
                    "model.demo.a",
                    name="a",
                    relation_name='"main"."a"',
                    columns={"x": {"data_type": "INT"}},
                ),
                "model.demo.b": _model(
                    "model.demo.b",
                    name="b",
                    relation_name='"main"."b"',
                    columns={"y": {"data_type": "INT"}},
                ),
            },
        }
        m = DbtManifest(manifest)
        # Both have empty db, both share schema=main → collapse outer ""
        # to expose {main: {a: ..., b: ...}}.
        assert m.build_sqlglot_schema() == {
            "main": {"a": {"x": "INT"}, "b": {"y": "INT"}},
        }


# ----- adapter_type → dialect -------------------------------------------

class TestDialectMapping:
    @pytest.mark.parametrize(
        "adapter,expected",
        [
            ("redshift", "redshift"),
            ("postgres", "postgres"),
            ("snowflake", "snowflake"),
            ("bigquery", "bigquery"),
            ("duckdb", "duckdb"),
            ("databricks", "databricks"),
        ],
    )
    def test_known_adapters_map_to_themselves(self, adapter: str, expected: str) -> None:
        m = DbtManifest({"metadata": {"adapter_type": adapter}})
        assert m.dialect == expected

    def test_unknown_adapter_falls_back_to_adapter_string(self) -> None:
        # sqlglot's dialect names mostly match dbt adapter_type, so the
        # fallback is to pass through whatever dbt reports — better than
        # losing the dialect altogether.
        m = DbtManifest({"metadata": {"adapter_type": "some_future_adapter"}})
        assert m.dialect == "some_future_adapter"

    def test_no_adapter_type_returns_none(self) -> None:
        m = DbtManifest({"metadata": {}})
        assert m.dialect is None

    def test_no_metadata_returns_none(self) -> None:
        m = DbtManifest({})
        assert m.dialect is None


# ----- resolve_model edge cases -----------------------------------------

class TestResolveModel:
    def test_ambiguous_name_across_packages_raises(self) -> None:
        # If two packages each have a model named "orders", a bare-name
        # query can't choose between them.
        manifest = {
            "nodes": {
                "model.pkg_a.orders": _model("model.pkg_a.orders", name="orders", package="pkg_a"),
                "model.pkg_b.orders": _model("model.pkg_b.orders", name="orders", package="pkg_b"),
            },
        }
        m = DbtManifest(manifest)
        with pytest.raises(ManifestError) as exc:
            m.resolve_model("orders")
        assert "Ambiguous" in str(exc.value)

    def test_package_qualified_name_disambiguates(self) -> None:
        manifest = {
            "nodes": {
                "model.pkg_a.orders": _model("model.pkg_a.orders", name="orders", package="pkg_a"),
                "model.pkg_b.orders": _model("model.pkg_b.orders", name="orders", package="pkg_b"),
            },
        }
        m = DbtManifest(manifest)
        ref = m.resolve_model("pkg_b.orders")
        assert ref.unique_id == "model.pkg_b.orders"


# ----- catalog.json malformed -----------------------------------------

class TestCatalogParsing:
    def test_malformed_catalog_silently_ignored(self, tmp_path: Path) -> None:
        # Behavior contract: a catalog.json that fails to parse must not
        # take down the manifest load. Plugin still gets columns from
        # schema.yml in that case.
        target = tmp_path / "target"
        target.mkdir()
        (target / "manifest.json").write_text(
            json.dumps({"nodes": {}, "metadata": {"adapter_type": "duckdb"}}),
            encoding="utf-8",
        )
        (target / "catalog.json").write_text("this is not json {{{", encoding="utf-8")

        m = DbtManifest.load(target / "manifest.json")
        # Successfully loaded; build_sqlglot_schema returns empty (no nodes)
        # without crashing on the bad catalog.
        assert m.dialect == "duckdb"
        assert m.build_sqlglot_schema() == {}

    def test_missing_catalog_is_fine(self, tmp_path: Path) -> None:
        target = tmp_path / "target"
        target.mkdir()
        (target / "manifest.json").write_text(
            json.dumps({"nodes": {}, "metadata": {"adapter_type": "duckdb"}}),
            encoding="utf-8",
        )
        # No catalog.json file at all
        m = DbtManifest.load(target / "manifest.json")
        assert m.dialect == "duckdb"
