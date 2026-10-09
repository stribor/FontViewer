"""Generate original synthetic fonts for regression checks (requires fonttools)."""
from pathlib import Path
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.ttLib import TTCollection
from fontTools.feaLib.builder import addOpenTypeFeaturesFromString

destination = Path(__file__).resolve().parents[1] / "src/test/resources"
destination.mkdir(parents=True, exist_ok=True)

def draw(pen, index):
    if index == 1:
        return  # Space: encoded but deliberately has no visible outline.
    if index % 3 == 0:
        points = [(100, 0), (500, 0), (500, 600), (100, 600)]
    elif index % 3 == 1:
        points = [(300, 0), (550, 300), (300, 600), (50, 300)]
    else:
        points = [(300, 600), (550, 0), (50, 0)]
    pen.moveTo(points[0])
    for point in points[1:]:
        pen.lineTo(point)
    pen.closePath()

def make(style="Regular", cff=False, large=False, substitutions=False):
    order = [".notdef", "space", "icon", "supplementary", "unmapped"]
    if large:
        order += [f"g{index}" for index in range(20000)]
    if substitutions:
        order += ["A", "a", "f", "i", "smallcap", "alternate", "fi", "extension"]
    builder = FontBuilder(1000, isTTF=not cff)
    builder.setupGlyphOrder(order)
    cmap = {0x20: "space", 0xE91B: "icon", 0xE91C: "icon", 0x1F680: "supplementary"}
    if large:
        cmap.update({0x10000 + index: f"g{index}" for index in range(20000)})
    if substitutions:
        cmap.update({ord(char): char for char in "Aafi"})
    builder.setupCharacterMap(cmap)
    if cff:
        charstrings = {}
        for index, name in enumerate(order):
            pen = T2CharStringPen(600, None)
            draw(pen, index)
            charstrings[name] = pen.getCharString()
        builder.setupCFF("StriborFixture", {"FullName": "Stribor Fixture", "FamilyName": "Stribor Fixture", "Weight": style}, charstrings, {})
    else:
        glyphs = {}
        for index, name in enumerate(order):
            pen = TTGlyphPen(None)
            draw(pen, index)
            glyphs[name] = pen.glyph()
        builder.setupGlyf(glyphs)
    builder.setupHorizontalMetrics({name: (600, 0) for name in order})
    builder.setupHorizontalHeader(ascent=800, descent=-200)
    builder.setupNameTable({"familyName": "Stribor Fixture", "styleName": style,
        "uniqueFontIdentifier": f"StriborFixture-{style}", "fullName": f"Stribor Fixture {style}",
        "psName": f"StriborFixture-{style}", "version": "Version 1.0", "licenseDescription": "Original synthetic test fixture."})
    builder.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    builder.setupPost()
    if cff:
        builder.setupMaxp()
    if substitutions:
        addOpenTypeFeaturesFromString(builder.font, """
            feature c2sc { sub A by smallcap; } c2sc;
            feature smcp { sub a by smallcap; } smcp;
            feature salt { sub A from [alternate]; } salt;
            feature liga { sub f i by fi; } liga;
            lookup Extended useExtension { sub i by extension; } Extended;
            feature ss01 { lookup Extended; } ss01;
        """)
    return builder.font

font = make()
font.save(destination / "icons.ttf")
font.flavor = "woff"
font.save(destination / "icons.woff")
make(cff=True).save(destination / "icons.otf")
collection = TTCollection()
collection.fonts = [make(), make("Bold")]
collection.save(destination / "faces.ttc")
make(large=True).save(destination / "large.ttf")
make(substitutions=True).save(destination / "substitutions.ttf")
print(f"Generated fixtures in {destination}")
