# intellij-dbtree

dbt lineage plugin for JetBrains IDEs (DataSpell, IntelliJ IDEA, PyCharm) with **column-level lineage** powered by [sqlglot](https://github.com/tobymao/sqlglot).

Successor to the archived [`ramonvermeulen/dbt-toolkit`](https://github.com/ramonvermeulen/dbt-toolkit), extended with column-level lineage that the original lacks.

## Features

- Interactive model-level DAG (React Flow + dagre) embedded in JCEF
- Column-to-column lineage tracing across CTEs, JOINs, UNIONs, window functions
- Reads dbt `target/manifest.json` + `target/catalog.json` (real warehouse types when available)
- IDE theme integration (light / dark)
- Drag-to-rearrange nodes; click model to open file; configurable hop depth
- Compatible with all IntelliJ-based IDEs on build 261+ (currently tested on DataSpell 2026.1.1)

## Install

Plugin is distributed as a `.zip` on this repo's [Releases](https://github.com/kouko/intellij-dbtree/releases) page. Install it manually:

1. Download `dbtree-<version>.zip` from the latest release.
2. In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the `.zip`.
3. Restart the IDE when prompted.

### Configure the Python interpreter

Column-level lineage requires a Python interpreter with `sqlglot` installed (your dbt project's `.venv` is the natural choice — dbt itself depends on sqlglot or a compatible version).

**Settings → Tools → dbtree → Python interpreter** — point it at e.g. `/path/to/your/dbt-project/.venv/bin/python`. Leave blank to disable column-level lineage (model-level DAG still works).

## Usage

1. Open a dbt project in the IDE (root must contain `dbt_project.yml`).
2. Run `dbt compile` (or any dbt command that produces `target/manifest.json`).
3. Open the **dbtree** tool window (right-side toolbar).
4. Open any `.sql` model file — the lineage panel auto-focuses on it.

## Repo layout

```
python-sidecar/   Python CLI — sqlglot-based column lineage (bundled into plugin)
frontend/        React + xyflow lineage viewer (bundled into plugin via JCEF)
plugin/          Kotlin JetBrains plugin (build target)
```

## Development

Requirements: JDK 21. Node 22 is auto-downloaded by `node-gradle`.

```bash
# Run a sandbox IDE with the plugin loaded against a dbt project
cd plugin
./gradlew runIde -Pide.project=/path/to/your/dbt-project

# Build a distributable .zip
./gradlew buildPlugin
# Output: plugin/build/distributions/dbtree-<version>.zip

# Run JetBrains Plugin Verifier against the target IDE
./gradlew verifyPlugin
```

Python sidecar tests (no plugin needed):

```bash
cd python-sidecar
uv sync && uv run pytest
```

## Release process (maintainers)

CI builds and publishes to GitHub Releases automatically on tag push.

1. Bump `version` in [`plugin/gradle.properties`](plugin/gradle.properties).
2. Update `<change-notes>` in [`plugin/src/main/resources/META-INF/plugin.xml`](plugin/src/main/resources/META-INF/plugin.xml).
3. Commit and push to `main`.
4. Tag and push:
   ```bash
   git tag v0.1.1
   git push --tags
   ```
5. The [`Release`](.github/workflows/release.yml) workflow validates the tag matches `gradle.properties`, runs `buildPlugin` + `verifyPlugin`, then attaches the `.zip` to a new GitHub Release.

PRs and pushes to `main` run the lighter [`Build`](.github/workflows/build.yml) workflow, which produces a `.zip` artifact for inspection without creating a release.

## License

GPL-3.0-or-later (inherited from upstream `dbt-toolkit`). See [LICENSE](LICENSE).
