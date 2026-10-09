package org.stribor.fontpreview

import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.Rectangle2D
import javax.swing.*

/** A plain Swing panel keeps alignment, filtering and selection testable without starting an IDE. */
class FontDiffPanel(
    titles: List<String?>,
    private val owner: Disposable? = null,
    options: FontDiffOptions = FontDiffOptions(),
    private val rememberOptions: (FontDiffOptions) -> Unit = {}
) : JBPanel<FontDiffPanel>(BorderLayout()) {
    val shared = FontDiffLayout().apply { previewSize = options.zoom.coerceIn(16, 160) }
    val leftGrid = FontDiffGrid(false, shared).apply { showOutlineChanges = options.highlightOutlines }
    val rightGrid = FontDiffGrid(true, shared).apply { showOutlineChanges = options.highlightOutlines }
    val leftScroll = JBScrollPane(leftGrid)
    val rightScroll = JBScrollPane(rightGrid)
    val zoom = JSlider(16, 160, shared.previewSize)
    val hideUnchanged = JBCheckBox("Hide unchanged", options.hideUnchanged)
    val outlineColors = JBCheckBox("Highlight outlines", options.highlightOutlines)
    private val sizeLabel = JBLabel("${shared.previewSize} px")
    private val summary = JBLabel("Loading font comparison…")
    private val detail = JBTextArea("Select a glyph to compare").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val faces = listOf(ComboBox<FontFace>(), ComboBox<FontFace>())
    private val headings = listOf(JBLabel(), JBLabel())
    private val copyButtons = mutableListOf<Pair<JButton, Pair<Boolean, String>>>()
    private val previous = JButton("Previous change")
    private val next = JButton("Next change")
    private val inspect = JButton("Inspect / overlay")
    private val body = JBPanel<JBPanel<*>>(GridLayout(1, 2, JBUI.scale(1), 0))
    private var installing = false
    private var syncingScroll = false
    private var documents: List<FontDocument?> = emptyList()
    var onFaceChange: () -> Unit = {}
    var comparison: FontComparison? = null
        private set
    var selectedPair: GlyphPair? = null
        private set
    val leftFaceIndex get() = faces[0].selectedIndex.coerceAtLeast(0)
    val rightFaceIndex get() = faces[1].selectedIndex.coerceAtLeast(0)

    init {
        border = JBUI.Borders.empty(8)
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            add(JBLabel("Size")); add(zoom); add(sizeLabel); add(hideUnchanged); add(outlineColors)
        }
        zoom.preferredSize = JBUI.size(120, 28)
        zoom.toolTipText = "Shared glyph size for both versions"
        zoom.accessibleContext.accessibleName = "Shared glyph size in pixels"
        hideUnchanged.toolTipText = "Hide glyphs whose normalized outlines, metrics and mappings are unchanged"
        outlineColors.toolTipText = "Red: removed outline regions. Green: added outline regions. Neutral: shared outline."
        add(JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.emptyBottom(8)
            add(summary, BorderLayout.NORTH); add(toolbar, BorderLayout.CENTER)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(previous); add(next); add(inspect)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        listOf(leftGrid, rightGrid).forEachIndexed { side, grid ->
            val scroll = if (side == 0) leftScroll else rightScroll
            scroll.border = JBUI.Borders.empty()
            scroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            val header = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(4))).apply {
                border = JBUI.Borders.empty(8)
                add(JBLabel(titles.getOrNull(side) ?: if (side == 0) "Before" else "After"), BorderLayout.NORTH)
                add(headings[side], BorderLayout.CENTER)
                add(faces[side], BorderLayout.SOUTH)
            }
            faces[side].toolTipText = if (side == 0) "Before: font collection face" else "After: font collection face"
            faces[side].addActionListener { if (!installing) onFaceChange() }
            body.add(JBPanel<JBPanel<*>>(BorderLayout()).apply { add(header, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER) })
            grid.onSelect = { select(it) }
            grid.onInspect = { inspectSelection() }
            grid.onCopy = { copy(side == 1, "Character") }
            scroll.viewport.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) = updateLayout()
            })
            scroll.verticalScrollBar.addAdjustmentListener {
                if (!syncingScroll) {
                    syncingScroll = true
                    try { (if (side == 0) rightScroll else leftScroll).verticalScrollBar.value = it.value }
                    finally { syncingScroll = false }
                }
            }
        }
        add(body, BorderLayout.CENTER)
        val bottom = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.emptyTop(8)
            detail.rows = 3
            add(detail, BorderLayout.NORTH)
            add(JBPanel<JBPanel<*>>(GridLayout(1, 2)).apply {
                for (right in listOf(false, true)) add(JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
                    border = JBUI.Borders.emptyRight(8)
                    add(JBLabel(if (right) "After:" else "Before:"), BorderLayout.WEST)
                    add(JBPanel<JBPanel<*>>(GridLayout(2, 2, JBUI.scale(6), JBUI.scale(4))).apply {
                        for (format in listOf("\\u…", "U+…", "Character", "Glyph ID")) {
                            val button = JButton("Copy $format").apply { addActionListener { copy(right, format) } }
                            copyButtons.add(button to (right to format)); add(button)
                        }
                    }, BorderLayout.CENTER)
                })
            }, BorderLayout.CENTER)
        }
        add(bottom, BorderLayout.SOUTH)
        zoom.addChangeListener {
            shared.previewSize = zoom.value; sizeLabel.text = "${zoom.value} px"
            updateLayout(); leftGrid.repaint(); rightGrid.repaint()
            leftGrid.select(leftGrid.selectedIndex); rightGrid.select(rightGrid.selectedIndex)
            saveOptions()
        }
        hideUnchanged.addActionListener { filter(); saveOptions() }
        outlineColors.addActionListener {
            leftGrid.showOutlineChanges = outlineColors.isSelected; rightGrid.showOutlineChanges = outlineColors.isSelected
            saveOptions()
        }
        previous.addActionListener { goChange(false) }; next.addActionListener { goChange(true) }
        inspect.addActionListener { inspectSelection() }
        updateSelection()
    }

    private fun saveOptions() = rememberOptions(FontDiffOptions(zoom.value, hideUnchanged.isSelected, outlineColors.isSelected))

    fun install(left: FontDocument?, right: FontDocument?, model: FontComparison) {
        installing = true
        try {
            val incoming = listOf(left, right)
            for (side in 0..1) {
                val face = if (side == 0) model.left else model.right
                if (documents.getOrNull(side) !== incoming[side] || faces[side].itemCount == 0) {
                    faces[side].removeAllItems()
                    incoming[side]?.faces?.forEach(faces[side]::addItem)
                    faces[side].selectedItem = face
                }
                // Keep header heights equal even if only one font is a collection.
                faces[side].isVisible = incoming.any { (it?.faces?.size ?: 0) > 1 }
                faces[side].isEnabled = (incoming[side]?.faces?.size ?: 0) > 1
                headings[side].text = face?.let { "${it.family} · ${it.style} · ${it.glyphs.size} glyphs" } ?: "Font not present"
            }
            documents = incoming
        } finally { installing = false }
        comparison = model
        summary.text = model.summary()
        hideUnchanged.text = "Hide unchanged (${model.pairs.count { it.change == GlyphChange.SAME }})"
        leftGrid.comparison = model; rightGrid.comparison = model
        filter(); updateLayout()
    }

    fun showError(message: String) {
        clear()
        summary.text = "Unable to compare fonts: $message"
        summary.toolTipText = summary.text
    }

    fun showLoading() { summary.text = "Comparing font outlines and metrics…" }

    fun clear() {
        comparison = null; selectedPair = null; documents = emptyList()
        leftGrid.comparison = null; rightGrid.comparison = null
        leftGrid.visiblePairs = emptyList(); rightGrid.visiblePairs = emptyList()
        installing = true
        try { faces.forEach { it.removeAllItems(); it.isEnabled = false } } finally { installing = false }
        updateSelection()
    }

    private fun filter() {
        val old = selectedPair
        val pairs = comparison?.pairs.orEmpty().filter { !hideUnchanged.isSelected || it.change != GlyphChange.SAME }
        leftGrid.visiblePairs = pairs; rightGrid.visiblePairs = pairs
        // Preserve the semantic pair across filtering; IDs alone are not stable identities.
        val selected = pairs.indexOfFirst { it.left?.id == old?.left?.id && it.right?.id == old?.right?.id }
        select(if (selected >= 0) selected else pairs.indexOfFirst { it.change != GlyphChange.SAME }.takeIf { it >= 0 } ?: if (pairs.isEmpty()) -1 else 0)
        updateSelection()
    }

    fun select(index: Int) {
        selectedPair = leftGrid.visiblePairs.getOrNull(index)
        leftGrid.select(index); rightGrid.select(index)
        updateSelection()
    }

    fun canGoChange(forward: Boolean): Boolean = changeIndex(forward) >= 0
    private fun changeIndex(forward: Boolean): Int {
        val pairs = leftGrid.visiblePairs
        return if (forward) pairs.indices.firstOrNull { it > leftGrid.selectedIndex && pairs[it].change != GlyphChange.SAME } ?: -1
        else pairs.indices.reversed().firstOrNull { it < leftGrid.selectedIndex && pairs[it].change != GlyphChange.SAME } ?: -1
    }
    fun goChange(forward: Boolean) { changeIndex(forward).takeIf { it >= 0 }?.let(::select) }

    fun updateLayout() {
        val widths = listOf(leftScroll.viewport.width, rightScroll.viewport.width).filter { it > 0 }
        if (widths.size != 2) return
        val oldColumns = shared.columns
        val oldHeight = shared.cellHeight
        val anchor = leftScroll.verticalScrollBar.value / oldHeight * oldColumns
        shared.update(widths.min())
        leftGrid.revalidate(); rightGrid.revalidate()
        leftGrid.repaint(); rightGrid.repaint()
        if (oldColumns != shared.columns) {
            leftScroll.verticalScrollBar.value = anchor / shared.columns * shared.cellHeight
        }
    }

    private fun updateSelection() {
        val model = comparison
        val pair = selectedPair
        detail.text = if (pair == null || model == null) "Select a glyph to compare" else buildString {
            appendLine("${pair.label} · ${pair.description}")
            fun describe(face: FontFace?, glyph: Glyph?) = if (face == null || glyph == null) "Not present" else
                "${glyph.description} · gid${glyph.id} · advance ${"%.3f".format(GlyphGeometry.advance(face, glyph) / GlyphGeometry.EM)} em"
            appendLine("Before: ${describe(model.left, pair.left)}")
            append("After: ${describe(model.right, pair.right)}")
        }
        detail.caretPosition = 0
        copyButtons.forEach { (button, action) ->
            val glyph = if (action.first) pair?.right else pair?.left
            button.isEnabled = glyph != null && (action.second == "Glyph ID" || glyph.canCopy)
            button.toolTipText = glyph?.defaultCopyMapping?.features?.takeIf { it.isNotEmpty() }?.let { "Copies input text; enable ${it.joinToString(", ")}." }
        }
        previous.isEnabled = canGoChange(false); next.isEnabled = canGoChange(true)
        inspect.isEnabled = pair != null
    }

    private fun copy(right: Boolean, format: String) {
        val glyph = (if (right) selectedPair?.right else selectedPair?.left) ?: return
        val mapping = glyph.defaultCopyMapping
        val text = if (format == "Glyph ID") glyph.id.toString() else {
            if (mapping == null) return
            when (format) { "\\u…" -> mapping.escape; "U+…" -> mapping.notation; else -> mapping.character }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun inspectSelection() {
        val model = comparison ?: return
        val pair = selectedPair ?: return
        val panel = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(8))).apply { border = JBUI.Borders.empty(16) }
        val overlay = JBCheckBox("Overlay both outlines", true)
        val canvas = FontDiffInspector(model, pair)
        overlay.addActionListener { canvas.overlay = overlay.isSelected; canvas.repaint() }
        panel.add(overlay, BorderLayout.NORTH); panel.add(canvas, BorderLayout.CENTER)
        panel.add(JBLabel("Red: before only · Green: after only · Neutral: shared outline"), BorderLayout.SOUTH)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, overlay)
            .setTitle("${pair.label} · ${pair.description}").setRequestFocus(true).setMovable(true).setResizable(true).createPopup()
        owner?.let { Disposer.register(it, popup) }
        popup.showInCenterOf(this)
    }
}

class FontDiffInspector(private val model: FontComparison, private val pair: GlyphPair) : JComponent() {
    var overlay = true
    private val old = GlyphGeometry.outline(model.left, pair.left)
    private val new = GlyphGeometry.outline(model.right, pair.right)
    private val differences = GlyphGeometry.differences(old, new)
    private val bounds = Rectangle2D.Double().apply {
        add(old.bounds2D); add(new.bounds2D)
        add(GlyphGeometry.advance(model.left, pair.left).toDouble(), 0.0)
        add(GlyphGeometry.advance(model.right, pair.right).toDouble(), 0.0)
    }
    init { preferredSize = JBUI.size(640, 320) }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.color = UIManager.getColor("EditorPane.background") ?: JBColor.PanelBackground
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            fun paint(area: Rectangle, side: Int?) {
                val transform = glyphTransform(bounds, area, JBUI.scale(260).toDouble())
                val ink = g.create() as Graphics2D
                try {
                    ink.transform(transform)
                    ink.stroke = BasicStroke((1 / transform.scaleX).toFloat())
                    ink.color = JBColor.border()
                    ink.draw(java.awt.geom.Line2D.Double(bounds.minX, 0.0, bounds.maxX, 0.0))
                    ink.draw(java.awt.geom.Line2D.Double(0.0, bounds.minY, 0.0, bounds.maxY))
                    if (side == null) {
                        ink.color = JBColor.foreground(); ink.fill(differences.first)
                        ink.color = FontDiffColors.removed; ink.fill(differences.second)
                        ink.color = FontDiffColors.added; ink.fill(differences.third)
                    } else {
                        ink.color = JBColor.foreground(); ink.fill(if (side == 0) old else new)
                    }
                    for (index in 0..1) if (side == null || index == side) {
                        val glyph = if (index == 0) pair.left else pair.right
                        val face = if (index == 0) model.left else model.right
                        if (glyph != null && face != null) {
                            val advance = GlyphGeometry.advance(face, glyph).toDouble()
                            ink.color = if (index == 0) FontDiffColors.removed else FontDiffColors.added
                            ink.draw(java.awt.geom.Line2D.Double(advance, bounds.minY, advance, bounds.maxY))
                        }
                    }
                } finally { ink.dispose() }
            }
            if (overlay) paint(Rectangle(20, 30, width - 40, height - 50), null)
            else {
                paint(Rectangle(20, 30, width / 2 - 40, height - 50), 0)
                paint(Rectangle(width / 2 + 20, 30, width / 2 - 40, height - 50), 1)
                g.color = JBColor.foreground(); g.drawString("Before", 20, 20); g.drawString("After", width / 2 + 20, 20)
            }
        } finally { g.dispose() }
    }
}
