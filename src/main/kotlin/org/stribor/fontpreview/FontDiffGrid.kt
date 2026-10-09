package org.stribor.fontpreview

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import javax.swing.*
import kotlin.math.*

object FontDiffColors {
    val removed = JBColor(0xC43240, 0xF08080)
    val added = JBColor(0x187B45, 0x70CF98)
    val modified = JBColor(0x976700, 0xE7BE65)
    fun forChange(change: GlyphChange): Color = when (change) {
        GlyphChange.ADDED -> added; GlyphChange.REMOVED -> removed
        GlyphChange.MODIFIED -> modified; GlyphChange.SAME -> JBColor.GRAY
    }
}

/** Shared by both canvases: column count cannot diverge when the panels have different widths. */
class FontDiffLayout {
    var previewSize = 48
    var columns = 1
    var cellWidth = JBUI.scale(112)
    val cellHeight get() = JBUI.scale(max(116, previewSize + 76))
    fun update(width: Int) {
        val minimum = JBUI.scale(max(112, previewSize + 48))
        columns = max(1, width / minimum)
        cellWidth = max(minimum, width / columns)
    }
    fun bounds(index: Int) = Rectangle(index % columns * cellWidth, index / columns * cellHeight, cellWidth, cellHeight)
}

class FontDiffGrid(val rightSide: Boolean, val shared: FontDiffLayout) : JComponent(), Scrollable {
    var comparison: FontComparison? = null
        set(value) { field = value; shapes.clear(); revalidate(); repaint() }
    var visiblePairs: List<GlyphPair> = emptyList()
        set(value) { field = value; selectedIndex = -1; revalidate(); repaint() }
    var showOutlineChanges = true
        set(value) { field = value; repaint() }
    var selectedIndex = -1
        private set
    var onSelect: (Int) -> Unit = {}
    var onInspect: () -> Unit = {}
    var onCopy: () -> Unit = {}
    private val shapes = object : LinkedHashMap<GlyphPair, Pair<Shape, Triple<Area, Area, Area>?>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlyphPair, Pair<Shape, Triple<Area, Area, Area>?>>?) = size > 512
    }

    init {
        isOpaque = true; isFocusable = true; toolTipText = ""
        accessibleContext?.accessibleName = if (rightSide) "New font glyph comparison" else "Old font glyph comparison"
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                indexAt(e.point)?.let(onSelect)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) && indexAt(e.point) != null) onInspect()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
        fun bind(key: String, action: () -> Unit) {
            inputMap.put(KeyStroke.getKeyStroke(key), key)
            actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = action() })
        }
        fun move(delta: Int) { if (visiblePairs.isNotEmpty()) onSelect((selectedIndex + delta).coerceIn(0, visiblePairs.lastIndex)) }
        bind("LEFT") { move(-1) }; bind("RIGHT") { move(1) }
        bind("UP") { move(-shared.columns) }; bind("DOWN") { move(shared.columns) }
        bind("HOME") { if (visiblePairs.isNotEmpty()) onSelect(0) }
        bind("END") { if (visiblePairs.isNotEmpty()) onSelect(visiblePairs.lastIndex) }
        bind("PAGE_UP") { move(-shared.columns * max(1, visibleRect.height / shared.cellHeight)) }
        bind("PAGE_DOWN") { move(shared.columns * max(1, visibleRect.height / shared.cellHeight)) }
        bind("ENTER") { onInspect() }
        val modifier = if (GraphicsEnvironment.isHeadless()) InputEvent.CTRL_DOWN_MASK else Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, modifier), "copy")
        actionMap.put("copy", object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = onCopy() })
    }

    fun select(index: Int, reveal: Boolean = true) {
        selectedIndex = index.takeIf { it in visiblePairs.indices } ?: -1
        if (reveal && selectedIndex >= 0) scrollRectToVisible(shared.bounds(selectedIndex))
        repaint()
    }

    private fun indexAt(point: Point): Int? {
        if (point.x < 0 || point.y < 0 || point.x >= shared.columns * shared.cellWidth) return null
        return (point.y / shared.cellHeight * shared.columns + point.x / shared.cellWidth).takeIf { it in visiblePairs.indices }
    }

    override fun getToolTipText(event: MouseEvent): String? = indexAt(event.point)?.let { index ->
        val pair = visiblePairs[index]
        val glyph = if (rightSide) pair.right else pair.left
        "${pair.label} · ${glyph?.let { "gid${it.id}" } ?: "Not present"} · ${pair.description} · Double-click to inspect"
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.color = UIManager.getColor("EditorPane.background") ?: JBColor.PanelBackground
            g.fillRect(0, 0, width, height)
            val model = comparison ?: return
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val clip = g.clipBounds ?: Rectangle(0, 0, width, height)
            val first = max(0, clip.y / shared.cellHeight * shared.columns)
            val last = min(visiblePairs.size, ((clip.y + clip.height) / shared.cellHeight + 1) * shared.columns)
            val labelFont = (UIManager.getFont("Label.font") ?: Font(Font.SANS_SERIF, Font.PLAIN, 12)).deriveFont(JBUI.scale(11).toFloat())
            for (index in first until last) {
                val pair = visiblePairs[index]
                val cell = shared.bounds(index)
                val glyph = if (rightSide) pair.right else pair.left
                if (index == selectedIndex) {
                    g.color = UIManager.getColor("List.selectionBackground") ?: JBColor(0xDCEBFC, 0x2F4563)
                    g.fillRoundRect(cell.x + 4, cell.y + 4, cell.width - 8, cell.height - 8, 10, 10)
                    g.color = JBColor(0x3574F0, 0x6A9AF7)
                    g.drawRoundRect(cell.x + 4, cell.y + 4, cell.width - 9, cell.height - 9, 10, 10)
                }
                if (pair.change != GlyphChange.SAME) {
                    g.color = FontDiffColors.forChange(pair.change)
                    g.fillRect(cell.x + JBUI.scale(7), cell.y + JBUI.scale(12), JBUI.scale(3), cell.height - JBUI.scale(24))
                }
                val area = Rectangle(cell.x + JBUI.scale(16), cell.y + JBUI.scale(10), cell.width - JBUI.scale(32), cell.height - JBUI.scale(52))
                val transform = glyphTransform(model.bounds, area, JBUI.scale(shared.previewSize).toDouble())
                g.color = JBColor.border()
                val baseline = transform.transform(java.awt.geom.Point2D.Double(0.0, 0.0), null).y.toInt()
                g.drawLine(area.x, baseline, area.x + area.width, baseline)
                if (glyph != null) {
                    val cached = shapes.getOrPut(pair) {
                        val old = GlyphGeometry.outline(model.left, pair.left)
                        val new = GlyphGeometry.outline(model.right, pair.right)
                        (if (rightSide) new else old) to if (pair.outlineChanged) GlyphGeometry.differences(old, new) else null
                    }
                    val paint = g.create() as Graphics2D
                    try {
                        paint.transform(transform)
                        paint.color = UIManager.getColor("Label.foreground") ?: JBColor.foreground()
                        val difference = cached.second
                        if (showOutlineChanges && difference != null) {
                            paint.fill(difference.first)
                            paint.color = if (rightSide) FontDiffColors.added else FontDiffColors.removed
                            paint.fill(if (rightSide) difference.third else difference.second)
                        } else {
                            if (pair.change == GlyphChange.ADDED || pair.change == GlyphChange.REMOVED) paint.color = FontDiffColors.forChange(pair.change)
                            paint.fill(cached.first)
                        }
                    } finally { paint.dispose() }
                }
                g.font = labelFont
                g.color = UIManager.getColor("Label.foreground") ?: JBColor.foreground()
                val label = if (glyph == null) "${pair.label} · absent" else
                    (if (glyph.codePoints.isNotEmpty()) glyph.unicode else "gid${glyph.id}")
                drawLabel(g, label, cell, cell.y + cell.height - JBUI.scale(30))
                g.color = if (pair.change == GlyphChange.SAME) JBColor.GRAY else FontDiffColors.forChange(pair.change)
                drawLabel(g, if (glyph == null) "Not present" else "gid${glyph.id} · ${pair.change.label}", cell, cell.y + cell.height - JBUI.scale(13))
            }
            if (visiblePairs.isEmpty()) {
                g.color = JBColor.foreground(); g.font = labelFont
                g.drawString("No glyph changes", JBUI.scale(20), JBUI.scale(32))
            }
        } finally { g.dispose() }
    }

    private fun drawLabel(g: Graphics2D, text: String, cell: Rectangle, y: Int) {
        var label = text
        while (label.length > 2 && g.fontMetrics.stringWidth(label) > cell.width - JBUI.scale(16)) label = label.dropLast(2) + "…"
        g.drawString(label, cell.x + (cell.width - g.fontMetrics.stringWidth(label)) / 2, y)
    }

    override fun getPreferredSize() = Dimension(shared.columns * shared.cellWidth, max(1, ceil(visiblePairs.size.toDouble() / shared.columns).toInt()) * shared.cellHeight)
    override fun getPreferredScrollableViewportSize() = Dimension(JBUI.scale(600), JBUI.scale(600))
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { preferredSize.height < it.height } ?: false
    override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(24)
    override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = max(shared.cellHeight, rect.height - shared.cellHeight)
}

/** The same transform is used for both sides; neither outline is independently recentered. */
fun glyphTransform(bounds: java.awt.geom.Rectangle2D, area: Rectangle, emPixels: Double): AffineTransform {
    val scale = min(emPixels / GlyphGeometry.EM, min(area.width / max(1.0, bounds.width), area.height / max(1.0, bounds.height)))
    return AffineTransform.getTranslateInstance(area.centerX - bounds.centerX * scale, area.centerY - bounds.centerY * scale).apply { scale(scale, scale) }
}
