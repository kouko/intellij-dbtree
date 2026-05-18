"""Read dbt ``target/manifest.json`` and resolve model → compiled SQL.

This module knows nothing about lineage; it only loads dbt artifacts and
exposes a clean API for lookups.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .lineage import list_output_columns

# dbt adapter_type -> sqlglot dialect.
# dbt-core supports many adapters; we map the common ones and fall back to
# the adapter_type string itself, since sqlglot's dialect names mostly match.
_ADAPTER_TO_DIALECT = {
    "redshift": "redshift",
    "postgres": "postgres",
    "snowflake": "snowflake",
    "bigquery": "bigquery",
    "duckdb": "duckdb",
    "spark": "spark",
    "databricks": "databricks",
    "trino": "trino",
    "athena": "athena",
    "clickhouse": "clickhouse",
}


class ManifestError(Exception):
    """Raised when manifest.json is missing or malformed."""


@dataclass
class ModelRef:
    unique_id: str
    name: str
    package_name: str
    compiled_sql: str
    depends_on: list[str]


class DbtManifest:
    def __init__(
        self,
        manifest: dict[str, Any],
        project_dir: Path | None = None,
        catalog: dict[str, Any] | None = None,
    ) -> None:
        self._raw = manifest
        self._project_dir = project_dir
        self._nodes: dict[str, Any] = manifest.get("nodes", {})
        self._sources: dict[str, Any] = manifest.get("sources", {})
        self._catalog: dict[str, Any] = catalog or {}

    @classmethod
    def load(cls, manifest_path: Path) -> "DbtManifest":
        if not manifest_path.is_file():
            raise ManifestError(f"manifest.json not found: {manifest_path}")
        try:
            data = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise ManifestError(f"manifest.json is not valid JSON: {e}") from e
        project_dir = manifest_path.parent.parent
        catalog_path = project_dir / "target" / "catalog.json"
        catalog: dict[str, Any] | None = None
        if catalog_path.is_file():
            try:
                catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                catalog = None
        return cls(data, project_dir=project_dir, catalog=catalog)

    @classmethod
    def from_project(cls, project_dir: Path) -> "DbtManifest":
        return cls.load(project_dir / "target" / "manifest.json")

    @property
    def adapter_type(self) -> str | None:
        return self._raw.get("metadata", {}).get("adapter_type")

    @property
    def dialect(self) -> str | None:
        adapter = self.adapter_type
        if adapter is None:
            return None
        return _ADAPTER_TO_DIALECT.get(adapter, adapter)

    def resolve_unique_id(self, name_or_id: str) -> str:
        """Resolve a model name or unique_id to its unique_id without
        loading compiled SQL.

        Useful for callers (e.g., the in-process full-walker) that look
        up models lazily — the seed model's SQL might be missing while
        its children's SQL is available, and we still want the walk to
        produce partial edges rather than failing wholesale.

        Raises ``ManifestError`` on miss or ambiguous match.
        """
        if name_or_id in self._nodes:
            return name_or_id
        candidates: list[str] = []
        for uid, node in self._nodes.items():
            if node.get("resource_type") != "model":
                continue
            if uid == name_or_id:
                return uid
            if node.get("name") == name_or_id:
                candidates.append(uid)
            elif f"{node.get('package_name')}.{node.get('name')}" == name_or_id:
                candidates.append(uid)
        if not candidates:
            raise ManifestError(f"No model named {name_or_id!r} found in manifest")
        if len(candidates) > 1:
            raise ManifestError(
                f"Ambiguous model name {name_or_id!r}; "
                f"matches: {', '.join(candidates)}. Use the full unique_id."
            )
        return candidates[0]

    def node_metadata(self, unique_id: str) -> dict[str, str]:
        """Return the {name, package_name} for [unique_id] without loading SQL.

        Returns empty strings for missing/malformed nodes rather than raising,
        so callers can fall back gracefully when the manifest is partial.
        """
        node = self._nodes.get(unique_id) or self._sources.get(unique_id) or {}
        return {
            "name": node.get("name", ""),
            "package_name": node.get("package_name", ""),
        }

    def resolve_model(self, name_or_id: str) -> ModelRef:
        """Resolve a model by name or unique_id.

        Accepts ``orders``, ``model.my_project.orders``, or ``my_project.orders``.
        Raises ``ManifestError`` on miss or ambiguous match.
        """
        # 1. Exact unique_id match
        if name_or_id in self._nodes:
            return self._build_ref(name_or_id)

        # 2. Try as full model unique_id
        candidates: list[str] = []
        for uid, node in self._nodes.items():
            if node.get("resource_type") != "model":
                continue
            if uid == name_or_id:
                return self._build_ref(uid)
            if node.get("name") == name_or_id:
                candidates.append(uid)
            elif f"{node.get('package_name')}.{node.get('name')}" == name_or_id:
                candidates.append(uid)

        if not candidates:
            raise ManifestError(f"No model named {name_or_id!r} found in manifest")
        if len(candidates) > 1:
            raise ManifestError(
                f"Ambiguous model name {name_or_id!r}; "
                f"matches: {', '.join(candidates)}. Use the full unique_id."
            )
        return self._build_ref(candidates[0])

    def _build_ref(self, unique_id: str) -> ModelRef:
        node = self._nodes[unique_id]
        compiled_sql = self._read_compiled_sql(node)
        return ModelRef(
            unique_id=unique_id,
            name=node.get("name", ""),
            package_name=node.get("package_name", ""),
            compiled_sql=compiled_sql,
            depends_on=list(node.get("depends_on", {}).get("nodes", [])),
        )

    def _read_compiled_sql(self, node: dict[str, Any]) -> str:
        # dbt 1.7+ embeds compiled_code in the manifest when the model has been compiled.
        compiled_code = node.get("compiled_code")
        if isinstance(compiled_code, str) and compiled_code.strip():
            return compiled_code

        compiled_path = node.get("compiled_path")
        if compiled_path:
            path = Path(compiled_path)
            if not path.is_absolute() and self._project_dir is not None:
                path = self._project_dir / path
            if path.is_file():
                return path.read_text(encoding="utf-8")

        # Fallback: subsequent `dbt parse` / `dbt deps` (or IDE-driven
        # equivalents) regenerate manifest.json without compiled_code /
        # compiled_path, but they don't delete the already-compiled SQL
        # files under `target/compiled/<package>/<original_file_path>`.
        # Derive that standard path from the manifest's metadata so a
        # one-time `dbt compile` keeps benefiting the plugin even after
        # the manifest gets rewritten.
        derived = self._derived_compiled_path(node)
        if derived is not None and derived.is_file():
            return derived.read_text(encoding="utf-8")

        raise ManifestError(
            f"Model {node.get('unique_id')!r} has no compiled SQL. "
            "Run `dbt compile` first."
        )

    def _safe_read_compiled_sql(self, node: dict[str, Any]) -> str | None:
        """Like [_read_compiled_sql] but swallows missing-SQL errors.

        Used by schema construction where any node lacking compiled SQL
        is silently skipped (we just don't contribute its columns) rather
        than aborting the whole schema build.
        """
        try:
            sql = self._read_compiled_sql(node)
        except Exception:
            return None
        return sql if sql and sql.strip() else None

    def _derived_compiled_path(self, node: dict[str, Any]) -> Path | None:
        package = node.get("package_name")
        original = node.get("original_file_path")
        if not (package and original and self._project_dir is not None):
            return None
        return self._project_dir / "target" / "compiled" / package / original

    def list_model_columns(self, unique_id: str) -> list[str]:
        """Return every known column name for a model.

        Prefers ``catalog.json`` (real warehouse columns) over manifest
        (yml-documented). Empty if neither has anything.
        """
        catalog_node = (
            self._catalog.get("nodes", {}).get(unique_id, {}) if self._catalog else {}
        )
        catalog_cols = list(catalog_node.get("columns", {}).keys())
        if catalog_cols:
            return catalog_cols
        node = self._nodes.get(unique_id, {})
        return list((node.get("columns") or {}).keys())

    def build_sqlglot_schema(self) -> dict[str, dict[str, str]]:
        """Build a flat ``{table: {col: type}}`` dict suitable for sqlglot's
        ``schema=`` parameter — intentionally schema-agnostic.

        Why flat: dbt compiles SQL against the user's active TARGET
        schema (e.g. ``dbt_dev_<user>`` for dev runs). The same model's
        ``relation_name`` in ``manifest.json`` reflects whatever target
        was last written there — often a DIFFERENT schema (e.g.
        ``dbt_prod`` from a docs-generate). A fully-qualified nested
        schema (``{db: {schema: {table: cols}}}``) is brittle to this
        mismatch: sqlglot can't expand ``SELECT *`` from a table whose
        qualified path doesn't match the dict's nesting.

        By indexing tables by **bare name only**, the lookup succeeds
        regardless of which target the SQL was rendered against. Trade-
        off: a source and model with the same bare name would collide
        — acceptable in practice because model names are unique within
        a dbt project and source/model name collisions are unusual.

        Column types come from ``catalog.json`` when available, falling
        back to ``schema.yml``-documented types from the manifest.
        """
        catalog_nodes = self._catalog.get("nodes", {}) if self._catalog else {}
        catalog_sources = self._catalog.get("sources", {}) if self._catalog else {}

        schema: dict[str, dict[str, str]] = {}

        def add(uid: str, node: dict[str, Any], catalog_entry: dict[str, Any] | None) -> None:
            name = node.get("name")
            if not name:
                return

            cols: dict[str, str] = {}
            cat_cols = (catalog_entry or {}).get("columns", {})
            for col, spec in cat_cols.items():
                t = spec.get("type") if isinstance(spec, dict) else None
                # Keep the column even if the type is missing — sqlglot only
                # needs the *name* for star expansion and qualifier lookups.
                # validate_qualify_columns=False on the walker side means
                # "UNKNOWN" types never trigger errors.
                cols[col] = t or "UNKNOWN"
            for col, spec in (node.get("columns") or {}).items():
                if col in cols:
                    continue
                t = spec.get("data_type") if isinstance(spec, dict) else None
                cols[col] = t or "UNKNOWN"

            # Fallback for models with neither catalog.json nor schema.yml
            # column docs (common during in-development branches): parse the
            # compiled SQL and use its output column names. Without this,
            # `SELECT *` from an undocumented upstream model can't be
            # expanded by sqlglot, and column-lineage tracing through it
            # stops at an unresolved `*` leaf — so no edges get emitted
            # for any downstream column that ultimately flows through that
            # star.
            if not cols and node.get("resource_type") == "model":
                compiled = self._safe_read_compiled_sql(node)
                if compiled:
                    try:
                        names = list_output_columns(compiled, dialect=self.dialect)
                    except Exception:
                        names = []
                    if names and names != ["*"]:
                        cols = {n: "UNKNOWN" for n in names}

            if not cols:
                return

            # Merge on table-name collision: later writes (sources) augment
            # earlier (models) without overwriting existing columns.
            existing = schema.get(name)
            if existing:
                merged = {**cols, **existing}  # existing wins on overlap
                schema[name] = merged
            else:
                schema[name] = cols

        for uid, node in self._nodes.items():
            if node.get("resource_type") != "model":
                continue
            add(uid, node, catalog_nodes.get(uid))
        for uid, node in self._sources.items():
            add(uid, node, catalog_sources.get(uid))

        return schema

    # ---- Walker helpers ---------------------------------------------------
    # These power the in-Python full-lineage walker. Cached on first call;
    # cheap re-use across calls within a single sidecar invocation.

    def models_by_name(self) -> dict[str, str]:
        """Map model name → unique_id. Multiple matches collapse to one (last wins);
        callers that need disambiguation should use [resolve_model] instead."""
        if not hasattr(self, "_models_by_name_cache"):
            self._models_by_name_cache = {
                node["name"]: uid
                for uid, node in self._nodes.items()
                if node.get("resource_type") == "model" and node.get("name")
            }
        return self._models_by_name_cache

    def sources_by_name(self) -> dict[str, str]:
        """Map source name → unique_id."""
        if not hasattr(self, "_sources_by_name_cache"):
            self._sources_by_name_cache = {
                node["name"]: uid
                for uid, node in self._sources.items()
                if node.get("name")
            }
        return self._sources_by_name_cache

    def children_by_parent(self) -> dict[str, list[str]]:
        """Map parent unique_id → list of child model unique_ids that depend on it."""
        if not hasattr(self, "_children_by_parent_cache"):
            cache: dict[str, list[str]] = {}
            for child_uid, node in self._nodes.items():
                if node.get("resource_type") != "model":
                    continue
                for parent_uid in node.get("depends_on", {}).get("nodes", []):
                    cache.setdefault(parent_uid, []).append(child_uid)
            self._children_by_parent_cache = cache
        return self._children_by_parent_cache

    def model_name(self, unique_id: str) -> str | None:
        """Look up the bare model/source name for [unique_id]. Returns None if not found."""
        node = self._nodes.get(unique_id) or self._sources.get(unique_id)
        if node is None:
            return None
        return node.get("name")

    def compiled_model_stats(self) -> tuple[int, int]:
        """Return ``(compiled_count, total_count)`` for models in the manifest.

        A model counts as compiled when EITHER:
          - it has a non-empty ``compiled_code`` field in the manifest, OR
          - it has a non-null ``compiled_path`` field, OR
          - the derived `target/compiled/<package>/<original_file_path>`
            file exists on disk (handles the case where `dbt parse` wiped
            compiled_code/compiled_path but left the SQL files behind).
        Used by the walker to distinguish "no compile ever ran" from
        "compile ran but partially failed".
        """
        compiled = 0
        total = 0
        for node in self._nodes.values():
            if node.get("resource_type") != "model":
                continue
            total += 1
            code = node.get("compiled_code")
            if isinstance(code, str) and code.strip():
                compiled += 1
                continue
            if node.get("compiled_path"):
                compiled += 1
                continue
            derived = self._derived_compiled_path(node)
            if derived is not None and derived.is_file():
                compiled += 1
        return compiled, total
