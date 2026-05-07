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

        /**
         * Per-invocation timeout for the Python sidecar in seconds. Large dbt
         * projects with deep upstream/downstream chains can need >15s for a
         * full column trace. Bounded at the I/O layer to [TIMEOUT_MIN_SECONDS]..
         * [TIMEOUT_MAX_SECONDS]; this field stores the user's request as-is.
         */
        var sidecarTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /**
     * Resolve [State.sidecarTimeoutSeconds] to a millisecond value, clamped
     * to a sane range. Out-of-range values (e.g. malformed XML on disk) fall
     * back to [DEFAULT_TIMEOUT_SECONDS] rather than DoS-ing the user with
     * a 1ms timeout or hanging on a 1-hour timeout.
     */
    fun sidecarTimeoutMillis(): Int =
        clampTimeoutSeconds(myState.sidecarTimeoutSeconds) * 1000

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val TIMEOUT_MIN_SECONDS = 5
        const val TIMEOUT_MAX_SECONDS = 120

        internal fun clampTimeoutSeconds(seconds: Int): Int =
            if (seconds in TIMEOUT_MIN_SECONDS..TIMEOUT_MAX_SECONDS) seconds
            else DEFAULT_TIMEOUT_SECONDS

        @JvmStatic
        fun getInstance(): DbtreeSettingsService =
            ApplicationManager.getApplication().getService(DbtreeSettingsService::class.java)
    }
}
