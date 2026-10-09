package org.stribor.fontpreview

import java.awt.Font
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.Inflater

data class GlyphCopyMapping(val codePoints: IntArray, val features: List<String> = emptyList()) {
    val notation: String get() = codePoints.joinToString(" ", transform = UnicodeText::notation)
    val escape: String get() = codePoints.joinToString("", transform = UnicodeText::escape)
    val character: String get() = codePoints.joinToString("", transform = UnicodeText::character)
    val label: String get() = notation + if (features.isEmpty()) "" else " — ${features.joinToString(", ")} (input text)"
}

data class Glyph(val id: Int, val codePoints: IntArray, val sources: List<GlyphCopyMapping> = emptyList()) {
    val unicode: String get() = codePoints.firstOrNull()?.let(UnicodeText::notation) ?: "Unmapped"
    val canCopy: Boolean get() = codePoints.isNotEmpty() || sources.isNotEmpty()
    val defaultCopyMapping: GlyphCopyMapping? get() = codePoints.firstOrNull()?.let { GlyphCopyMapping(intArrayOf(it)) } ?: sources.firstOrNull()
    val copyMappings: List<GlyphCopyMapping> get() = codePoints.map { GlyphCopyMapping(intArrayOf(it)) } + sources
    val description: String get() = if (codePoints.isNotEmpty()) unicode else sources.firstOrNull()?.let {
        "Input ${it.notation} · ${it.features.joinToString(", ")}"
    } ?: "No Unicode mapping"
}

object UnicodeText {
    fun notation(codePoint: Int) = "U+" + codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
    // Supplementary characters use two UTF-16 escapes, valid in Kotlin and Java strings.
    fun escape(codePoint: Int) = String(Character.toChars(codePoint)).map {
        "\\u" + it.code.toString(16).padStart(4, '0')
    }.joinToString("")
    fun character(codePoint: Int) = String(Character.toChars(codePoint))
}

data class FontFace(val font: Font, val names: Map<Int, String>, val glyphs: List<Glyph>, val unitsPerEm: Int) {
    val mappedGlyphCount = glyphs.count { it.codePoints.isNotEmpty() }
    val family: String get() = names[16] ?: names[1] ?: font.getFamily(Locale.ROOT)
    val style: String get() = names[17] ?: names[2] ?: "Regular"
    override fun toString() = "$family — $style"
}

data class FontDocument(val faces: List<FontFace>, val byteSize: Int, val format: String) {
    companion object {
        const val MAX_BYTES = 128 * 1024 * 1024
        fun load(source: ByteArray, checkCanceled: () -> Unit = {}): FontDocument {
            require(source.size in 12..MAX_BYTES) { "Font must be between 12 bytes and 128 MB." }
            checkCanceled()
            val signature = Bytes(source).tag(0)
            val data = if (signature == "wOFF") decodeWoff(source) else source
            require(signature != "wOF2") { "WOFF2 is not supported by the native renderer. Convert it to TTF or OTF." }
            val reader = Bytes(data)
            val collection = reader.tag(0) == "ttcf"
            val offsets = if (collection) {
                val count = reader.uint(8)
                require(count in 1..256) { "Invalid font collection face count." }
                IntArray(count) { reader.uint(12 + it * 4) }
            } else intArrayOf(0)
            val parsed = offsets.map { offset ->
                checkCanceled()
                Sfnt(reader, offset).parse(checkCanceled)
            }
            val fonts = Font.createFonts(ByteArrayInputStream(data))
            require(fonts.size == parsed.size) { "The native renderer could not read every collection face." }
            val faces = parsed.mapIndexed { index, info ->
                val count = fonts[index].numGlyphs
                require(count in 1..65535) { "Invalid glyph count." }
                require(count == info.mappings.size) { "Font glyph count does not match its maxp table." }
                FontFace(fonts[index], info.names, (0 until count).map { gid ->
                    Glyph(gid, info.mappings[gid]?.toIntArray() ?: IntArray(0), info.sources[gid]?.toList() ?: emptyList())
                }, info.unitsPerEm)
            }
            val format = when {
                signature == "wOFF" -> "WOFF"
                collection -> "OpenType collection"
                reader.tag(0) == "OTTO" -> "OpenType (CFF)"
                else -> "TrueType"
            }
            return FontDocument(faces, source.size, format)
        }

        private fun decodeWoff(data: ByteArray): ByteArray {
            val input = Bytes(data)
            input.range(0, 44)
            require(input.uint(8) == data.size) { "Invalid WOFF length." }
            val count = input.ushort(12)
            require(count in 1..2048) { "Invalid WOFF table count." }
            val size = input.uint(16)
            require(size in (12 + count * 16)..MAX_BYTES) { "Invalid uncompressed WOFF size." }
            val output = ByteArray(size)
            val buffer = ByteBuffer.wrap(output).order(ByteOrder.BIG_ENDIAN)
            buffer.putInt(input.int(4)).putShort(count.toShort())
            val power = Integer.highestOneBit(count)
            buffer.putShort((power * 16).toShort()).putShort(Integer.numberOfTrailingZeros(power).toShort())
                .putShort((count * 16 - power * 16).toShort())
            var position = 12 + count * 16
            repeat(count) { index ->
                val entry = 44 + index * 20
                val offset = input.uint(entry + 4)
                val compressed = input.uint(entry + 8)
                val original = input.uint(entry + 12)
                require(compressed <= original && original <= size - position) { "Invalid WOFF table size." }
                input.range(offset, compressed)
                buffer.position(12 + index * 16)
                buffer.putInt(input.int(entry)).putInt(input.int(entry + 16)).putInt(position).putInt(original)
                if (compressed == original) {
                    data.copyInto(output, position, offset, offset + original)
                } else {
                    val inflater = Inflater()
                    try {
                        inflater.setInput(data, offset, compressed)
                        var written = 0
                        while (written < original && !inflater.finished()) {
                            val n = inflater.inflate(output, position + written, original - written)
                            require(n > 0) { "Invalid compressed WOFF table." }
                            written += n
                        }
                        require(written == original && inflater.finished()) { "Invalid WOFF decompressed size." }
                    } finally { inflater.end() }
                }
                position = (position + original + 3) and -4
                require(position <= size) { "Invalid WOFF table padding." }
            }
            require(position == size) { "Invalid WOFF total SFNT size." }
            return output
        }
    }
}

private class Bytes(val data: ByteArray, private val start: Int = 0, private val length: Int = data.size) {
    fun range(offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= length - count) { "Truncated or invalid font table." }
    }
    fun ushort(offset: Int): Int {
        range(offset, 2)
        return ((data[start + offset].toInt() and 255) shl 8) or (data[start + offset + 1].toInt() and 255)
    }
    fun ubyte(offset: Int): Int { range(offset, 1); return data[start + offset].toInt() and 255 }
    fun int(offset: Int): Int = (ushort(offset) shl 16) or ushort(offset + 2)
    fun uint(offset: Int): Int {
        val value = int(offset)
        require(value >= 0) { "Font offset or length exceeds supported size." }
        return value
    }
    fun tag(offset: Int): String { range(offset, 4); return String(data, start + offset, 4, Charsets.ISO_8859_1) }
    fun slice(offset: Int, count: Int): Bytes { range(offset, count); return Bytes(data, start + offset, count) }
    fun tail(offset: Int): Bytes = slice(offset, length - offset)
    fun string(offset: Int, count: Int, charset: Charset): String {
        range(offset, count)
        return String(data, start + offset, count, charset).replace("\u0000", "").trim()
    }
}

private data class ParsedFace(val names: Map<Int, String>, val mappings: Array<MutableList<Int>?>,
                              val sources: Array<MutableList<GlyphCopyMapping>?>, val unitsPerEm: Int)

private class Sfnt(private val input: Bytes, offset: Int) {
    private val tables: Map<String, Bytes>
    init {
        require(input.int(offset) == 0x00010000 || input.tag(offset) in setOf("OTTO", "true")) { "Unsupported font signature." }
        val count = input.ushort(offset + 4)
        require(count in 1..2048) { "Invalid SFNT table count." }
        tables = (0 until count).associate { index ->
            val entry = offset + 12 + index * 16
            input.tag(entry) to input.slice(input.uint(entry + 8), input.uint(entry + 12))
        }
    }
    fun parse(checkCanceled: () -> Unit): ParsedFace {
        val count = requireNotNull(tables["maxp"]) { "Missing glyph count table." }.ushort(4)
        require(count > 0) { "Font has no glyphs." }
        val mappings = arrayOfNulls<MutableList<Int>>(count)
        tables["cmap"]?.let { cmap ->
            val records = cmap.ushort(2)
            // Prefer full Unicode coverage, then BMP Unicode, then Microsoft symbol encoding.
            val candidates = (0 until records).mapNotNull { index ->
                val record = 4 + index * 8
                val platform = cmap.ushort(record)
                val encoding = cmap.ushort(record + 2)
                val unicode = platform == 0 || platform == 3 && encoding in setOf(1, 10)
                val symbol = platform == 3 && encoding == 0
                if (!unicode && !symbol) return@mapNotNull null
                val location = cmap.uint(record + 4)
                val format = cmap.ushort(location)
                if (format !in setOf(0, 4, 6, 12, 13)) return@mapNotNull null
                val size = if (format >= 12) cmap.uint(location + 4) else cmap.ushort(location + 2)
                val priority = (if (unicode) 100 else 0) + (if (format >= 12) 20 else 0) + (if (platform == 3) 1 else 0)
                priority to cmap.slice(location, size)
            }
            candidates.maxByOrNull { it.first }?.second?.let { table ->
                readCmap(table, checkCanceled) { codePoint, gid ->
                    if (gid in 1 until count && Character.isValidCodePoint(codePoint) && codePoint !in 0xD800..0xDFFF) {
                        val list = mappings[gid] ?: mutableListOf<Int>().also { mappings[gid] = it }
                        list.add(codePoint)
                    }
                }
            }
        }
        return ParsedFace(readNames(), mappings, readSubstitutionSources(mappings, checkCanceled), tables["head"]?.ushort(18) ?: 1000)
    }

    /** Keep input text separate from Unicode encodings: an alternate does not acquire a code point. */
    private fun readSubstitutionSources(mappings: Array<MutableList<Int>?>, checkCanceled: () -> Unit): Array<MutableList<GlyphCopyMapping>?> {
        val sources = arrayOfNulls<MutableList<GlyphCopyMapping>>(mappings.size)
        val gsub = tables["GSUB"] ?: return sources
        val lookups = gsub.tail(gsub.ushort(8))
        val lookupCount = lookups.ushort(0)
        val features = Array(lookupCount) { linkedSetOf<String>() }
        val featureList = gsub.tail(gsub.ushort(6))
        repeat(featureList.ushort(0)) { index ->
            checkCanceled()
            val record = 2 + index * 6
            val tag = featureList.tag(record)
            val feature = featureList.tail(featureList.ushort(record + 4))
            repeat(feature.ushort(2)) { position ->
                val lookup = feature.ushort(4 + position * 2)
                require(lookup < lookupCount) { "Invalid GSUB feature lookup." }
                features[lookup].add(tag)
            }
        }

        fun addSource(target: Int, inputGlyphs: IntArray, tags: Set<String>) {
            if (target !in 1 until mappings.size || inputGlyphs.any { it !in mappings.indices || mappings[it].isNullOrEmpty() }) return
            // For ligatures use each component's default encoding; single substitutions preserve aliases.
            val inputs = if (inputGlyphs.size == 1) mappings[inputGlyphs[0]]!!.take(32).map { intArrayOf(it) }
                         else listOf(inputGlyphs.map { mappings[it]!!.first() }.toIntArray())
            val list = sources[target] ?: mutableListOf<GlyphCopyMapping>().also { sources[target] = it }
            for (tag in tags) for (input in inputs) {
                if (list.size >= 32) return
                if (list.none { it.codePoints.contentEquals(input) && it.features == listOf(tag) }) list.add(GlyphCopyMapping(input, listOf(tag)))
            }
        }

        fun coverage(table: Bytes, offset: Int): IntArray {
            val data = table.tail(offset)
            return when (data.ushort(0)) {
                1 -> IntArray(data.ushort(2)) { data.ushort(4 + it * 2) }
                2 -> {
                    val result = mutableListOf<Int>()
                    var previousEnd = -1
                    repeat(data.ushort(2)) { index ->
                        checkCanceled()
                        val record = 4 + index * 6
                        val start = data.ushort(record)
                        val end = data.ushort(record + 2)
                        require(start > previousEnd && start <= end && data.ushort(record + 4) == result.size) { "Invalid GSUB coverage range." }
                        for (glyph in start..end) result.add(glyph)
                        previousEnd = end
                    }
                    result.toIntArray()
                }
                else -> error("Unsupported GSUB coverage format.")
            }
        }

        fun readSubtable(type: Int, table: Bytes, tags: Set<String>, extension: Boolean = false) {
            checkCanceled()
            if (type == 7) {
                require(!extension && table.ushort(0) == 1 && table.ushort(2) != 7) { "Invalid GSUB extension." }
                readSubtable(table.ushort(2), table.tail(table.uint(4)), tags, true)
                return
            }
            if (type !in setOf(1, 3, 4)) return // Contextual substitutions require a shaping context.
            val format = table.ushort(0)
            val covered = coverage(table, table.ushort(2))
            when (type) {
                1 -> {
                    require(format in 1..2) { "Invalid single substitution format." }
                    if (format == 2) require(table.ushort(4) == covered.size) { "Invalid substitution count." }
                    covered.forEachIndexed { index, base ->
                        checkCanceled()
                        val target = if (format == 1) (base + table.ushort(4)) and 0xFFFF else table.ushort(6 + index * 2)
                        addSource(target, intArrayOf(base), tags)
                    }
                }
                3 -> {
                    require(format == 1 && table.ushort(4) == covered.size) { "Invalid alternate substitution." }
                    covered.forEachIndexed { index, base ->
                        checkCanceled()
                        val alternate = table.tail(table.ushort(6 + index * 2))
                        repeat(alternate.ushort(0)) { addSource(alternate.ushort(2 + it * 2), intArrayOf(base), tags) }
                    }
                }
                4 -> {
                    require(format == 1 && table.ushort(4) == covered.size) { "Invalid ligature substitution." }
                    covered.forEachIndexed { index, base ->
                        checkCanceled()
                        val set = table.tail(table.ushort(6 + index * 2))
                        repeat(set.ushort(0)) { item ->
                            val ligature = set.tail(set.ushort(2 + item * 2))
                            val count = ligature.ushort(2)
                            require(count in 1..64) { "Invalid ligature component count." }
                            val inputs = IntArray(count) { if (it == 0) base else ligature.ushort(2 + it * 2) }
                            addSource(ligature.ushort(0), inputs, tags)
                        }
                    }
                }
            }
        }

        repeat(lookupCount) { index ->
            checkCanceled()
            if (features[index].isEmpty()) return@repeat
            val lookup = lookups.tail(lookups.ushort(2 + index * 2))
            val type = lookup.ushort(0)
            repeat(lookup.ushort(4)) { subtable ->
                readSubtable(type, lookup.tail(lookup.ushort(6 + subtable * 2)), features[index])
            }
        }
        return sources
    }

    private fun readCmap(table: Bytes, checkCanceled: () -> Unit, emit: (Int, Int) -> Unit) {
        when (table.ushort(0)) {
            0 -> repeat(256) { cp -> emit(cp, table.ubyte(6 + cp)) }
            6 -> {
                val first = table.ushort(6)
                repeat(table.ushort(8)) { emit(first + it, table.ushort(10 + it * 2)) }
            }
            4 -> {
                val segments = table.ushort(6) / 2
                require(segments in 1..32767) { "Invalid cmap segment count." }
                val starts = 16 + segments * 2
                val deltas = starts + segments * 2
                val ranges = deltas + segments * 2
                var previousEnd = -1
                repeat(segments) { index ->
                    checkCanceled()
                    val end = table.ushort(14 + index * 2)
                    val start = table.ushort(starts + index * 2)
                    require(start > previousEnd && start <= end) { "Invalid cmap range." }
                    previousEnd = end
                    val delta = table.ushort(deltas + index * 2)
                    val location = ranges + index * 2
                    val range = table.ushort(location)
                    for (cp in start..end) {
                        if (cp and 1023 == 0) checkCanceled()
                        if (cp == 0xFFFF) continue
                        val raw = if (range == 0) cp else table.ushort(location + range + (cp - start) * 2)
                        val gid = if (range != 0 && raw == 0) 0 else (raw + delta) and 0xFFFF
                        emit(cp, gid)
                    }
                }
            }
            12, 13 -> {
                val constant = table.ushort(0) == 13
                val groups = table.uint(12)
                require(groups <= 0x110000) { "Invalid cmap group count." }
                table.range(16, Math.multiplyExact(groups, 12))
                var previousEnd = -1
                repeat(groups) { index ->
                    checkCanceled()
                    val entry = 16 + index * 12
                    val start = table.uint(entry)
                    val end = table.uint(entry + 4)
                    val firstGlyph = table.uint(entry + 8)
                    require(start > previousEnd && end in start..0x10FFFF) { "Invalid cmap Unicode range." }
                    previousEnd = end
                    for (cp in start..end) {
                        if (cp and 1023 == 0) checkCanceled()
                        emit(cp, if (constant) firstGlyph else Math.addExact(firstGlyph, cp - start))
                    }
                }
            }
        }
    }

    private fun readNames(): Map<Int, String> {
        val table = tables["name"] ?: return emptyMap()
        val storage = table.ushort(4)
        val chosen = mutableMapOf<Int, Pair<Int, String>>()
        repeat(table.ushort(2)) { index ->
            val record = 6 + index * 12
            val platform = table.ushort(record)
            val language = table.ushort(record + 4)
            val id = table.ushort(record + 6)
            val charset = when (platform) {
                0, 3 -> Charsets.UTF_16BE
                1 -> Charset.forName("x-MacRoman")
                else -> return@repeat
            }
            val value = table.string(storage + table.ushort(record + 10), table.ushort(record + 8), charset)
            val score = (if (platform == 3) 10 else if (platform == 0) 5 else 0) + (if (language == 0x409 || language == 0) 20 else 0)
            if (value.isNotEmpty() && score > (chosen[id]?.first ?: -1)) chosen[id] = score to value
        }
        return chosen.mapValues { it.value.second }
    }
}
