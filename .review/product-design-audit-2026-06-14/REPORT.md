# Research Desk UX / Visual Audit

Date: 2026-06-14

## Audit Scope

- Product: Research Desk Vue frontend
- Core journey: home discovery -> publications -> PDF translation -> PPT generation
- Evidence: current-run screenshots at approximately 942px viewport width, current DOM inspection, and read-only accessibility checks
- Mode: read-only; no product code was modified
- Accessibility target: identify visible and high-confidence WCAG-related risks without claiming full compliance

## Executive Summary

The product already feels coherent enough to use: names are direct, the upload area is understandable, template choices are easy to compare, and completed translation tasks retain useful metadata. No P0 blocker was found.

The largest issue is structural consistency. The homepage, publications, translation, and PPT screens each use a different first-screen composition, so the product feels like several tools collected under one header rather than one continuous research workflow. Several core controls also lack accessible names or native keyboard behavior.

## Priority Findings

### P1. Core tools do not share a consistent first-screen structure

Evidence: screenshots 02, 03, and 04.

- Publications uses a left-aligned title after a large blank area.
- Translation uses a centered title and large upload zone.
- PPT generation starts inside a large card close to the header.
- Users must relearn page structure whenever they switch tools.

Recommendation: create a shared tool-page shell with consistent maximum width, title position, top spacing, description style, and action hierarchy. Allow tool-specific layouts inside that shell.

### P1. Homepage positioning overemphasizes PPT generation

Evidence: screenshots 01 and 06.

The hero describes papers, translation, PPT, and AI chat as equal parts of the product, but presents only one primary action: `生成 PPT`. This makes the product appear to be primarily a PPT generator.

Recommendation: expose the main research tasks at equal visual rank in or immediately below the hero: browse publications, translate PDF, generate PPT, and open AI chat. If PPT remains recommended, explain why it is recommended.

### P1. Publications wastes the most valuable first-screen space

Evidence: screenshot 02.

A large blank region pushes the search toolbar and results toward the fold. Only the top of the first publication is visible, reducing the usefulness of a high-frequency browsing screen.

Recommendation: substantially reduce the title-area height. Keep collections, search, type filter, synchronization status, and at least one complete result visible on entry.

### P1. PPT generation does not explain the complete submission flow above the fold

Evidence: screenshot 04.

The first screen is dominated by template cards. The prompt, optional uploads, and final generation action are not visible, so a new user may assume template selection is the main or only required task.

Recommendation: show a short three-step model near the title: `描述需求 -> 可选上传论文/模板 -> 生成 PPT`. Compress template cards so the main input or generation action enters the first screen.

### P1. Several core interactions are not reliably keyboard accessible

Evidence: confirmed through read-only source and DOM inspection; visually relevant to screenshots 01, 02, 03, and 05.

Examples include clickable `div`, `li`, and `p` elements used for tool cards, collection selection, footer entries, upload, and the floating mail entry. These may be skipped by keyboard and assistive technologies.

Recommendation: use native `button`, `a`, `label`, and `input` elements for interaction. Preserve Enter, Space, focus, and state semantics.

### P1. Core routes contain unnamed buttons and unlabeled form controls

Evidence: current-run DOM inspection.

- All four checked core routes contain one button without an accessible name, identified as the theme control.
- Publications has three controls without reliable programmatic labels.
- Translation has two controls without reliable programmatic labels.
- PPT generation has four controls without reliable programmatic labels.

Recommendation: label the theme action according to its next state, add `aria-pressed`, and connect every input to a visible `label` or `aria-labelledby`. Connect help and limit text with `aria-describedby`.

### P1. The floating mail button competes with primary tasks

Evidence: screenshots 01 through 05.

The saturated blue, large shadow, and fixed position give the mail action more visual weight than several primary page controls. It overlaps content-card or footer regions.

Recommendation: reduce size, shadow, and saturation; reserve a safe area around it; consider showing it only on the homepage/footer or collapsing it on task-heavy screens.

## Secondary Findings

### P2. Navigation grouping and emphasis are unclear

Evidence: screenshot 06.

`工具模块` looks similar to navigation items, while PPT generation receives a second, prominent bottom action. The close button is named `close` rather than localized.

Recommendation: use visibly weaker group labels, keep all research tools at the same navigation level, avoid duplicate PPT emphasis, and name the close action `关闭导航`.

### P2. Homepage and tool pages use competing visual languages

Evidence: screenshot 01 compared with 02 through 05.

The homepage uses dark photography and green CTA treatment; tool pages use light blue-gray surfaces, white cards, and blue selection states.

Recommendation: retain the hero image but bring in the brand blue, shared radius/border details, and the same primary-action color used by tool screens.

### P2. Primary color semantics are inconsistent

Evidence: screenshots 01, 03, 04, and 06.

Green is used for primary CTAs, blue for brand, selection, upload, and floating mail, while success states also use green.

Recommendation: choose one brand color for primary actions and selected states. Reserve green primarily for success or completion.

### P2. Translation states do not form an obvious continuous journey

Evidence: screenshots 03 and 05.

The upload state emphasizes starting a new job, while recent tasks and result actions are visually separated. Completed task cards read more like status summaries than actionable entries.

Recommendation: add a compact recent-task entry near the upload state, label completed-card actions explicitly, and provide a persistent `开始新翻译` action on result screens.

### P2. Progress and completion feedback may not be announced

Evidence: high-confidence DOM/source inference; screenshot 05 shows toast-based feedback.

Dynamic progress and completion messages were not confirmed to use `aria-live` or `role=status`.

Recommendation: provide a throttled `aria-live=polite` status region, use `role=alert` for urgent errors, and move focus or announce the result heading when a job completes.

### P2. Heading and landmark structure is incomplete

Evidence: current-run DOM inspection.

Publications and some translation states do not maintain a stable `h1`. Several pages lack explicit `header`, `nav`, `main`, or `footer` landmarks.

Recommendation: keep one stable `h1` per route, use semantic page landmarks, and add a skip-to-main-content link.

### P2. Low-contrast secondary text and small targets need verification

Evidence: screenshots 02, 03, and 05 plus read-only style inspection.

Light gray metadata and small controls may be difficult for low-vision and touch users. The publications collapse control is approximately 30 by 30 pixels.

Recommendation: target at least 4.5:1 contrast for small text and approximately 44 by 44 pixels for primary touch targets. Do not rely on color alone for selection or completion.

### P2. Publications hierarchy is too visually uniform

Evidence: screenshot 02.

Sidebar, filters, and result cards use similar white surfaces, rounding, and shadows. Search, navigation, and content compete at similar visual weight.

Recommendation: reduce sidebar/filter elevation, strengthen result-title hierarchy, and visually demote secondary metadata and tags.

### P2. PPT template selection needs a stronger selected state

Evidence: screenshot 04.

The selected template differs mainly through a thin blue outline.

Recommendation: add a clear check marker, subtle selected background, and stable selection label while keeping the existing Naive UI card pattern.

### P3. Result toast adds little durable guidance

Evidence: screenshot 05.

`已打开翻译结果` disappears quickly and does not explain page range, preview mode, downloads, or next action.

Recommendation: replace or supplement it with a persistent result header and action bar.

## Strengths

- The main tool names and descriptions are direct and easy to understand.
- The PDF upload area clearly communicates drag/drop, click selection, file type, and size limit.
- PPT templates provide names, palettes, and use cases that are easy to compare.
- Translation history preserves useful filename, page range, speed, time, and completion metadata.
- The shared header gives users a stable cross-page anchor.
- No page-level horizontal overflow was detected at the captured width.
- Checked content images have non-empty alternative text.
- The navigation drawer exposes dialog and menu semantics and has a visible selected state.
- Long publication titles and DOI strings have wrapping protection.

## Evidence Limits

- Captures are approximately 942px wide; genuine 320-430px mobile layouts were not captured in this run.
- Static screenshots cannot verify hover states, motion, focus visibility, focus return, full keyboard traversal, screen-reader announcements, errors, or loading transitions.
- The screenshots show specific scroll positions and do not prove that controls below the fold are absent.
- Exact color contrast needs browser-based measurement against final rendered styles.

## Recommended Sequence

1. Fix accessible names, native keyboard controls, form labels, headings, and landmarks.
2. Establish one shared tool-page shell and reduce publications first-screen whitespace.
3. Clarify homepage task entry and navigation grouping.
4. Improve PPT first-screen flow and translation state continuity.
5. Normalize primary colors, card elevation, template selection, and floating mail behavior.
6. Run focused mobile, keyboard, screen-reader, and contrast verification.

## Captured Steps

1. `01-home-desktop.png` - Homepage discovery. Health: usable, but task emphasis is unbalanced.
2. `02-publications-desktop.png` - Publications entry and filters. Health: functional, but first-screen space and hierarchy need work.
3. `03-translate-desktop.png` - Translation upload state. Health: clear starting action.
4. `04-ppt-desktop.png` - PPT template selection. Health: understandable selection, incomplete first-screen flow.
5. `05-translate-result-desktop.png` - Translation result and recent task. Health: useful metadata, weak persistent next-step guidance.
6. `06-navigation-drawer.png` - Cross-product navigation. Health: usable, but grouping and duplicate emphasis are unclear.
