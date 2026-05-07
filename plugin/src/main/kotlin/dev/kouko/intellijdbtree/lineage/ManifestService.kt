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
     * Most recent [RefreshResult]. Survives even when [state] gets reset to
     * null on failure, so callers (e.g. [LineageInfoService]) can surface a
     * user-facing reason ("run dbt parse first") rather than silently
     * showing a blank canvas. Initialised pessimistically — anyone reading
     * before the first refresh sees "no project" rather than a stale Ok.
     */
    private val lastResult = AtomicReference<RefreshResult>(RefreshResult.NoDbtProject)

    /** Latest refresh outcome — Ok if a manifest is currently loaded, else the failure reason. */
    fun lastRefreshResult(): RefreshResult = lastResult.get()

    /**
     * Re-detect the dbt project, reload manifest.json, and (if present)
     * load catalog.json to enrich model column lists with real warehouse
     * types. Safe to call from any thread; performs file I/O.
     */
    fun refresh(): RefreshResult {
        val result = loadManifestFromDisk(
            dbtProjectDir = DbtProjectDetector.findFirst(project),
            onWarn = { msg, e -> log.warn(msg, e) },
        )
        when (result) {
            is RefreshResult.Ok -> {
                state.set(result.manifest)
                log.info(
                    "ManifestService: loaded ${result.manifest.modelCount()} models, " +
                        "${result.manifest.sourceCount()} sources from " +
                        "${result.manifest.dbtProjectDir.resolve("target").resolve("manifest.json")}" +
                        (if (result.manifest.hasCatalog()) " (+ catalog.json)" else " (no catalog.json)"),
                )
            }
            is RefreshResult.ParseError -> {
                state.set(null)
                log.warn("ManifestService: failed to parse ${result.path}", result.cause)
            }
            else -> state.set(null)
        }
        lastResult.set(result)
        return result
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
 * Pure file-I/O half of [ManifestService.refresh]: given a candidate dbt
 * project directory (already resolved by the caller via [DbtProjectDetector]
 * or otherwise), read `target/manifest.json` and optionally `target/catalog.json`
 * and return the matching [ManifestService.RefreshResult].
 *
 * No state mutation, no IDE coupling — extracted so the four RefreshResult
 * branches can be exercised by `@TempDir`-based unit tests.
 *
 * @param dbtProjectDir result of project detection; null = no dbt project found
 * @param onWarn        called for non-fatal parse failures (currently:
 *                      catalog.json present but unparseable). Defaults to
 *                      no-op for tests; production passes a Logger.warn binding.
 */
internal fun loadManifestFromDisk(
    dbtProjectDir: Path?,
    onWarn: (String, Throwable) -> Unit = { _, _ -> },
): ManifestService.RefreshResult {
    if (dbtProjectDir == null) return ManifestService.RefreshResult.NoDbtProject
    val targetDir = dbtProjectDir.resolve("target")
    val manifestPath = targetDir.resolve("manifest.json")
    if (!Files.isRegularFile(manifestPath)) {
        return ManifestService.RefreshResult.NoManifest(manifestPath)
    }
    return try {
        val manifestJson = JsonParser.parseString(Files.readString(manifestPath)).asJsonObject
        val catalogPath = targetDir.resolve("catalog.json")
        val catalogJson = if (Files.isRegularFile(catalogPath)) {
            try {
                JsonParser.parseString(Files.readString(catalogPath)).asJsonObject
            } catch (e: Exception) {
                onWarn("ManifestService: failed to parse $catalogPath, ignoring", e)
                null
            }
        } else {
            null
        }
        ManifestService.RefreshResult.Ok(ParsedManifest(manifestJson, catalogJson, dbtProjectDir))
    } catch (e: Exception) {
        ManifestService.RefreshResult.ParseError(manifestPath, e)
    }
}

/**
 * Cached, read-only view over a parsed manifest.json. Thread-safe to
 * read concurrently; never mutated after construction.
 */
class ParsedManifest(
    private val raw: JsonObject,
    private val catalog: JsonObject?,
    val dbtProjectDir: Path,
) {
    private val nodes: JsonObject = raw.getAsJsonObject("nodes") ?: JsonObject()
    private val sources: JsonObject = raw.getAsJsonObject("sources") ?: JsonObject()
    private val childMap: JsonObject = raw.getAsJsonObject("child_map") ?: JsonObject()
    private val parentMap: JsonObject = raw.getAsJsonObject("parent_map") ?: JsonObject()
    private val catalogNodes: JsonObject = catalog?.getAsJsonObject("nodes") ?: JsonObject()
    private val catalogSources: JsonObject = catalog?.getAsJsonObject("sources") ?: JsonObject()

    fun hasCatalog(): Boolean = catalog != null

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

    /** Find a model unique_id by its bare name (no package qualifier). */
    fun findModelByName(name: String): String? {
        for ((uid, v) in nodes.entrySet()) {
            val n = v.asJsonObject
            if (n.string("resource_type") == "model" && n.string("name") == name) {
                return uid
            }
        }
        return null
    }

    /** Return the unique_id of a model, paired with its bare name (for matching). */
    fun modelName(uniqueId: String): String? {
        val n = nodes.getAsJsonObject(uniqueId) ?: return null
        if (n.string("resource_type") != "model") return null
        return n.string("name")
    }

    /** Direct downstream models / sources of [uniqueId] (child_map entries). */
    fun directChildren(uniqueId: String): List<String> {
        val arr = childMap.getAsJsonArray(uniqueId) ?: return emptyList()
        return arr.mapNotNull { e ->
            val s = e?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            // Only consider models — sources/tests/seeds aren't downstream column targets we care about.
            if (nodes.getAsJsonObject(s)?.string("resource_type") == "model") s else null
        }
    }

    /**
     * Find a source unique_id by its dbt source `name` (table identifier in
     * the warehouse). dbt sources are `source.<package>.<source>.<table>`,
     * but the relevant identifier for sqlglot column lineage is the table.
     */
    fun findSourceByName(name: String): String? {
        for ((uid, v) in sources.entrySet()) {
            val s = v.asJsonObject
            if (s.string("name") == name) return uid
        }
        return null
    }

    /**
     * BFS upstream and/or downstream from [seed], bounded by hop counts.
     *
     * If [seed] is null, returns the full project graph (debug only —
     * use bounded variants in normal flow).
     *
     * @param upHops   max hops to walk via parent_map (Int.MAX_VALUE for unlimited)
     * @param downHops max hops to walk via child_map  (Int.MAX_VALUE for unlimited)
     */
    fun buildLineage(seed: String?, upHops: Int = Int.MAX_VALUE, downHops: Int = Int.MAX_VALUE): LineagePayload {
        val visited = mutableSetOf<String>()
        val edges = LinkedHashSet<ModelEdge>()

        fun walkUp(node: String, remaining: Int) {
            if (!visited.add(node)) {
                // Already visited; still record edges below if we re-encounter
                // through a different branch (handled by edges set dedup).
            }
            if (remaining <= 0) return
            parentMap.getAsJsonArray(node)?.forEach { p ->
                val parent = p.asString
                edges += ModelEdge(parent, node)
                // Re-walk visited nodes when we still have hop budget: a node
                // re-entered via a skip edge may now have MORE remaining than
                // when we first reached it via a long path, letting us extend
                // the visit into subtrees the first walk couldn't afford.
                // Without this, hop-bounded queries miss nodes connected via
                // shortcut refs (iCHEF has 126 such skip edges = 9.1% of total).
                if (parent !in visited || remaining > 0) {
                    visited.add(parent)
                    walkUp(parent, remaining - 1)
                }
            }
        }

        fun walkDown(node: String, remaining: Int) {
            visited.add(node)
            if (remaining <= 0) return
            childMap.getAsJsonArray(node)?.forEach { c ->
                val child = c.asString
                edges += ModelEdge(node, child)
                if (child !in visited || remaining > 0) {
                    visited.add(child)
                    walkDown(child, remaining - 1)
                }
            }
        }

        if (seed != null) {
            visited.add(seed)
            walkUp(seed, upHops)
            walkDown(seed, downHops)
        } else {
            for ((uid, _) in nodes.entrySet()) {
                visited.add(uid)
                walkUp(uid, upHops)
                walkDown(uid, downHops)
            }
            for ((uid, _) in sources.entrySet()) {
                visited.add(uid)
                walkDown(uid, downHops)
            }
        }

        // After the directional walks, include every manifest edge whose
        // *both* endpoints already landed in `visited`. Without this pass,
        // a "diamond" pattern like
        //     orders ─┐
        //     customers ┴─→ customer_combined_metrics
        // (with seed = customers) would lose the orders→customer_combined_metrics
        // edge: walkUp from customers reaches orders, walkDown reaches
        // customer_combined_metrics, but neither walk picks up the lateral
        // edge that joins them through a *descendant's* other parent.
        for (uid in visited.toList()) {
            childMap.getAsJsonArray(uid)?.forEach { c ->
                val child = c.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    ?: return@forEach
                if (child in visited) edges += ModelEdge(uid, child)
            }
            parentMap.getAsJsonArray(uid)?.forEach { p ->
                val parent = p.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    ?: return@forEach
                if (parent in visited) edges += ModelEdge(parent, uid)
            }
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
                folder = "source",
                columns = mergedColumns(uid, s, fromSources = true),
            )
        }
        val n = nodes.getAsJsonObject(uid) ?: return null
        if (n.string("resource_type") != "model") return null
        return DbtModel(
            uniqueId = uid,
            name = n.string("name") ?: uid.substringAfterLast('.'),
            packageName = n.string("package_name") ?: "",
            layer = inferLayer(n),
            folder = inferFolder(n),
            columns = mergedColumns(uid, n, fromSources = false),
        )
    }

    /**
     * The raw first path segment, shown verbatim on the model card chip.
     * Differs from [inferLayer] which collapses to a canonical color bucket;
     * this preserves namespaces like `marts_msd` / `dash` / `ads_data`.
     */
    private fun inferFolder(n: JsonObject): String? {
        val path = n.string("path") ?: return null
        return path.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun inferLayer(n: JsonObject): String? {
        val path = n.string("path") ?: return null
        // Path is relative to model-paths/, e.g. "staging/stg_orders.sql".
        val firstSegment = path.substringBefore('/').lowercase()
        return when {
            firstSegment.startsWith("stg") || firstSegment == "staging" -> "staging"
            firstSegment.startsWith("int") || firstSegment == "intermediate" -> "intermediate"
            // `mart`, `marts`, `marts_msd`, `marts_qlr` etc. all map to "marts".
            // dbt projects often namespace marts (`marts_<team>` / `marts_<domain>`)
            // and we want them all to share the same "marts" color tier.
            firstSegment.startsWith("mart") || firstSegment == "models" -> "marts"
            // Anything else returns null. The frontend's normalizeLayer falls
            // back to "staging" for unknown values, but emitting a non-canonical
            // string here would crash older frontends and gives the new one
            // nothing useful to do anyway.
            else -> null
        }
    }

    /**
     * Build the column list for a model/source. Sources of truth, in order:
     *   1. catalog.json (real warehouse columns + types) — if present
     *   2. manifest.json columns (from schema.yml) — descriptions + fallback
     * The merge: take the union; type from catalog if available, else manifest;
     * description from manifest (yml docs) regardless.
     */
    private fun mergedColumns(uid: String, manifestNode: JsonObject, fromSources: Boolean): List<ColumnSpec> {
        val manifestCols: Map<String, JsonObject> = manifestNode.getAsJsonObject("columns")
            ?.entrySet()
            ?.filter { (_, v) -> v != null && !v.isJsonNull && v.isJsonObject }
            ?.associate { (k, v) -> k to v.asJsonObject }
            ?: emptyMap()

        val catalogContainer = if (fromSources) catalogSources else catalogNodes
        val catalogCols: Map<String, JsonObject> = catalogContainer.getAsJsonObject(uid)
            ?.getAsJsonObject("columns")
            ?.entrySet()
            ?.filter { (_, v) -> v != null && !v.isJsonNull && v.isJsonObject }
            ?.associate { (k, v) -> k to v.asJsonObject }
            ?: emptyMap()

        if (manifestCols.isEmpty() && catalogCols.isEmpty()) return emptyList()

        // Preserve catalog's index ordering when available; otherwise use manifest order.
        val ordered: List<String> = if (catalogCols.isNotEmpty()) {
            catalogCols.entries
                .sortedBy { it.value.get("index")?.let { idx -> if (idx.isJsonPrimitive) idx.asInt else 0 } ?: 0 }
                .map { it.key } + manifestCols.keys.filter { it !in catalogCols }
        } else {
            manifestCols.keys.toList()
        }

        return ordered.map { name ->
            val cat = catalogCols[name]
            val man = manifestCols[name]
            ColumnSpec(
                name = name,
                type = cat?.string("type") ?: man?.string("data_type"),
                description = man?.string("description")?.takeIf { it.isNotBlank() }
                    ?: cat?.string("comment")?.takeIf { it.isNotBlank() },
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
