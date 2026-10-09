package org.stribor.fontpreview

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import java.util.Locale
import org.jdom.Element

object FontFileType : FileType {
    override fun getName() = "StriborFont"
    override fun getDescription() = "Font repertoire"
    override fun getDefaultExtension() = "ttf"
    override fun getIcon() = IconLoader.getIcon("/icons/font.svg", FontFileType::class.java)
    override fun isBinary() = true
    override fun isReadOnly() = true
    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null
}

class FontEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) =
        !file.isDirectory && file.extension?.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc", "otc", "woff")
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = FontPreviewEditor(file)
    override fun getEditorTypeId() = "org.stribor.fontpreview.editor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
    @Suppress("OVERRIDE_DEPRECATION") // Keep the 2025.3-compatible state API.
    override fun readState(source: Element, project: Project, file: VirtualFile): FileEditorState = PreviewState(
        source.getAttributeValue("size")?.toIntOrNull()?.coerceIn(16, 160) ?: 48,
        source.getAttributeValue("face")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        source.getAttributeValue("selected")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        source.getAttributeValue("info") == "true"
    )
    override fun writeState(state: FileEditorState, project: Project, target: Element) {
        if (state is PreviewState) {
            target.setAttribute("size", state.size.toString())
            target.setAttribute("face", state.face.toString())
            target.setAttribute("selected", state.selected.toString())
            target.setAttribute("info", state.info.toString())
        }
    }
}
