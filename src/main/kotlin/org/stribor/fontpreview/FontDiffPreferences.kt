package org.stribor.fontpreview

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class FontDiffOptions(var zoom: Int = 48, var hideUnchanged: Boolean = false, var highlightOutlines: Boolean = true)

/** Application preferences are shared by comparisons and saved by the IDE across restarts. */
@Service(Service.Level.APP)
@State(name = "FontPreviewDiffPreferences", storages = [Storage("font-preview.xml")])
class FontDiffPreferences : PersistentStateComponent<FontDiffOptions> {
    private var options = FontDiffOptions()

    override fun getState() = options.copy()
    override fun loadState(state: FontDiffOptions) { remember(state) }
    fun snapshot() = options.copy()
    fun remember(value: FontDiffOptions) { options = value.copy(zoom = value.zoom.coerceIn(16, 160)) }

    companion object {
        fun getInstance(): FontDiffPreferences = ApplicationManager.getApplication().getService(FontDiffPreferences::class.java)
    }
}
