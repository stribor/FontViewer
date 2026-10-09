package org.stribor.fontpreview

import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Container
import java.awt.Rectangle
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.io.path.readBytes

fun runFontDiffChecks(fixtures: Path, original: FontDocument, substitutions: FontFace, large: FontFace) {
    fun load(name: String) = FontDocument.load(fixtures.resolve(name).readBytes())
    val before = original.faces.single()
    val afterDocument = load("icons-diff.ttf")
    val after = afterDocument.faces.single()
    val reordered = load("icons-reordered.ttf").faces.single()
    val same = FontComparison.compare(before, before)
    check(same.pairs.size == before.glyphs.size && same.pairs.all { it.change == GlyphChange.SAME })
    check(FontComparison.compare(before, load("icons.woff").faces.single()).pairs.all { it.change == GlyphChange.SAME })
    val moved = FontComparison.compare(before, reordered)
    check(moved.pairs.all { it.change == GlyphChange.SAME }) { moved.pairs.joinToString { it.description } }
    check(moved.pairs.single { it.left?.id == 2 }.let { it.right?.id == 3 && it.match == GlyphMatch.UNICODE })
    val scaled = FontComparison.compare(before, load("icons-scaled.ttf").faces.single())
    check(scaled.pairs.all { it.change == GlyphChange.SAME }) { "Changing units-per-em altered normalized geometry: ${scaled.summary()}" }
    val variants = FontComparison.compare(substitutions, load("substitutions-reordered.ttf").faces.single())
    check(variants.pairs.all { it.change == GlyphChange.SAME })
    check(variants.pairs.single { it.left?.id == 9 }.let { it.right?.id == 10 && it.match == GlyphMatch.INPUT })
    val changed = FontComparison.compare(before, after)
    check(changed.pairs.count { it.change == GlyphChange.ADDED } == 1)
    check(changed.pairs.count { it.change == GlyphChange.REMOVED } == 1)
    check(changed.pairs.count { it.change == GlyphChange.MODIFIED } == 2)
    check(changed.pairs.single { it.left?.id == 1 }.let { it.metricsChanged && !it.outlineChanged }) { "An empty space's advance change must remain visible" }
    check(changed.pairs.single { it.left?.id == 2 }.outlineChanged)
    check(changed.pairs.single { it.left?.id == 4 }.match == GlyphMatch.ID)
    check(FontComparison.compare(null, before).pairs.all { it.change == GlyphChange.ADDED && it.left == null })
    check(FontComparison.compare(before, null).pairs.all { it.change == GlyphChange.REMOVED && it.right == null })
    val aliasesSplit = before.copy(glyphs = before.glyphs.map { glyph -> when (glyph.id) {
        2 -> glyph.copy(codePoints = intArrayOf(0xE91B))
        4 -> glyph.copy(codePoints = intArrayOf(0xE91C))
        else -> glyph
    } })
    val aliasComparison = FontComparison.compare(before, aliasesSplit)
    check(aliasComparison.pairs.single { it.left?.id == 2 }.mappingChanged)
    check(aliasComparison.pairs.mapNotNull { it.right?.id }.distinct().size == aliasesSplit.glyphs.size)
    val canceled = runCatching { FontComparison.compare(before, after) { throw CancellationException() } }.exceptionOrNull()
    check(canceled is CancellationException)
    val rectangle = Rectangle(0, 0, 20, 30)
    val differentlyOrdered = Path2D.Double().apply { moveTo(20.0, 30.0); lineTo(0.0, 30.0); lineTo(0.0, 0.0); lineTo(20.0, 0.0); closePath() }
    check(!GlyphGeometry.changed(rectangle, differentlyOrdered)) { "Equivalent outlines with different contour order must compare equal" }
    val delta = GlyphGeometry.differences(rectangle, Rectangle(10, 0, 20, 30))
    check(delta.first.bounds == Rectangle(10, 0, 10, 30))
    check(delta.second.bounds == Rectangle(0, 0, 10, 30) && delta.third.bounds == Rectangle(20, 0, 10, 30))
    check(Area(delta.first).apply { add(delta.second); exclusiveOr(Area(rectangle)) }.isEmpty)
    val big = FontComparison.compare(large, large)
    check(big.pairs.size == 20005 && big.pairs.all { it.change == GlyphChange.SAME })

    fun content(name: String) = object : UserDataHolderBase(), FileContent {
        private val virtualFile = LightVirtualFile(name, FontFileType, "")
        override fun getFile() = virtualFile
        override fun getContentType() = FontFileType
    }
    val a = content("before.TTF"); val b = content("after.woff")
    fun request(left: com.intellij.diff.contents.DiffContent, right: com.intellij.diff.contents.DiffContent) = SimpleDiffRequest("Fonts", left, right, "Before", "After")
    check(FontDiffTool.accepts(request(a, b)))
    check(FontDiffTool.accepts(request(a, EmptyContent())))
    check(FontDiffTool.accepts(request(EmptyContent(), b)))
    check(!FontDiffTool.accepts(request(EmptyContent(), EmptyContent())))
    check(!FontDiffTool.accepts(request(a, content("notes.txt"))))
    check(!FontDiffTool.accepts(SimpleDiffRequest("Merge", a, b, a, "Base", "Left", "Right")))

    SwingUtilities.invokeAndWait {
        val panel = FontDiffPanel(listOf("b7127ebf · icons.ttf", "94989d78 · icons.ttf"))
        panel.install(original, afterDocument, changed)
        panel.setSize(1200, 740)
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        repeat(3) { layout(panel); panel.updateLayout() }
        val columns = panel.shared.columns
        check(columns > 1)
        check(panel.leftGrid.preferredSize.width == panel.rightGrid.preferredSize.width)
        panel.rightGrid.onSelect(2)
        check(panel.leftGrid.selectedIndex == 2 && panel.rightGrid.selectedIndex == 2)
        val addedIndex = panel.leftGrid.visiblePairs.indexOfFirst { it.change == GlyphChange.ADDED }
        panel.leftGrid.onSelect(addedIndex) // The absent side still selects the counterpart.
        check(panel.selectedPair?.left == null && panel.selectedPair?.right != null)
        check(panel.rightGrid.selectedIndex == addedIndex)
        panel.hideUnchanged.doClick()
        check(panel.leftGrid.visiblePairs.size == 4 && panel.rightGrid.visiblePairs === panel.leftGrid.visiblePairs)
        check(panel.selectedPair?.change == GlyphChange.ADDED)
        panel.select(0)
        check(!panel.canGoChange(false) && panel.canGoChange(true))
        panel.goChange(true); check(panel.leftGrid.selectedIndex == 1 && panel.rightGrid.selectedIndex == 1)
        panel.zoom.value = 120
        repeat(3) { layout(panel); panel.updateLayout() }
        check(panel.shared.previewSize == 120 && panel.shared.columns < columns)
        panel.outlineColors.doClick()
        check(!panel.leftGrid.showOutlineChanges && !panel.rightGrid.showOutlineChanges)
        panel.outlineColors.doClick()
        val output = fixtures.parent.parent.parent.resolve("build/verification")
        output.toFile().mkdirs()
        fun render(name: String, component: javax.swing.JComponent, width: Int, height: Int) {
            component.setSize(width, height)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().also { component.paint(it); it.dispose() }
            ImageIO.write(image, "png", output.resolve(name).toFile())
        }
        render("font-diff-filtered.png", panel, 1200, 740)
        panel.hideUnchanged.doClick(); panel.zoom.value = 48
        repeat(3) { layout(panel); panel.updateLayout() }
        render("font-diff.png", panel, 1200, 740)
        val pair = changed.pairs.single { it.outlineChanged }
        val inspector = FontDiffInspector(changed, pair)
        render("font-diff-overlay.png", inspector, 640, 320)
        inspector.overlay = false
        render("font-diff-inspector.png", inspector, 640, 320)
        val transform = glyphTransform(changed.bounds, Rectangle(0, 0, 120, 100), 48.0)
        val origin = transform.transform(java.awt.geom.Point2D.Double(0.0, 0.0), null)
        check(origin.y > 0 && origin.y <= 100)
        panel.install(original, original, same); panel.hideUnchanged.doClick()
        check(panel.leftGrid.visiblePairs.isEmpty() && panel.selectedPair == null)
        panel.hideUnchanged.doClick()
        val largeDocument = original.copy(faces = listOf(large))
        panel.install(largeDocument, largeDocument, big)
        repeat(3) { layout(panel); panel.updateLayout() }
        panel.leftScroll.verticalScrollBar.value = 400
        check(panel.rightScroll.verticalScrollBar.value == 400)
        panel.rightGrid.onSelect(big.pairs.lastIndex)
        check(panel.leftGrid.selectedIndex == big.pairs.lastIndex)
        check(panel.leftScroll.verticalScrollBar.value == panel.rightScroll.verticalScrollBar.value)
        check(panel.leftGrid.componentCount == 0 && panel.rightGrid.componentCount == 0)
        panel.clear()
        check(panel.leftGrid.comparison == null && panel.rightGrid.visiblePairs.isEmpty())

        // User changes in one comparison must initialize a different comparison's controls and grid.
        val preferences = FontDiffPreferences()
        val first = FontDiffPanel(listOf("Before", "After"), options = preferences.snapshot(), rememberOptions = preferences::remember)
        first.install(original, afterDocument, changed)
        first.zoom.value = 96
        first.hideUnchanged.doClick()
        first.outlineColors.doClick()
        check(preferences.snapshot() == FontDiffOptions(96, true, false))
        val second = FontDiffPanel(listOf("Another before", "Another after"), options = preferences.snapshot(), rememberOptions = preferences::remember)
        second.install(original, afterDocument, changed)
        check(second.zoom.value == 96 && second.shared.previewSize == 96)
        check(second.hideUnchanged.isSelected && second.leftGrid.visiblePairs.size == 4)
        check(!second.outlineColors.isSelected && !second.leftGrid.showOutlineChanges && !second.rightGrid.showOutlineChanges)

        // Exercise the XML state contract used by the IDE to restore preferences after restart.
        val xml = XmlSerializer.serialize(preferences.state)
        val restored = FontDiffPreferences()
        restored.loadState(XmlSerializer.deserialize(xml, FontDiffOptions::class.java))
        check(restored.snapshot() == FontDiffOptions(96, true, false))
        val restarted = FontDiffPanel(listOf("Before", "After"), options = restored.snapshot(), rememberOptions = restored::remember)
        restarted.install(original, afterDocument, changed)
        check(restarted.zoom.value == 96 && restarted.hideUnchanged.isSelected && !restarted.outlineColors.isSelected)
        second.zoom.value = 80
        first.clear() // Disposing an older viewer must not overwrite the latest user choice.
        check(preferences.snapshot() == FontDiffOptions(80, true, false))
        restored.loadState(FontDiffOptions(999, false, true))
        check(restored.snapshot().zoom == 160)
        second.clear(); restarted.clear()
    }
    println("Passed font diff: routing, Unicode/GSUB matches across reordered IDs, equivalent contours, em normalization, outline/space-metric changes, additions/deletions, aliases, cancellation, linked selection/scroll/zoom, filtering, navigation, overlays, persistent preferences and 20,005-glyph comparisons.")
}
