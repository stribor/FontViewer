package org.stribor.fontpreview

import java.awt.Shape
import java.awt.font.FontRenderContext
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import kotlin.math.abs

enum class GlyphChange(val label: String) { SAME("Unchanged"), ADDED("Added"), REMOVED("Removed"), MODIFIED("Modified") }
enum class GlyphMatch(val label: String) { UNICODE("Unicode"), INPUT("OpenType input"), ID("Glyph ID fallback"), NONE("No counterpart") }

data class GlyphPair(
    val left: Glyph?, val right: Glyph?, val match: GlyphMatch, val change: GlyphChange,
    val outlineChanged: Boolean = false, val metricsChanged: Boolean = false, val mappingChanged: Boolean = false
) {
    val label: String get() = (left ?: right)!!.let { glyph ->
        if (glyph.codePoints.isNotEmpty()) glyph.unicode
        else glyph.sources.firstOrNull()?.let { "${it.notation} · ${it.features.joinToString(",")}" } ?: "gid${glyph.id}"
    }
    val description: String get() = buildString {
        append(change.label)
        if (change == GlyphChange.MODIFIED) append(": " + listOfNotNull(
            "outline".takeIf { outlineChanged }, "metrics".takeIf { metricsChanged }, "mapping".takeIf { mappingChanged }
        ).joinToString(", "))
        append(" · ${match.label}")
    }
}

/** All geometry uses one em size so fonts with different units-per-em remain comparable. */
object GlyphGeometry {
    const val EM = 1024f
    private val context = FontRenderContext(null, true, true)
    fun vector(face: FontFace, glyph: Glyph) = face.font.deriveFont(EM).createGlyphVector(context, intArrayOf(glyph.id))
    fun outline(face: FontFace?, glyph: Glyph?): Shape = if (face == null || glyph == null) Area() else vector(face, glyph).getGlyphOutline(0)
    fun advance(face: FontFace?, glyph: Glyph?): Float = if (face == null || glyph == null) 0f else vector(face, glyph).getGlyphMetrics(0).advanceX
    fun changed(left: Shape, right: Shape): Boolean {
        val a = left.getPathIterator(null); val b = right.getPathIterator(null)
        val ac = DoubleArray(6); val bc = DoubleArray(6)
        var equal = a.windingRule == b.windingRule
        while (equal && !a.isDone && !b.isDone) {
            val at = a.currentSegment(ac); val bt = b.currentSegment(bc)
            val count = when (at) { 0, 1 -> 2; 2 -> 4; 3 -> 6; else -> 0 }
            equal = at == bt && (0 until count).all { ac[it] == bc[it] }
            a.next(); b.next()
        }
        if (equal && a.isDone && b.isDone) return false
        return !Area(left).apply { exclusiveOr(Area(right)) }.isEmpty
    }
    fun differences(left: Shape, right: Shape): Triple<Area, Area, Area> {
        val common = Area(left).apply { intersect(Area(right)) }
        val removed = Area(left).apply { subtract(Area(right)) }
        val added = Area(right).apply { subtract(Area(left)) }
        return Triple(common, removed, added)
    }
}

data class FontComparison(val left: FontFace?, val right: FontFace?, val pairs: List<GlyphPair>, val bounds: Rectangle2D.Double) {
    fun summary() = GlyphChange.entries.joinToString(" · ") { change -> "${pairs.count { it.change == change }} ${change.label.lowercase()}" }

    companion object {
        fun compare(left: FontFace?, right: FontFace?, checkCanceled: () -> Unit = {}): FontComparison {
            val old = left?.glyphs.orEmpty()
            val new = right?.glyphs.orEmpty()
            val used = HashSet<Int>()
            val matches = HashMap<Int, Pair<Glyph, GlyphMatch>>()
            val unicode = HashMap<Int, Glyph>()
            val inputs = HashMap<String, MutableList<Glyph>>()
            new.forEach { glyph ->
                checkCanceled()
                glyph.codePoints.forEach { unicode[it] = glyph }
                glyph.sources.forEach { inputs.getOrPut(inputKey(it)) { mutableListOf() }.add(glyph) }
            }
            // Reserve Unicode matches before considering alternates or unstable glyph IDs.
            old.forEach { glyph ->
                checkCanceled()
                val candidates = glyph.codePoints.asSequence().mapNotNull { unicode[it] }.distinctBy { it.id }.toList()
                val candidate = candidates.filter { it.id !in used }.maxByOrNull { next ->
                    next.codePoints.count { it in glyph.codePoints }
                }
                if (candidate != null) { used.add(candidate.id); matches[glyph.id] = candidate to GlyphMatch.UNICODE }
            }
            old.filter { it.id !in matches && it.codePoints.isEmpty() }.forEach { glyph ->
                checkCanceled()
                val candidates = glyph.sources.flatMap { inputs[inputKey(it)].orEmpty() }
                    .filter { it.codePoints.isEmpty() && it.id !in used }.distinctBy { it.id }
                // Multiple alternates with the same input are ambiguous; do not guess a counterpart.
                val candidate = candidates.singleOrNull()
                if (candidate != null) { used.add(candidate.id); matches[glyph.id] = candidate to GlyphMatch.INPUT }
            }
            val byId = new.associateBy { it.id }
            old.filter { it.id !in matches && it.codePoints.isEmpty() && it.sources.isEmpty() }.forEach { glyph ->
                checkCanceled()
                val candidate = byId[glyph.id]?.takeIf { it.id !in used && it.codePoints.isEmpty() && it.sources.isEmpty() }
                if (candidate != null) { used.add(candidate.id); matches[glyph.id] = candidate to GlyphMatch.ID }
            }
            // Preserve font-space origin and baseline, including non-inking glyph advances.
            val bounds = Rectangle2D.Double(0.0, 0.0, 0.0, 0.0)
            val context = FontRenderContext(null, true, true)
            listOfNotNull(left, right).forEach { face ->
                bounds.add(face.font.deriveFont(GlyphGeometry.EM).getMaxCharBounds(context))
            }
            fun pair(a: Glyph?, b: Glyph?, match: GlyphMatch): GlyphPair {
                checkCanceled()
                val va = if (a == null || left == null) null else GlyphGeometry.vector(left, a)
                val vb = if (b == null || right == null) null else GlyphGeometry.vector(right, b)
                listOfNotNull(va, vb).forEach { vector ->
                    bounds.add(vector.getGlyphOutline(0).bounds2D)
                    bounds.add(vector.getGlyphMetrics(0).advanceX.toDouble(), 0.0)
                }
                if (a == null) return GlyphPair(null, b, match, GlyphChange.ADDED)
                if (b == null) return GlyphPair(a, null, match, GlyphChange.REMOVED)
                val outline = GlyphGeometry.changed(va!!.getGlyphOutline(0), vb!!.getGlyphOutline(0))
                val am = va.getGlyphMetrics(0); val bm = vb.getGlyphMetrics(0)
                val metrics = abs(am.advanceX - bm.advanceX) > 1f / 64 || abs(am.advanceY - bm.advanceY) > 1f / 64 ||
                    abs(am.lsb - bm.lsb) > 1f / 64 || abs(am.rsb - bm.rsb) > 1f / 64
                val mapping = !a.codePoints.sorted().equals(b.codePoints.sorted()) ||
                    a.sources.map(::inputKey).toSet() != b.sources.map(::inputKey).toSet()
                return GlyphPair(a, b, match, if (outline || metrics || mapping) GlyphChange.MODIFIED else GlyphChange.SAME, outline, metrics, mapping)
            }
            val pairs = old.map { glyph ->
                val match = matches[glyph.id]
                pair(glyph, match?.first, match?.second ?: GlyphMatch.NONE)
            } + new.filter { it.id !in used }.map { pair(null, it, GlyphMatch.NONE) }
            val sorted = pairs.sortedWith(compareBy<GlyphPair> { (it.left ?: it.right)!!.codePoints.minOrNull() ?: Int.MAX_VALUE }
                .thenBy { (it.left ?: it.right)!!.id })
            return FontComparison(left, right, sorted, bounds)
        }

        private fun inputKey(mapping: GlyphCopyMapping) = mapping.codePoints.joinToString(",") + ":" + mapping.features.sorted().joinToString(",")
    }
}
