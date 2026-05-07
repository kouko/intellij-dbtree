package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Watches `target/manifest.json` and `target/catalog.json` and triggers a
 * lineage reload when either changes. Fires for create / delete / content
 * change so we cover `dbt parse`, `dbt clean`, and editor-driven edits.
 *
 * Wired up via the `<projectListeners>` extension in plugin.xml. Out-of-order
 * publishes from a noisy `dbt run` (the manifest is rewritten several times)
 * are absorbed by [LineageInfoService]'s epoch counter — only the latest
 * refresh survives.
 */
class DbtTargetFileListener(private val project: Project) : BulkFileListener {

    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        if (events.any { isManifestStatusEvent(it.path) }) {
            project.service<LineageInfoService>().refreshFromDisk()
        }
    }
}

/**
 * Pure path predicate, exposed for unit testing. Matches dbt's exact
 * conventional output paths regardless of the project being a monorepo
 * subfolder (the path can have any prefix).
 */
internal fun isManifestStatusEvent(path: String): Boolean =
    path.endsWith("/target/manifest.json") || path.endsWith("/target/catalog.json")
