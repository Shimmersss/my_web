---
name: create-html-presentation
description: Create or revise offline HTML presentations with reveal.js theme assets, real browser previews, citations, responsive 16:9 layouts, and iterative QA. Use for HTML presentation output or natural-language revisions to an existing HTML deck.
---

# Create an HTML presentation

1. Load `references/quality-contract.md` and the selected theme's `style`, tokens, and allowed layout vocabulary from `assets/themes.json`.
2. Define the communication job and a cumulative narrative arc.
3. Compose varied 16:9 slide silhouettes from `cover`, `statement`, `split`, `evidence`, `comparison`, `timeline`, `quote`, and `closing` when the theme allows them. Do not repeat one card grid across the deck.
4. Write audience-facing copy. Keep the title slide minimal and use takeaway titles.
5. Embed the approved theme, reveal.js runtime, images, and fonts needed for offline use.
6. Put source IDs in each slide's speaker notes. Keep the deck audience-facing: do not expose a bibliography unless the user explicitly asks for one.
7. Do not emit arbitrary scripts, remote resources, forms, storage access, network calls, or parent-frame access.
8. Honor the selected style's typography, background treatment, dividers, and surface geometry; a theme is not merely a palette swap.
9. Open the deck in Chromium, capture every page, and check overflow, contrast, wrapping, navigation, and source coverage.
10. Repair at most twice. Do not return a deck that fails deterministic or visual QA.

Use only the presentation worker's allowlisted tools and the current task directory.
