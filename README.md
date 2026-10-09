# Font Preview

An IntelliJ Platform plugin, written entirely in Kotlin, with plugin ID
`org.stribor.fontpreview`. The editor uses Swing and Java2D from the IDE runtime.
It has no JCEF, Chromium, HTML, JavaScript, background polling, or bundled libraries.

## Install

Use **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select
`build/distributions/font-preview-1.0.2.zip` in this directory. Restart the
IDE when prompted.

Open a `.ttf`, `.otf`, `.ttc`, `.otc`, or `.woff` file in the project to preview it.
The plugin targets IntelliJ Platform 2025.3 and later, including CLion.
The local ZIP was compiled against CLion `263.6800.10`; interactive IDE testing
and the JetBrains Plugin Verifier have not been run.

## Use

- The repertoire grid shows every glyph ID, including glyphs with no Unicode
  mapping. Unicode labels identify encoded glyphs; supported OpenType alternates
  show input text and feature tags, and other unmapped glyphs have `gid` labels.
- The size slider changes the preview from 16 to 160 px.
- The face selector appears only when a collection contains multiple faces.
- The information button opens a sidebar with font names, format, glyph counts,
  selected glyph metrics, copyright and licensing information when present.
- Select a glyph and use the bottom copy buttons, or right-click for native copy
  actions: `\ue91b`, `U+E91B`, or the actual character.
- Double-click a glyph or press Enter for an enlarged inspector. If a glyph has
  multiple Unicode mappings, choose the mapping to copy in this inspector.
- Arrow keys, Home/End and Page Up/Down navigate; Cmd+C on macOS (Ctrl+C elsewhere)
  copies the selected character. Shift+F10 opens its context menu.

Font size, selected face, glyph selection and sidebar visibility are saved in
the IDE's editor state. VFS changes reload the preview in the background.

Supplementary characters are copied as two UTF-16 escapes, for example
`\ud83d\ude80`, so the result works inside Kotlin and Java string literals.
Small caps, alternate forms and ligatures often have no direct Unicode encoding.
For supported single, alternate and ligature GSUB substitutions (including
extension lookups), copying uses the original input character or character
sequence. The inspector lets you choose the input and displays the OpenType
feature required to reproduce the appearance. For example, Roboto Italic's
small-cap A can be copied as `A` with `c2sc`, or `a` with `smcp`.
The copied text does not encode the OpenType feature itself.

Other unmapped glyphs cannot be copied as characters or Unicode values; the
context menu hides unavailable character/Unicode copy actions and always offers
**Copy Glyph ID** and **Inspect Glyph**. The inspector also offers **Copy Glyph ID**. Contextual or chained
substitutions that require a shaping context are not resolved in this version.

## Resource use and format limits

The grid is a single component and paints only visible cells, with a maximum of
512 cached glyph vectors. Font loading and Unicode parsing run off the UI thread;
superseded loads are canceled and editor disposal releases listeners and references.
Fonts are opened locally and are not installed into the system or registered globally.

TTF and OpenType/CFF outlines, collections, and WOFF 1 are supported. WOFF 1 is
decoded using the JDK's zlib implementation. WOFF2 needs conversion to TTF/OTF
first. This is a monochrome glyph repertoire viewer; color-font layers, variable
font axis controls, and shaping/playground features are outside this version.
Font files are limited to 128 MB.

## Build

From the project root with JDK 21:

```bash
./gradlew buildPlugin regressionTest
```

To compile against an installed IDE, add `-PlocalIdePath=/path/to/IDE.app`.
Gradle downloads its dependencies when they are not already cached.

An offline build is also available using an installed IDE runtime and Kotlin
compiler distribution (Kotlin 2.2 or later):

```bash
python3 tools/build-local.py \
  --ide /path/to/IDE.app \
  --kotlin-home "/path/to/kotlin" \
  --test
```

The script compiles Java 21 bytecode with Kotlin language/API version 2.2, runs
regression checks, and packages only production classes and resources. Kotlin's
standard library is supplied by the IDE, not included in the ZIP.

The checked-in fonts are original synthetic test fixtures. To regenerate them,
install `fonttools` and run `python3 tools/generate-fixtures.py`. Checks cover TTF,
CFF/OTF, WOFF, two-face TTCs, Unicode aliases and supplementary characters,
unmapped glyphs, GSUB small caps/alternates/ligatures/extension lookups,
malformed data, cancellation, rendered outlines, and scrolling
through a 20,005-glyph font. Grid renders are saved under `build/verification/`.
To also check the reported Roboto Italic glyph 442 case, add
`--roboto-italic /path/to/Roboto-Italic.ttf` to the offline test command.

## License

Apache License 2.0. See [LICENSE](LICENSE).
