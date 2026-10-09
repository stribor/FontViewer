package org.stribor.fontpreview

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.io.path.readBytes
import kotlin.io.path.exists

/** Run via build-local.py --test or the Gradle regressionTest task. */
fun main(args: Array<String>) {
    val fixtures = Path.of(args.firstOrNull() ?: "src/test/resources")
    check(UnicodeText.escape(0xE91B) == "\\ue91b")
    check(UnicodeText.notation(0xE91B) == "U+E91B")
    check(UnicodeText.character(0xE91B).codePointAt(0) == 0xE91B)
    check(UnicodeText.escape(0x1F680) == "\\ud83d\\ude80")
    check(UnicodeText.notation(0x1F680) == "U+1F680")
    check(UnicodeText.character(0x1F680).codePointCount(0, 2) == 1)
    val ttf = FontDocument.load(fixtures.resolve("icons.ttf").readBytes())
    val otf = FontDocument.load(fixtures.resolve("icons.otf").readBytes())
    val woff = FontDocument.load(fixtures.resolve("icons.woff").readBytes())
    val collection = FontDocument.load(fixtures.resolve("faces.ttc").readBytes())
    for (document in listOf(ttf, otf, woff)) {
        val face = document.faces.single()
        check(face.family == "Stribor Fixture")
        check(face.glyphs.size == 5)
        check(face.unitsPerEm == 1000)
        check(face.glyphs[2].codePoints.contentEquals(intArrayOf(0xE91B, 0xE91C)))
        check(face.glyphs[3].codePoints.contentEquals(intArrayOf(0x1F680)))
        check(face.glyphs[4].codePoints.isEmpty())
        check(face.glyphs[1].codePoints.contentEquals(intArrayOf(0x20)))
    }
    check(collection.faces.map { it.style } == listOf("Regular", "Bold"))
    check(collection.faces.all { it.glyphs[2].codePoints.first() == 0xE91B })
    val substitutions = FontDocument.load(fixtures.resolve("substitutions.ttf").readBytes()).faces.single()
    val smallcap = substitutions.glyphs[9]
    check(smallcap.codePoints.isEmpty()) // A small cap must never be given a fabricated code point.
    check(smallcap.canCopy)
    check(smallcap.sources.any { it.character == "A" && it.features == listOf("c2sc") })
    check(smallcap.sources.any { it.character == "a" && it.features == listOf("smcp") })
    check(substitutions.glyphs[10].sources.single().let { it.character == "A" && it.features == listOf("salt") })
    val ligature = substitutions.glyphs[11].defaultCopyMapping!!
    check(ligature.character == "fi" && ligature.escape == "\\u0066\\u0069" && ligature.notation == "U+0066 U+0069")
    check(ligature.features == listOf("liga"))
    check(substitutions.glyphs[12].sources.single().let { it.character == "i" && it.features == listOf("ss01") })
    check(!substitutions.glyphs[4].canCopy)
    check(substitutions.glyphs[5].defaultCopyMapping!!.features.isEmpty())
    val robotoPath = args.getOrNull(1)?.let { Path.of(it) }
    if (robotoPath != null) {
        val roboto = FontDocument.load(robotoPath.readBytes()).faces.single()
        val reportedGlyph = roboto.glyphs[442]
        check(reportedGlyph.codePoints.isEmpty())
        check(reportedGlyph.sources.any { it.character == "A" && it.features == listOf("c2sc") })
        check(reportedGlyph.sources.any { it.character == "a" && it.features == listOf("smcp") })
        check(reportedGlyph.defaultCopyMapping!!.escape in setOf("\\u0041", "\\u0061"))
        println("Roboto Italic regression: glyph 442 copies A/c2sc and a/smcp")
    }
    for (bytes in listOf(ByteArray(0), ByteArray(12), fixtures.resolve("icons.ttf").readBytes().copyOf(64), fixtures.resolve("icons.woff").readBytes().copyOf(60))) {
        check(runCatching { FontDocument.load(bytes) }.isFailure) { "Malformed data was accepted" }
    }
    val canceled = runCatching { FontDocument.load(fixtures.resolve("icons.ttf").readBytes()) { throw CancellationException() } }.exceptionOrNull()
    check(canceled is CancellationException)
    val large = FontDocument.load(fixtures.resolve("large.ttf").readBytes())
    check(large.faces.single().glyphs.size == 20005)
    check(large.faces.single().glyphs.last().codePoints.single() == 0x10000 + 19999)
    // Real-world smoke checks are optional so the suite stays portable.
    for (systemFont in listOf("/System/Library/Fonts/Menlo.ttc", "/System/Library/Fonts/Helvetica.ttc", "/System/Library/Fonts/Supplemental/AppleGothic.ttf")) {
        val path = Path.of(systemFont)
        if (path.exists()) {
            val realFont = FontDocument.load(path.readBytes())
            check(realFont.faces.isNotEmpty())
            check(realFont.faces.all { it.mappedGlyphCount > 0 })
            println("Real font: ${path.fileName}, ${realFont.faces.size} faces, ${realFont.faces.sumOf { it.glyphs.size }} glyphs")
        }
    }
    SwingUtilities.invokeAndWait {
        val grid = GlyphGrid()
        grid.face = ttf.faces.single()
        grid.setSize(800, 600)
        check(grid.componentCount == 0)
        grid.select(2)
        check(grid.selectedGlyph?.unicode == "U+E91B")
        val image = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { grid.paint(it); it.dispose() }
        val ink = (0 until 110).sumOf { y -> (220 until 320).count { x ->
            val color = Color(image.getRGB(x, y))
            color.red < 100 && color.green < 100 && color.blue < 100
        } }
        check(ink > 100) { "Glyph outline did not render" }
        val output = fixtures.parent.parent.parent.resolve("build/verification")
        output.toFile().mkdirs()
        ImageIO.write(image, "png", output.resolve("glyph-grid.png").toFile())
        grid.previewSize = 120
        check(grid.previewSize == 120)
        grid.face = large.faces.single()
        grid.setSize(800, grid.preferredSize.height)
        grid.select(20004)
        check(grid.selectedGlyph?.id == 20004)
        val viewport = BufferedImage(800, 300, BufferedImage.TYPE_INT_RGB)
        viewport.createGraphics().also { graphics ->
            val bottom = grid.height - 300
            graphics.translate(0, -bottom)
            graphics.clip = Rectangle(0, bottom, 800, 300)
            grid.paint(graphics)
            graphics.dispose()
        }
        check(grid.componentCount == 0)
        check(grid.scrollableTracksViewportWidth)
        ImageIO.write(viewport, "png", output.resolve("large-grid-bottom.png").toFile())
    }
    runFontDiffChecks(fixtures, ttf, substitutions, large.faces.single())
    println("Passed: Unicode copies, GSUB small caps/alternates/ligatures/extensions, TTF/OTF/WOFF, TTC faces, aliases, supplementary mappings, unmapped glyphs, malformed fonts, cancellation, native rendering and 20,005-glyph viewport.")
}
