package dev.kouko.intellijdbtree.lineage

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Reads dbt's `target/manifest.json` and exposes derived data:
 *
 *  - `lineageFor(uniqueId)`: build a LineagePayload subgraph centered on
 *    a model (BFS in both directions, no hop limit yet).
 *  - `resolveModel(file)`: turn an open SQL file into a `unique_id`.
 *
 * dbt project location is auto-detected from the IntelliJ project root
 * via [DbtProjectDetector] (handles monorepos where the dbt project is
 * a subfolder, like iCHEF).
 *
 * Phase A1 limits:
 *  - No file watcher: must call `refresh()` to re-read manifest after
 *    `dbt parse` / `dbt run` (Phase A2 will add VFS listener).
 *  - column_edges always empty in the payload (Phase A2 spawns Python
 *    sidecar for that).
 */
@Service(Service.Level.PROJECT)
class ManifestService(private val project: Project) {

    private val log = Logger.getInstance(ManifestService::class.java)
    private val state = AtomicReference<ParsedManifest?>(null)

    /**
     * Re-detect the dbt project and reload manifest.json. Safe to call
     * from any thread; performs file I/O.
     */
    fun refresh(): RefreshResult {
        val dbtProjectDir = DbtProjectDetector.findFirst(project)
            ?: run {
                state.set(null)
                return RefreshResult.NoDbtProject
            }
        val manifestPath = dbtProjectDir.resolve("target").resolve("manifest.json")
        if (!Files.isRegularFile(manifestPath)) {
            state.set(null)
            return RefreshResult.NoManifest(manifestPath)
        }
        return try {
            val raw = Files.readString(manifestPath)
            val json = JsonParser.parseString(raw).asJsonObject
            val parsed = ParsedManifest(json, dbtProjectDir)
            state.set(parsed)
            log.info(
                "ManifestService: loaded ${parsed.modelCount()} models, " +
                    "${parsed.sourceCount()} sources from $manifestPath",
            )
            RefreshResult.Ok(parsed)
        } catch (e: Exception) {
            log.warn("ManifestService: failed to parse $manifestPath", e)
            state.set(null)
            RefreshResult.ParseError(manifestPath, e)
        }
    }

    /**
     * Returns the parsed manifest if loaded, else triggers a refresh
     * (synchronous I/O — call from a pooled thread).
     */
    fun ensureLoaded(): ParsedManifest? {
        state.get()?.let { return it }
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        return when (val r = refresh()) {
            is RefreshResult.Ok -> r.manifest
            else -> null
        }
    }

    /**
     * Find the dbt model unique_id for an open SQL file, or null if the
     * file is not part of the loaded dbt project's models.
     */
    fun resolveModel(file: VirtualFile): String? {
        val parsed = state.get() ?: return null
        return parsed.resolveByOriginalPath(file.path)
    }

    /**
     * Build a LineagePayload centered on [uniqueId] (or all models if null).
     *
     * The subgraph contains all transitive ancestors and descendants of
     * the selected node. `column_edges` is always empty in Phase A1.
     */
    fun lineageFor(uniqueId: String?): LineagePayload {
        val parsed = state.get() ?: return LineagePayload()
        return parsed.buildLineage(uniqueId)
    }

    sealed class RefreshResult {
        data object NoDbtProject : RefreshResult()
        data class NoManifest(val path: Path) : RefreshResult()
        data class ParseError(val path: Path, val cause: Throwable) : RefreshResult()
        data class Ok(val manifest: ParsedManifest) : RefreshResult()
    }
}

/**
 * Cached, read-only view over a parsed manifest.json. Thread-safe to
 * read concurrently; never mutated after construction.
 */
class ParsedManifest(
    private val raw: JsonObject,
    val dbtProjectDir: Path,
) {
    private val nodes: JsonObject = raw.getAsJsonObject("nodes") ?: JsonObject()
    private val sources: JsonObject = raw.getAsJsonObject("sources") ?: JsonObject()
    private val childMap: JsonObject = raw.getAsJsonObject("child_map") ?: JsonObject()
    private val parentMap: JsonObject = raw.getAsJsonObject("parent_map") ?: JsonObject()

    /** Map: absolute filesystem path of a model file → model unique_id. */
    private val pathToModelId: Map<String, String> = buildMap {
        for ((uid, n) in nodes.entrySet()) {
            val obj = n.asJsonObject
            if (obj.string("resource_type") != "model") continue
            val originalPath = obj.string("original_file_path") ?: continue
            val abs = dbtProjectDir.resolve(originalPath).toString()
            put(abs, uid)
        }
    }

    fun modelCount(): Int = nodes.entrySet().count {
        it.value.asJsonObject.string("resource_type") == "model"
    }

    fun sourceCount(): Int = sources.size()

    fun resolveByOriginalPath(filePath: String): String? = pathToModelId[filePath]

    /** Inverse of `resolveByOriginalPath`: unique_id -> dbt-relative file path. */
    fun lookupOriginalPath(uniqueId: String): String? {
        val n = nodes.getAsJsonObject(uniqueId) ?: return null
        return n.string("original_file_path")
    }

    /**
     * BFS upstream + downstream from [seed]. If [seed] is null, returns
     * every model + source as one big graph (rare; mostly for debug).
     */
    fun buildLineage(seed: String?): LineagePayload {
        val visited = mutableSetOf<String>()
        val edges = LinkedHashSet<ModelEdge>()

        fun walk(node: String) {
            if (!visited.add(node)) return
            childMap.getAsJsonArray(node)?.forEach { c ->
                val child = c.asString
                edges += ModelEdge(node, child)
                walk(child)
            }
            parentMap.getAsJsonArray(node)?.forEach { p ->
                val parent = p.asString
                edges += ModelEdge(parent, node)
                walk(parent)
            }
        }

        if (seed != null) {
            walk(seed)
        } else {
            // Whole project — iterate over every node.
            for ((uid, _) in nodes.entrySet()) walk(uid)
            for ((uid, _) in sources.entrySet()) walk(uid)
        }

        // Filter edges + visited down to "interesting" nodes only:
        // models + sources. Skip tests, snapshots, seeds for the model graph.
        val interesting = visited.filter { isModelOrSource(it) }.toSet()
        val keptEdges = edges.filter { it.sourceUniqueId in interesting && it.targetUniqueId in interesting }

        val models = interesting.mapNotNull { uid -> describe(uid) }
        val selected = if (seed != null && seed in interesting) {
            Selected(uniqueId = seed, column = null)
        } else null

        return LineagePayload(
            models = models,
            modelEdges = keptEdges,
            columnEdges = emptyList(),
            selected = selected,
        )
    }

    private fun isModelOrSource(uid: String): Boolean {
        if (sources.has(uid)) return true
        val n = nodes.getAsJsonObject(uid) ?: return false
        return n.string("resource_type") == "model"
    }

    private fun describe(uid: String): DbtModel? {
        if (sources.has(uid)) {
            val s = sources.getAsJsonObject(uid)
            return DbtModel(
                uniqueId = uid,
                name = s.string("name") ?: uid.substringAfterLast('.'),
                packageName = s.string("package_name") ?: "",
                layer = "source",
                columns = readColumns(s),
            )
        }
        val n = nodes.getAsJsonObject(uid) ?: return null
        if (n.string("resource_type") != "model") return null
        return DbtModel(
            uniqueId = uid,
            name = n.string("name") ?: uid.substringAfterLast('.'),
            packageName = n.string("package_name") ?: "",
            layer = inferLayer(n),
            columns = readColumns(n),
        )
    }

    private fun inferLayer(n: JsonObject): String? {
        val path = n.string("path") ?: return null
        // Path is relative to model-paths/, e.g. "staging/stg_orders.sql".
        val firstSegment = path.substringBefore('/').lowercase()
        return when {
            firstSegment.startsWith("stg") || firstSegment == "staging" -> "staging"
            firstSegment.startsWith("int") || firstSegment == "intermediate" -> "intermediate"
            firstSegment == "marts" || firstSegment == "mart" || firstSegment == "models" -> "marts"
            else -> firstSegment.takeIf { it.isNotEmpty() }
        }
    }

    private fun readColumns(n: JsonObject): List<ColumnSpec> {
        val cols = n.getAsJsonObject("columns") ?: return emptyList()
        return cols.entrySet().mapNotNull { (name, v) ->
            if (v == null || v.isJsonNull || !v.isJsonObject) return@mapNotNull ColumnSpec(name = name)
            val obj = v.asJsonObject
            ColumnSpec(
                name = name,
                type = obj.string("data_type"),
                description = obj.string("description")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

// Gson's `.asString` throws on JsonNull — wrap once for safety.
private fun JsonObject.string(key: String): String? {
    val el: JsonElement = get(key) ?: return null
    if (el.isJsonNull) return null
    if (!el.isJsonPrimitive) return null
    return el.asString
}
