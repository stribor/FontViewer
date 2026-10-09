"""Generate original font comparison fixtures from the existing synthetic fonts."""
from pathlib import Path
from fontTools.ttLib import TTFont
from fontTools.ttLib.reorderGlyphs import reorderGlyphs
from fontTools.ttLib.scaleUpem import scale_upem
from fontTools.pens.ttGlyphPen import TTGlyphPen

destination = Path(__file__).resolve().parents[1] / "src/test/resources"

font = TTFont(destination / "icons.ttf")
# Replace one character, change an outline, and change the advance of an empty glyph.
order = list(font.getGlyphOrder())
font["glyf"]
font["hmtx"]
font["cmap"]
order[3] = "added"
font.setGlyphOrder(order)
font["glyf"].glyphs["added"] = font["glyf"].glyphs.pop("supplementary")
font["glyf"].glyphOrder = order
font["hmtx"].metrics["added"] = font["hmtx"].metrics.pop("supplementary")
for table in font["cmap"].tables:
    table.cmap = {code: name for code, name in table.cmap.items() if code != 0x1F680}
    if table.isUnicode():
        table.cmap[0xEA72] = "added"
pen = TTGlyphPen(None)
pen.moveTo((300, 650))
for point in [(575, 0), (350, 0), (300, 120), (250, 0), (25, 0)]:
    pen.lineTo(point)
pen.closePath()
font["glyf"].glyphs["icon"] = pen.glyph()
font["hmtx"].metrics["space"] = (750, 0)
font.save(destination / "icons-diff.ttf")

font = TTFont(destination / "icons.ttf")
reorderGlyphs(font, [".notdef", "space", "supplementary", "icon", "unmapped"])
font.save(destination / "icons-reordered.ttf")

font = TTFont(destination / "icons.ttf")
scale_upem(font, 2000)
font.save(destination / "icons-scaled.ttf")

font = TTFont(destination / "substitutions.ttf")
order = list(font.getGlyphOrder())
order[9], order[10] = order[10], order[9]
reorderGlyphs(font, order)
font.save(destination / "substitutions-reordered.ttf")
print(f"Generated comparison fixtures in {destination}")
