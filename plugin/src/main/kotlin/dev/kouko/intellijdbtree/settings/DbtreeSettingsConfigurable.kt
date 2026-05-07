package dev.kouko.intellijdbtree.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page at: Settings → Tools → dbtree.
 *
 * Phase A2: only the Python interpreter path. Test/validate buttons can
 * come later if needed.
 */
class DbtreeSettingsConfigurable : Configurable {

    private var pythonField: TextFieldWithBrowseButton? = null
    private var timeoutSpinner: JSpinner? = null
    private var component: JComponent? = null

    override fun getDisplayName(): String = "dbtree"
    override fun getHelpTopic(): String? = null

    override fun createComponent(): JComponent {
        val field = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(
                "Select Python Interpreter",
                "Pick a Python with sqlglot installed; used for column-level lineage",
                null,
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor(),
            )
        }
        pythonField = field

        val description = JLabel(
            "<html><body style='width: 480px; color: #888'>" +
                "Path to a Python interpreter that has the <code>sqlglot</code> package installed. " +
                "Used to compute column-level lineage by parsing each model's compiled SQL.<br><br>" +
                "<b>Leave blank to auto-detect.</b> dbtree will try, in order:<br>" +
                "&nbsp;&nbsp;1. The project's IDE-configured Python interpreter (DataSpell / PyCharm / IntelliJ + Python plugin)<br>" +
                "&nbsp;&nbsp;2. <code>&lt;dbt_project&gt;/.venv/bin/python</code> (the convention <code>uv</code> and <code>python -m venv</code> use)<br><br>" +
                "Set this field manually to override auto-detection — useful when sqlglot is in a different env (conda, pyenv, system).<br><br>" +
                "Example: <code>/path/to/your/dbt-project/.venv/bin/python</code>" +
                "</body></html>",
        )

        val spinner = JSpinner(
            SpinnerNumberModel(
                DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS,
                DbtreeSettingsService.TIMEOUT_MIN_SECONDS,
                DbtreeSettingsService.TIMEOUT_MAX_SECONDS,
                1,
            ),
        )
        timeoutSpinner = spinner

        val timeoutDescription = JLabel(
            "<html><body style='width: 480px; color: #888'>" +
                "Per-invocation timeout for the Python sidecar. " +
                "Default <b>${DbtreeSettingsService.DEFAULT_TIMEOUT_SECONDS}s</b>; " +
                "raise this if your column traces involve deep " +
                "upstream/downstream chains (large dbt projects can need 30-60s).<br><br>" +
                "Range: ${DbtreeSettingsService.TIMEOUT_MIN_SECONDS}-${DbtreeSettingsService.TIMEOUT_MAX_SECONDS} seconds." +
                "</body></html>",
        )

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Python interpreter:", field, 1, false)
            .addComponent(description)
            .addLabeledComponent("Sidecar timeout (seconds):", spinner, 1, false)
            .addComponent(timeoutDescription)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel.border = JBUI.Borders.empty(12)
        component = panel
        return panel
    }

    override fun isModified(): Boolean {
        val cur = DbtreeSettingsService.getInstance().state
        if (pythonField?.text != cur.pythonInterpreterPath) return true
        if ((timeoutSpinner?.value as? Int) != cur.sidecarTimeoutSeconds) return true
        return false
    }

    override fun apply() {
        val cur = DbtreeSettingsService.getInstance().state
        cur.pythonInterpreterPath = pythonField?.text?.trim().orEmpty()
        (timeoutSpinner?.value as? Int)?.let { cur.sidecarTimeoutSeconds = it }
    }

    override fun reset() {
        val cur = DbtreeSettingsService.getInstance().state
        pythonField?.text = cur.pythonInterpreterPath
        timeoutSpinner?.value = DbtreeSettingsService.clampTimeoutSeconds(cur.sidecarTimeoutSeconds)
    }

    override fun disposeUIResources() {
        pythonField = null
        timeoutSpinner = null
        component = null
    }
}
