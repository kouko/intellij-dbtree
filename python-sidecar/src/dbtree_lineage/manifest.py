"""Read dbt ``target/manifest.json`` and resolve model → compiled SQL.

This module knows nothing about lineage; it only loads dbt artifacts and
exposes a clean API for lookups.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

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
    def __init__(self, manifest: dict[str, Any], project_dir: Path | None = None) -> None:
        self._raw = manifest
        self._project_dir = project_dir
        self._nodes: dict[str, Any] = manifest.get("nodes", {})

    @classmethod
    def load(cls, manifest_path: Path) -> "DbtManifest":
        if not manifest_path.is_file():
            raise ManifestError(f"manifest.json not found: {manifest_path}")
        try:
            data = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            raise ManifestError(f"manifest.json is not valid JSON: {e}") from e
        # project_dir is two levels up from target/manifest.json
        project_dir = manifest_path.parent.parent
        return cls(data, project_dir=project_dir)

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

        raise ManifestError(
            f"Model {node.get('unique_id')!r} has no compiled SQL. "
            "Run `dbt compile` first."
        )
