package dev.kouko.intellijdbtree.lineage

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Find dbt projects (any directory containing `dbt_project.yml`) under the
 * IntelliJ project root.
 *
 * Pattern adapted from `ramonvermeulen/dbt-toolkit` (GPL-3.0):
 * - Honors `.gitignore` via `ChangeListManager.isIgnoredFile`.
 * - Skips dbt's own build artifacts (`target/`, `dbt_packages/`) and common
 *   Python/Node virtualenv paths so we don't recurse into hundreds of MB of
 *   noise. Necessary for monorepo cases like iCHEF where the dbt project
 *   sits in a subfolder.
 */
object DbtProjectDetector {

    internal val SKIP_PATH_PATTERN: Regex = Regex(
        ".*/(target|dbt_packages|\\.venv|venv|node_modules|\\.git|build|dist|__pycache__)(/.*)?$",
    )

    /**
     * Returns every dbt project root reachable under [project]'s base
     * directory. The list is in discovery order (depth-first).
     */
    fun findAll(project: Project): List<Path> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val changeListManager = ChangeListManager.getInstance(project)
        val projects = mutableListOf<Path>()

        VfsUtil.visitChildrenRecursively(
            baseDir,
            object : VirtualFileVisitor<Any>() {
                override fun visitFile(file: VirtualFile): Boolean {
                    if (changeListManager.isIgnoredFile(file)) return false
                    if (file.path.matches(SKIP_PATH_PATTERN)) return false
                    if (file.isDirectory) return true

                    if (file.name == "dbt_project.yml") {
                        file.parent?.path?.let { projects.add(Paths.get(it)) }
                        // Don't descend into this dbt project's own subtree
                        // (we don't expect nested dbt projects).
                        return false
                    }
                    return true
                }
            },
        )
        return projects
    }

    /** First dbt project under the IntelliJ project, or null. */
    fun findFirst(project: Project): Path? = findAll(project).firstOrNull()
}
