package org.stribor.fontpreview

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import java.awt.font.GlyphVector
import javax.swing.*
import kotlin.math.*

/** A single scrollable canvas: components and cached outlines never grow with the repertoire. */
class GlyphGrid : JComponent(), Scrollable {
    var face: FontFace? = null
        set(value) {
            field = value
            selectedIndex = if (value == null) -1 else 0
            vectors.clear()
            revalidate()
            repaint()
            onSelection(selectedGlyph)
        }
    var previewSize = 48
        set(value) { field = value.coerceIn(16, 160); vectors.clear(); revalidate(); repaint() }
    var selectedIndex = -1
        private set
    val selectedGlyph: Glyph? get() = face?.glyphs?.getOrNull(selectedIndex)
    var onSelection: (Glyph?) -> Unit = {}
    var onInspect: () -> Unit = {}
    var onContextMenu: (Point) -> Unit = {}
    var onCopy: () -> Unit = {}
    private val vectors = object : LinkedHashMap<Int, GlyphVector>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, GlyphVector>?) = size > 512
    }
    private val cellWidth: Int get() = JBUI.scale(max(96, previewSize + 48))
    private val cellHeight: Int get() = JBUI.scale(max(100, previewSize + 54))
    private val columns: Int get() = max(1, width / cellWidth)
    private val actualCellWidth: Int get() = max(cellWidth, width / columns)

    init {
        isOpaque = true
        isFocusable = true
        toolTipText = ""
        accessibleContext?.accessibleName = "Font glyph repertoire"
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) { revalidate(); repaint() }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                val index = indexAt(e.point)
                if (index != null) select(index)
                if (e.isPopupTrigger && index != null) onContextMenu(e.point)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger && indexAt(e.point) != null) onContextMenu(e.point)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) && indexAt(e.point) != null) onInspect()
            }
        })
        bind("LEFT") { move(-1) }
        bind("RIGHT") { move(1) }
        bind("UP") { move(-columns) }
        bind("DOWN") { move(columns) }
        bind("HOME") { select(0) }
        bind("END") { select((face?.glyphs?.size ?: 1) - 1) }
        bind("PAGE_UP") { move(-columns * max(1, visibleRect.height / cellHeight)) }
        bind("PAGE_DOWN") { move(columns * max(1, visibleRect.height / cellHeight)) }
        bind("ENTER") { onInspect() }
        bind("shift F10") { onContextMenu(cellBounds(selectedIndex).location) }
        val copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C, if (GraphicsEnvironment.isHeadless()) InputEvent.CTRL_DOWN_MASK else Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        inputMap.put(copyKey, "copyCharacter")
        actionMap.put("copyCharacter", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = onCopy()
        })
    }

    fun select(index: Int) {
        val count = face?.glyphs?.size ?: 0
        if (count == 0) return
        selectedIndex = index.coerceIn(0, count - 1)
        onSelection(selectedGlyph)
        scrollRectToVisible(cellBounds(selectedIndex))
        repaint()
    }

    private fun move(delta: Int) = select(selectedIndex + delta)
    private fun bind(key: String, action: () -> Unit) {
        inputMap.put(KeyStroke.getKeyStroke(key), key)
        actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
    }
    private fun indexAt(point: Point): Int? {
        if (point.x < 0 || point.x >= width || point.y < 0) return null
        val col = point.x / actualCellWidth
        if (col >= columns) return null
        return (point.y / cellHeight * columns + col).takeIf { it in 0 until (face?.glyphs?.size ?: 0) }
    }
    private fun cellBounds(index: Int) = Rectangle(index % columns * actualCellWidth, index / columns * cellHeight, actualCellWidth, cellHeight)

    override fun getToolTipText(event: MouseEvent): String? = indexAt(event.point)?.let { index ->
        val glyph = face!!.glyphs[index]
        "${glyph.description} · Glyph ${glyph.id} · Double-click to inspect"
    }
    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.color = UIManager.getColor("EditorPane.background") ?: JBColor.PanelBackground
            g.fillRect(0, 0, width, height)
            val current = face ?: return
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
            val first = max(0, clip.y / cellHeight * columns)
            val last = min(current.glyphs.size, ((clip.y + clip.height) / cellHeight + 1) * columns)
            val renderFont = current.font.deriveFont(JBUI.scale(previewSize).toFloat())
            val labelFont = (UIManager.getFont("Label.font") ?: font ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(JBUI.scale(11).toFloat())
            for (index in first until last) {
                val bounds = cellBounds(index)
                val selected = index == selectedIndex
                if (selected) {
                    g.color = UIManager.getColor("List.selectionBackground") ?: JBColor(0xDCEBFC, 0x2F4563)
                    g.fillRoundRect(bounds.x + JBUI.scale(6), bounds.y + JBUI.scale(6), bounds.width - JBUI.scale(12), bounds.height - JBUI.scale(12), JBUI.scale(12), JBUI.scale(12))
                    if (hasFocus()) {
                        g.color = JBColor(0x3574F0, 0x6A9AF7)
                        g.drawRoundRect(bounds.x + JBUI.scale(6), bounds.y + JBUI.scale(6), bounds.width - JBUI.scale(13), bounds.height - JBUI.scale(13), JBUI.scale(12), JBUI.scale(12))
                    }
                }
                g.color = if (selected) UIManager.getColor("List.selectionForeground") ?: JBColor.foreground()
                          else UIManager.getColor("Label.foreground") ?: JBColor.foreground()
                val glyph = current.glyphs[index]
                val vector = vectors.getOrPut(glyph.id) { renderFont.createGlyphVector(g.fontRenderContext, intArrayOf(glyph.id)) }
                drawCenteredGlyph(g, vector, Rectangle(bounds.x + JBUI.scale(12), bounds.y + JBUI.scale(10), bounds.width - JBUI.scale(24), bounds.height - JBUI.scale(36)))
                g.font = labelFont
                if (!selected) g.color = UIManager.getColor("Label.disabledForeground") ?: JBColor.GRAY
                val label = if (glyph.codePoints.isNotEmpty()) glyph.unicode else glyph.sources.firstOrNull()?.let {
                    "${it.character.take(3)} · ${it.features.first()}"
                } ?: "gid${glyph.id}"
                g.drawString(label, bounds.x + (bounds.width - g.fontMetrics.stringWidth(label)) / 2, bounds.y + bounds.height - JBUI.scale(16))
            }
        } finally { g.dispose() }
    }

    override fun getPreferredSize(): Dimension = Dimension(max(cellWidth, width), max(1, ceil((face?.glyphs?.size ?: 0).toDouble() / columns).toInt()) * cellHeight)
    override fun getPreferredScrollableViewportSize() = Dimension(JBUI.scale(800), JBUI.scale(600))
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = max(cellHeight, visibleRect.height - cellHeight)
}

internal fun drawCenteredGlyph(g: Graphics2D, vector: GlyphVector, area: Rectangle) {
    val outline = vector.getGlyphOutline(0)
    val bounds = outline.bounds2D
    if (bounds.isEmpty) return // Spaces and non-inking glyphs still retain their selectable cells.
    val scale = min(1.0, min(area.width / bounds.width, area.height / bounds.height))
    val transform = java.awt.geom.AffineTransform.getTranslateInstance(
        area.centerX - bounds.centerX * scale, area.centerY - bounds.centerY * scale
    ).apply { scale(scale, scale) }
    g.fill(transform.createTransformedShape(outline))
}
