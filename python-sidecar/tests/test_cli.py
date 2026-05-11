"""End-to-end CLI tests via subprocess."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest


def _run_cli(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "dbtree_lineage.cli", *args],
        check=False,
        capture_output=True,
        text=True,
    )


def test_cli_end_to_end_inline(dbt_project_inline: Path) -> None:
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", "fct_orders",
        "--column", "amount_with_tax",
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"
    payload = json.loads(result.stdout)

    assert payload["model"]["unique_id"] == "model.demo.fct_orders"
    assert payload["column"] == "amount_with_tax"
    assert payload["dialect"] == "postgres"
    assert payload["lineage"]["name"] == "amount_with_tax"

    # The leaf must trace through stg_orders into raw.orders.amount.
    source_cols = payload["source_columns"]
    assert any(
        "orders" in sc["table"] and sc["column"].endswith("amount")
        for sc in source_cols
    ), f"unexpected source_columns: {source_cols}"


def test_cli_end_to_end_compiled_path(dbt_project_file: Path) -> None:
    result = _run_cli(
        "--project-dir", str(dbt_project_file),
        "--model", "fct_orders",
        "--column", "amount_with_tax",
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"
    payload = json.loads(result.stdout)
    assert payload["lineage"]["name"] == "amount_with_tax"


def test_cli_missing_project_returns_2(tmp_path: Path) -> None:
    result = _run_cli(
        "--project-dir", str(tmp_path / "nope"),
        "--model", "x",
        "--column", "y",
    )
    assert result.returncode == 2
    assert "manifest" in result.stderr.lower()


def test_cli_unknown_model_returns_2(dbt_project_inline: Path) -> None:
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", "no_such_model",
        "--column", "id",
    )
    assert result.returncode == 2


def test_cli_pretty_output(dbt_project_inline: Path) -> None:
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", "stg_orders",
        "--column", "id",
        "--pretty",
    )
    assert result.returncode == 0
    # Pretty output should contain newlines and indentation.
    assert "\n  " in result.stdout


@pytest.mark.parametrize("model_ref", ["stg_orders", "model.demo.stg_orders"])
def test_cli_accepts_short_and_unique_id(dbt_project_inline: Path, model_ref: str) -> None:
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", model_ref,
        "--column", "id",
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"


def test_cli_full_walk_stream_emits_ndjson(dbt_project_inline: Path) -> None:
    """--stream emits start/edge*/done lines, one per line, JSON-parseable.
    Plugin consumers read line-by-line to render progressive lineage."""
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", "fct_orders",
        "--column", "id",
        "--full-walk",
        "--stream",
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"

    lines = [ln for ln in result.stdout.splitlines() if ln.strip()]
    assert len(lines) >= 2, f"expected at least start+done, got: {lines!r}"

    # First line: start envelope with model + column metadata
    head = json.loads(lines[0])
    assert "start" in head
    assert head["start"]["column"] == "id"
    assert head["start"]["model"]["unique_id"] == "model.demo.fct_orders"

    # Last line: done envelope (may or may not carry a notice)
    tail = json.loads(lines[-1])
    assert "done" in tail
    assert "notice" in tail["done"]  # key always present, value may be null

    # Everything between: edge envelopes with the wire-format keys the
    # plugin's FullWalkEdge DTO expects.
    edges = [json.loads(ln)["edge"] for ln in lines[1:-1]]
    assert len(edges) >= 1
    for e in edges:
        assert set(e.keys()) >= {
            "source_unique_id", "source_column",
            "target_unique_id", "target_column",
            "expression",
        }


def test_cli_full_walk_non_stream_still_returns_object(dbt_project_inline: Path) -> None:
    """Backwards-compat: --full-walk without --stream returns one JSON
    object (with edges list), same as before the streaming addition."""
    result = _run_cli(
        "--project-dir", str(dbt_project_inline),
        "--model", "fct_orders",
        "--column", "id",
        "--full-walk",
    )
    assert result.returncode == 0, f"stderr: {result.stderr}"
    payload = json.loads(result.stdout)
    assert "edges" in payload
    assert isinstance(payload["edges"], list)
