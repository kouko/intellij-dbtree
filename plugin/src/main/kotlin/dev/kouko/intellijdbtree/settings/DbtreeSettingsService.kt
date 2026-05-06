package dev.kouko.intellijdbtree.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Persistent settings for intellij-dbtree.
 *
 * Application-level (one config across all projects) for now — Phase A2's
 * scope. If users want per-project Python interpreters later, this becomes
 * project-scoped + per-project override.
 */
@Service(Service.Level.APP)
@State(
    name = "intellij-dbtree",
    storages = [Storage("intellij-dbtree.xml")],
)
class DbtreeSettingsService : PersistentStateComponent<DbtreeSettingsService.State> {

    data class State(
        /**
         * Absolute path to a Python interpreter that has `sqlglot` installed.
         * The plugin spawns this Python with `PYTHONPATH` set to the bundled
         * sidecar directory, then runs `python -m dbtree_lineage.cli ...`.
         * Empty = column-level lineage disabled.
         */
        var pythonInterpreterPath: String = "",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        @JvmStatic
        fun getInstance(): DbtreeSettingsService =
            ApplicationManager.getApplication().getService(DbtreeSettingsService::class.java)
    }
}
