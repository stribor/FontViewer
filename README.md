# Font Viewer

An IntelliJ Platform plugin, written entirely in Kotlin, with plugin ID
`org.stribor.fontpreview`. The editor uses Swing and Java2D from the IDE runtime.
It has no JCEF, Chromium, HTML, JavaScript, background polling, or bundled libraries.

## Install

Use **Settings → Plugins → ⚙ → Install Plugin from Disk…** and select
`build/distributions/font-preview-1.1.1.zip` in this directory. Restart the
IDE when prompted.

Open a `.ttf`, `.otf`, `.ttc`, `.otc`, or `.woff` file in the project to preview it.
The plugin targets IntelliJ Platform 2025.3 and later, including CLion.
Gradle builds compile against IntelliJ IDEA 2025.3, the minimum supported SDK.
GitHub Actions runs regression checks and Plugin Verifier against minimum and
latest stable IntelliJ IDEA and CLion releases. See [build and compatibility
checks](docs/verification.md) for downloadable ZIPs, reports, and local commands.

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

## Compare font versions

Open a two-sided comparison from version history, Git changes, or **Compare With…**.
The **Font comparison** diff tool displays both versions with aligned cells,
equal column counts, a shared zoom slider, and the same font-space origin and
baseline. Clicking or navigating a glyph selects its counterpart on both sides;
scrolling either grid scrolls the other. Added and removed glyphs have an empty
counterpart cell. Comparisons with an absent file also work.
Zoom, **Hide unchanged**, and **Highlight outlines** are shared preferences:
new font comparisons inherit your latest choices, including after an IDE restart.

- Green marks added glyphs, red removed glyphs, and amber modified glyphs.
- **Highlight outlines** paints only the removed outline regions red in
  the before grid and the added regions green in the after grid. Shared regions
  retain the normal foreground color.
- **Hide unchanged** hides matching glyphs in both grids. Turn it off to expand
  the full repertoire again. Empty glyphs such as spaces remain changes when
  their advance or bearings change.
- **Previous change / Next change** navigate differences. Font navigation actions
  also appear in the diff toolbar and use your standard previous/next difference shortcuts.
- Double-click a cell, press Enter, or use **Inspect / overlay** for enlarged
  paired views or an outline overlay. Origin, baseline and advance markers use
  shared coordinates so spacing and positioning changes remain visible.
- Each side retains copy actions; collection face selectors allow comparing
  different faces. File changes recompute the comparison in the background.

Matching uses Unicode mappings first, then unambiguous supported OpenType inputs.
Glyphs with neither use an explicitly labeled **Glyph ID fallback**; these IDs
can change between font builds, so an ID-based counterpart is not a guaranteed
semantic match. Ambiguous OpenType variants remain unmatched. Outlines are
compared geometrically at a normalized em size, including contours with different
path ordering; advance and bearing changes and supported mapping changes also
count. This compares the native monochrome outlines, not raster screenshots or
all font tables. Changes to font hints, kerning, unsupported shaping, color layers
or metadata alone do not count as glyph changes. Three-sided merge requests keep
the platform's existing viewer.

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
through a 20,005-glyph font. Comparison checks cover reordered glyph IDs, Unicode
aliases, OpenType matching, em normalization, outline and space-metric changes,
additions/deletions, filtering, selection, scrolling, zoom, navigation and overlays.
Generate comparison fixtures with `python3 tools/generate-diff-fixtures.py` after
generating the base fixtures. Grid and diff renders are saved under `build/verification/`.
To also check the reported Roboto Italic glyph 442 case, add
`--roboto-italic /path/to/Roboto-Italic.ttf` to the offline test command.

## License

Apache License 2.0. See [LICENSE](LICENSE).
