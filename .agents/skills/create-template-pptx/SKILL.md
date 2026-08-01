---
name: create-template-pptx
description: Create or revise editable PPTX presentations by reusing a selected source deck as a slide library. Use when a task selects or uploads a PowerPoint template and requires faithful layouts, real rendered previews, citations, and iterative visual QA.
---

# Create a template-faithful PPTX

1. Load `references/quality-contract.md` and verify the selected built-in template against `assets/template-catalog.json` (uploaded templates are task-local exceptions).
2. Inspect every source slide and its text/image affordances before planning.
3. Give every output slide one narrative job and one primary claim.
4. Map every output slide to a real source slide. Reuse, reorder, or omit source slides deliberately.
5. Use the manifest's stable `slotId`/`regionId` values. Duplicate the mapped source slide and apply explicit `textEdits` and allowlisted `imageEdits` to inherited elements; never assign copy by shape order. Preserve masters, layouts, backgrounds, logos, crop, z-order, typography, spacing, and decorative furniture.
6. Respect each text slot's declared capacity. Shorten copy, choose another source slide, or split content; never use shrink-to-fit.
7. Never leave sample text, `未命名页面`, or an empty inherited placeholder.
8. Add source IDs to slide notes. Use uploaded or licensed visuals; do not use an untraceable web image.
9. Render every slide, run package and placeholder checks, then inspect all rendered pages.
10. Repair at most twice. If the template cannot express the requested content faithfully, fail with a precise explanation; never fall back to a palette imitation.

Use only the presentation worker's allowlisted tools and the current task directory.
