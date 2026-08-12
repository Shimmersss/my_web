# Semantic layout and motion contract

## Layout selection

- `cover`: minimal title and optional one-line context.
- `section`: a chapter break, not a content card grid.
- `statement`: one dominant takeaway with restrained supporting copy.
- `image-hero`: a source-aware full-bleed visual with readable scrim and short copy.
- `split`: text and one meaningful image in distinct columns.
- `evidence`: claim plus evidence visual or an explicit evidence callout.
- `stats`: two to four metrics; each metric has a value and label.
- `process`: two to four ordered steps.
- `comparison`: two named sides with balanced evidence.
- `timeline`: two to four chronological events.
- `quote`: one quotation or human voice, with minimal context.
- `gallery`: one dominant visual and compact curatorial caption; it is not a thumbnail mosaic.
- `closing`: a concise conclusion or next action.

The renderer enforces the selected theme's allowlist and safely falls back when a requested layout lacks the needed image. A model can provide narrative fields and allowlisted names only; it never provides executable or style code.

## Deck rhythm

Alternate deep, light, and base surfaces so a deck has chapter rhythm instead of repeating one panel silhouette. Use semantic layouts to express the information relationship, not as decoration. Do not place every bullet in a card.

## Motion

The four user modes are `auto`, `subtle`, `expressive`, and `off`. They map to fixed server-owned presets: calm, editorial, focus, flow, and cinematic. Fragments are limited to ordered processes, timelines, metrics, or a deliberately expressive reveal. Stable `data-id` values may provide continuity between adjacent content slides through Reveal Auto-Animate. Motion must have a reduced-motion equivalent, a static print state, and a low-power state. The low-power shortcut is `L`; Reveal's `B` blackout shortcut remains untouched.

## Clean-room provenance

This specification was written from abstract behavior research, not copied implementations.

- reveal.js is MIT and remains the embedded offline runtime.
- Slidev, html-slides, make-slide, and frontend-slides-editable are MIT; only high-level ideas such as click steps, auto-animate continuity, semantic layout separation, and bounded themes informed this contract.
- Guizang PPT Skill is AGPL-3.0. Do not copy its code, CSS, templates, layout identifiers, numbered design systems, prompts, or assets. Only generic presentation principles independently expressed here may be used.
