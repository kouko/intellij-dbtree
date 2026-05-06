# intellij-dbtree

dbt lineage plugin for JetBrains IDEs (DataSpell, IntelliJ, PyCharm) with **column-level lineage** powered by [sqlglot](https://github.com/tobymao/sqlglot).

Forks the archived [`ramonvermeulen/dbt-toolkit`](https://github.com/ramonvermeulen/dbt-toolkit) and extends it with column-level lineage that the original lacks.

## Status

In development. See [the research note](../kouko-obsidian-vault/research/2026-05-06%20%E8%87%AA%E8%A3%BD%20dbt%20Lineage%20JetBrains%20Plugin%20%E6%8A%80%E8%A1%93%E7%A0%94%E7%A9%B6.md) for background.

Phases (per research):
- **C — Python CLI MVP** *(in progress)* — `python-sidecar/` reads `target/manifest.json` + `target/compiled/`, emits column-lineage JSON via sqlglot.
- **B — Frontend mockup** — standalone HTML using `@xyflow/react` + `dagre` to render Phase C output.
- **A — Plugin integration** — fork dbt-toolkit, embed Phase C as Python sidecar, embed Phase B in JCEF.

## Layout

```
python-sidecar/   Phase C — sqlglot-based column lineage CLI / RPC server
frontend/         Phase B — React + xyflow viewer (later)
plugin/           Phase A — Kotlin JetBrains plugin (later)
```

## License

GPL-3.0-or-later (inherited from upstream `dbt-toolkit`). See [LICENSE](LICENSE).
