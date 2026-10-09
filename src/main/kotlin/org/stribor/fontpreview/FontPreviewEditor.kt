package org.stribor.fontpreview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.util.concurrent.CancellationException
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

data class PreviewState(val size: Int = 48, val face: Int = 0, val selected: Int = 0, val info: Boolean = false) : FileEditorState {
    override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel) = otherState == this
}

class FontPreviewEditor(private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val root = JBPanel<JBPanel<*>>(BorderLayout())
    private val grid = GlyphGrid()
    private val title = JBLabel(file.name)
    private val summary = JBLabel("Loading font…")
    private val selection = JBLabel(" ")
    private val size = JSlider(16, 160, 48)
    private val sizeLabel = JBLabel("48 px")
    private val faceSelector = ComboBox<FontFace>()
    private val infoToggle = JToggleButton(AllIcons.General.Information)
    private val sidebar = JBPanel<JBPanel<*>>(BorderLayout())
    private val infoText = JBTextArea()
    private val content = JBPanel<JBPanel<*>>(BorderLayout())
    private val placeholder = JBLabel("Loading font…", SwingConstants.CENTER)
    private val scroll = JBScrollPane(grid)
    private val properties = PropertyChangeSupport(this)
    private val generation = AtomicInteger()
    private var task: Future<*>? = null
    @Volatile private var disposed = false
    private var document: FontDocument? = null
    private var pendingState = PreviewState()
    private var installingFaces = false
    private val copyButtons = mutableListOf<JButton>()
    private val connection = ApplicationManager.getApplication().messageBus.connect(this)

    init {
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 2)
        summary.foreground = JBColor.GRAY
        val heading = JBPanel<JBPanel<*>>(GridLayout(2, 1, 0, JBUI.scale(3))).apply {
            add(title); add(summary)
        }
        size.preferredSize = JBUI.size(140, 28)
        size.toolTipText = "Glyph size"
        size.accessibleContext.accessibleName = "Glyph size in pixels"
        sizeLabel.preferredSize = JBUI.size(52, 28)
        faceSelector.isVisible = false
        faceSelector.toolTipText = "Font collection face"
        faceSelector.accessibleContext.accessibleName = "Font collection face"
        infoToggle.toolTipText = "Show font and glyph information"
        infoToggle.accessibleContext.accessibleName = "Font information"
        val controls = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            add(faceSelector); add(JBLabel("Size")); add(this@FontPreviewEditor.size); add(sizeLabel); add(infoToggle)
        }
        root.add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(16), 0)).apply {
            border = JBUI.Borders.empty(12, 16)
            add(heading, BorderLayout.CENTER); add(controls, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        scroll.border = JBUI.Borders.empty()
        content.add(placeholder, BorderLayout.CENTER)
        infoText.isEditable = false
        infoText.lineWrap = true
        infoText.wrapStyleWord = true
        infoText.border = JBUI.Borders.empty(16)
        sidebar.add(JBScrollPane(infoText).apply { border = JBUI.Borders.empty() })
        sidebar.preferredSize = JBUI.size(300, 0)
        sidebar.border = JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0)
        sidebar.isVisible = false
        content.add(sidebar, BorderLayout.EAST)
        root.add(content, BorderLayout.CENTER)
        val copies = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            add(copyButton("Copy \\u…", CopyFormat.ESCAPE))
            add(copyButton("Copy U+…", CopyFormat.NOTATION))
            add(copyButton("Copy Character", CopyFormat.CHARACTER))
        }
        root.add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 16)
            add(selection, BorderLayout.CENTER); add(copies, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        grid.onSelection = { updateSelection() }
        grid.onCopy = { copy(CopyFormat.CHARACTER) }
        grid.onInspect = { inspectGlyph() }
        grid.onContextMenu = { point -> showContextMenu(point) }
        size.addChangeListener {
            grid.previewSize = size.value
            sizeLabel.text = "${size.value} px"
            if (document == null) pendingState = pendingState.copy(size = size.value)
        }
        faceSelector.addActionListener {
            if (!installingFaces) {
                grid.face = faceSelector.selectedItem as? FontFace
                updateHeading(); updateSelection()
                scroll.verticalScrollBar.value = 0
            }
        }
        infoToggle.addActionListener {
            sidebar.isVisible = infoToggle.isSelected
            if (document == null) pendingState = pendingState.copy(info = infoToggle.isSelected)
            updateInformation(); content.revalidate(); content.repaint()
        }
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file == file }) later {
                    properties.firePropertyChange("valid", true, file.isValid)
                    if (file.isValid) reload()
                }
            }
        })
        updateSelection()
        reload()
    }

    private fun later(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!disposed) action() }, ModalityState.any())
    }

    private fun reload() {
        if (document != null) pendingState = currentState()
        val token = generation.incrementAndGet()
        task?.cancel(true)
        document = null
        grid.face = null
        installingFaces = true
        try { faceSelector.removeAllItems() } finally { installingFaces = false }
        faceSelector.isEnabled = false
        title.text = file.name
        summary.text = "Loading font…"
        placeholder.text = "Loading font…"
        content.remove(scroll)
        content.add(placeholder, BorderLayout.CENTER)
        content.revalidate(); content.repaint()
        task = ApplicationManager.getApplication().executeOnPooledThread {
            try {
                require(file.length <= FontDocument.MAX_BYTES) { "Fonts larger than 128 MB are not supported." }
                val loaded = FontDocument.load(file.contentsToByteArray()) {
                    if (Thread.currentThread().isInterrupted || disposed || token != generation.get()) throw CancellationException()
                    ProgressManager.checkCanceled()
                }
                later { if (token == generation.get()) install(loaded) }
            } catch (_: CancellationException) {
                // A superseded load or disposed editor must never update the UI.
            } catch (_: ProcessCanceledException) {
                // IntelliJ cancellation is control flow.
            } catch (error: Exception) {
                later {
                    if (token == generation.get()) {
                        summary.text = "Unable to preview this font"
                        placeholder.text = "Unable to preview: ${error.message ?: error.javaClass.simpleName}"
                        placeholder.toolTipText = placeholder.text
                    }
                }
            }
        }
    }

    private fun install(loaded: FontDocument) {
        document = loaded
        installingFaces = true
        try {
            faceSelector.removeAllItems()
            loaded.faces.forEach(faceSelector::addItem)
            faceSelector.selectedIndex = pendingState.face.coerceIn(0, loaded.faces.lastIndex)
            faceSelector.isVisible = loaded.faces.size > 1
            faceSelector.isEnabled = true
        } finally { installingFaces = false }
        grid.face = faceSelector.selectedItem as FontFace
        size.value = pendingState.size.coerceIn(16, 160)
        infoToggle.isSelected = pendingState.info
        sidebar.isVisible = pendingState.info
        content.remove(placeholder)
        content.add(scroll, BorderLayout.CENTER)
        updateHeading()
        updateSelection()
        content.revalidate(); content.repaint()
        later { grid.select(pendingState.selected) }
    }

    private fun updateHeading() {
        val face = grid.face ?: return
        title.text = face.family
        summary.text = "${face.style} · ${face.glyphs.size} glyphs · ${document?.format}"
    }

    private fun updateSelection() {
        val glyph = grid.selectedGlyph
        selection.text = glyph?.let { "${it.description} · gid${it.id}" } ?: "Select a glyph"
        copyButtons.forEach {
            it.isEnabled = glyph?.canCopy == true
            it.toolTipText = if (glyph?.codePoints?.isEmpty() == true) glyph.defaultCopyMapping?.let { source ->
                "Copies input text. Enable ${source.features.joinToString(", ")} to render this glyph."
            } ?: "This glyph has no Unicode or supported OpenType input mapping." else null
        }
        updateInformation()
    }

    private fun updateInformation() {
        if (!sidebar.isVisible) return
        val face = grid.face
        infoText.text = if (face == null) "No font information available." else buildString {
            appendLine(face.family)
            appendLine(face.style)
            appendLine()
            appendLine("FONT")
            appendLine("Format: ${document?.format}")
            appendLine("File: ${file.name}")
            appendLine("Size: ${document?.byteSize?.let { "%,d".format(it) }} bytes")
            appendLine("Faces: ${document?.faces?.size}")
            appendLine("Glyphs: ${face.glyphs.size}")
            appendLine("Mapped glyphs: ${face.mappedGlyphCount}")
            appendLine("Units per em: ${face.unitsPerEm}")
            grid.selectedGlyph?.let { glyph ->
                appendLine(); appendLine("SELECTED GLYPH")
                appendLine("Glyph ID: ${glyph.id}")
                if (glyph.codePoints.isEmpty()) appendLine("No direct Unicode mapping")
                else {
                    appendLine("Unicode: ${glyph.codePoints.take(32).joinToString(", ", transform = UnicodeText::notation)}")
                    appendLine("Escape: ${UnicodeText.escape(glyph.codePoints.first())}")
                    if (glyph.codePoints.size > 32) appendLine("${glyph.codePoints.size} Unicode mappings in total")
                    appendLine(Character.getName(glyph.codePoints.first()) ?: "Unnamed character")
                }
                if (glyph.sources.isNotEmpty()) {
                    appendLine("OpenType inputs:")
                    glyph.sources.forEach { appendLine("${it.notation} → ${it.features.joinToString(", ")}") }
                    appendLine("Copy uses input text; enable the listed feature to render this variant.")
                }
                val vector = face.font.deriveFont(face.unitsPerEm.toFloat()).createGlyphVector(
                    java.awt.font.FontRenderContext(null, true, true), intArrayOf(glyph.id)
                )
                val metrics = vector.getGlyphMetrics(0)
                appendLine("Advance: ${metrics.advanceX.toInt()} font units")
                appendLine("Left bearing: ${metrics.lsb.toInt()} font units")
            }
            val labels = linkedMapOf(4 to "Full name", 6 to "PostScript name", 5 to "Version", 10 to "Description",
                8 to "Manufacturer", 9 to "Designer", 11 to "Vendor URL", 12 to "Designer URL", 0 to "Copyright", 13 to "License", 14 to "License URL")
            labels.forEach { (id, label) -> face.names[id]?.let { appendLine(); appendLine(label.uppercase()); appendLine(it) } }
        }
        infoText.caretPosition = 0
    }

    private enum class CopyFormat(val title: String) {
        ESCAPE("Copy Unicode Escape"), NOTATION("Copy U+ Notation"), CHARACTER("Copy Character")
    }
    private fun copyButton(label: String, format: CopyFormat) = JButton(label).apply {
        toolTipText = format.title
        addActionListener { copy(format) }
        copyButtons.add(this)
    }
    private fun copy(format: CopyFormat, mapping: GlyphCopyMapping? = grid.selectedGlyph?.defaultCopyMapping) {
        if (mapping == null) return
        val text = when (format) {
            CopyFormat.ESCAPE -> mapping.escape
            CopyFormat.NOTATION -> mapping.notation
            CopyFormat.CHARACTER -> mapping.character
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        selection.text = "Copied $text" + if (mapping.features.isEmpty()) " · gid${grid.selectedGlyph?.id}" else " · requires ${mapping.features.joinToString(", ")}"
    }

    private fun copyGlyphId(glyph: Glyph) {
        CopyPasteManager.getInstance().setContents(StringSelection(glyph.id.toString()))
        selection.text = "Copied glyph ID ${glyph.id}"
    }

    private fun showContextMenu(point: Point) {
        val glyph = grid.selectedGlyph ?: return
        val group = DefaultActionGroup()
        if (glyph.canCopy) CopyFormat.entries.forEach { format ->
            group.add(object : AnAction(format.title) {
                override fun actionPerformed(e: AnActionEvent) = copy(format)
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = glyph.canCopy
                    e.presentation.description = if (glyph.codePoints.isEmpty()) glyph.defaultCopyMapping?.let {
                        "Copies input text; enable ${it.features.joinToString(", ")} to render this glyph."
                    } ?: "This glyph has no Unicode or supported OpenType input mapping." else format.title
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        if (glyph.canCopy) group.addSeparator()
        group.add(object : AnAction("Copy Glyph ID") { override fun actionPerformed(e: AnActionEvent) = copyGlyphId(glyph) })
        group.add(object : AnAction("Inspect Glyph") { override fun actionPerformed(e: AnActionEvent) = inspectGlyph() })
        ActionManager.getInstance().createActionPopupMenu("StriborFontPreview", group).component.show(grid, point.x, point.y)
    }

    private fun inspectGlyph() {
        val face = grid.face ?: return
        val glyph = grid.selectedGlyph ?: return
        val panel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(12), JBUI.scale(12))).apply { border = JBUI.Borders.empty(16) }
        panel.add(object : JComponent() {
            init { preferredSize = JBUI.size(280, 200) }
            override fun paintComponent(graphics: Graphics) {
                val g = graphics.create() as Graphics2D
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g.color = JBColor.foreground()
                    drawCenteredGlyph(g, face.font.deriveFont(JBUI.scale(144).toFloat()).createGlyphVector(g.fontRenderContext, intArrayOf(glyph.id)), Rectangle(0, 0, width, height))
                } finally { g.dispose() }
            }
        }, BorderLayout.CENTER)
        val bottom = JBPanel<JBPanel<*>>().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        bottom.add(JBLabel("gid${glyph.id}"))
        if (glyph.codePoints.isEmpty()) bottom.add(JBLabel("No direct Unicode mapping"))
        if (glyph.canCopy) {
            val mappings = glyph.copyMappings
            val mapping = ComboBox<String>(mappings.map { it.label }.toTypedArray())
            mapping.toolTipText = "Character encoding or OpenType input"
            bottom.add(mapping)
            val hint = JBLabel()
            fun updateHint() {
                val selected = mappings[mapping.selectedIndex]
                hint.text = if (selected.features.isEmpty()) " " else "Input text requires ${selected.features.joinToString(", ")}."
            }
            mapping.addActionListener { updateHint() }
            updateHint()
            bottom.add(hint)
            bottom.add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(8))).apply {
                CopyFormat.entries.forEach { format -> add(JButton(when (format) {
                    CopyFormat.ESCAPE -> "\\u…"; CopyFormat.NOTATION -> "U+…"; CopyFormat.CHARACTER -> "Character"
                }).apply { toolTipText = format.title; addActionListener { copy(format, mappings[mapping.selectedIndex]) } }) }
            })
        }
        bottom.add(JButton("Copy Glyph ID").apply { addActionListener { copyGlyphId(glyph) } })
        panel.add(bottom, BorderLayout.SOUTH)
        JBPopupFactory.getInstance().createComponentPopupBuilder(panel, panel)
            .setTitle("${glyph.unicode} · Glyph ${glyph.id}")
            .setRequestFocus(true).setMovable(true).setResizable(false)
            .createPopup().showInCenterOf(grid)
    }

    private fun currentState() = PreviewState(size.value, faceSelector.selectedIndex.coerceAtLeast(0), grid.selectedIndex.coerceAtLeast(0), infoToggle.isSelected)
    override fun getState(level: FileEditorStateLevel): FileEditorState = if (document == null) pendingState else currentState()
    override fun setState(state: FileEditorState) {
        if (state !is PreviewState) return
        pendingState = state
        size.value = state.size.coerceIn(16, 160)
        infoToggle.isSelected = state.info
        sidebar.isVisible = state.info
        if (document != null) {
            faceSelector.selectedIndex = state.face.coerceIn(0, faceSelector.itemCount - 1)
            grid.select(state.selected)
            updateInformation()
        }
        content.revalidate(); content.repaint()
    }
    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = grid
    override fun getName() = "Font Viewer"
    override fun getFile() = file
    override fun isModified() = false
    override fun isValid() = !disposed && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = properties.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = properties.removePropertyChangeListener(listener)
    override fun dispose() {
        disposed = true
        generation.incrementAndGet()
        task?.cancel(true)
        connection.disconnect()
        document = null
        grid.face = null
        installingFaces = true
        try { faceSelector.removeAllItems() } finally { installingFaces = false }
    }
}
