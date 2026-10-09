# Changelog

## 1.1.1 — 2026-10-09

- Remember font comparison zoom, Hide unchanged and Highlight outlines across comparisons and IDE restarts.

## 1.1.0 — 2026-10-09

- Add a dedicated two-sided font diff tool, including added and deleted files.
- Align Unicode and OpenType counterparts with explicit glyph ID fallback matches.
- Share column counts, zoom, font-space origins, baselines, scrolling and selection.
- Compare normalized outlines, metrics and mappings; color added, removed and modified glyphs.
- Highlight removed and added outline regions and hide unchanged glyphs.
- Add previous/next change navigation, per-side copies, collection face selection and paired/overlay inspection.
- Cover matching, geometry, controls and large-font comparisons with synthetic fixtures and rendered regression checks.

## 1.0.2 — 2026-10-09

- Hide unavailable character and Unicode copy actions in the context menu.
- Keep Copy Glyph ID and Inspect Glyph available for every glyph.

## 1.0.1 — 2026-10-09

- Resolve input text for OpenType small caps, alternates and ligatures.
- Show the OpenType feature needed to reproduce an alternate glyph.
- Add Copy Glyph ID to the context menu and inspector.
- Cover Roboto Italic glyph 442 and GSUB extension lookups in regression checks.

## 1.0.0 — 2026-10-09

- Add the native Kotlin font editor with a scrollable glyph repertoire and size slider.
- Add font and glyph information, enlarged inspection, and collection face selection.
- Support copying characters, Unicode escapes and U+ notation.
- Read TTF, OTF, TTC, OTC and WOFF fonts using the IDE runtime.
- Name the plugin Font Preview with ID org.stribor.fontpreview.
