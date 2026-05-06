package dev.kouko.intellijdbtree.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Settings page at: Settings → Tools → intellij-dbtree.
 *
 * Phase A2: only the Python interpreter path. Test/validate buttons can
 * come later if needed.
 */
class DbtreeSettingsConfigurable : Configurable {

    private var pythonField: TextFieldWithBrowseButton? = null
    private var component: JComponent? = null

    override fun getDisplayName(): String = "intellij-dbtree"
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
                "Used to compute column-level lineage by parsing each model's compiled SQL. " +
                "Leave blank to disable column-level lineage.<br><br>" +
                "Example: <code>/path/to/your/dbt-project/.venv/bin/python</code>" +
                "</body></html>",
        )

        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Python interpreter:", field, 1, false)
            .addComponent(description)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        panel.border = JBUI.Borders.empty(12)
        component = panel
        return panel
    }

    override fun isModified(): Boolean {
        val cur = DbtreeSettingsService.getInstance().state
        return pythonField?.text != cur.pythonInterpreterPath
    }

    override fun apply() {
        val cur = DbtreeSettingsService.getInstance().state
        cur.pythonInterpreterPath = pythonField?.text?.trim().orEmpty()
    }

    override fun reset() {
        pythonField?.text = DbtreeSettingsService.getInstance().state.pythonInterpreterPath
    }

    override fun disposeUIResources() {
        pythonField = null
        component = null
    }
}
