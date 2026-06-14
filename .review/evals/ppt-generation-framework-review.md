# PPT Generation Framework Review

Date: 2026-06-08

## Baseline

Local sample:

- Template: `.run/ppt-generation-tasks/2fc988b2/template.pptx`
- Paper: `.run/ppt-generation-tasks/2fc988b2/paper.docx`
- Requirement: one-step PPTX generation, no user-facing outline confirmation, strong reuse of uploaded PPTX template.

## Findings

### ppt-master

Repository: `hugohe3/ppt-master`

Best fit for current problem. It is not a simple one-command generator; it is an agent workflow plus local scripts. The useful parts for this project are:

- `template_fill_pptx.py analyze`: reads an existing PPTX as a slide library.
- `slide_library.json`: exposes page type, text slots, slot geometry, font metrics, tables, and charts.
- `template_fill_pptx.py scaffold/check-plan/apply`: creates a fill plan, validates text capacity, clones selected source slides, replaces OOXML text, and preserves original template design.
- Strategist/spec-lock concept: separate slide planning from rendering, lock colors/fonts/page rhythm to prevent long-deck drift.

Smoke result:

- Analyzed the local template successfully: 24 slides, 1280 x 720 canvas.
- Capacity check caught wrong slot IDs and overlong text before rendering.
- After fixing the plan, generated `.review/evals/ppt-master-template-fill/exports/manual-smoke_20260608_031554.pptx`.
- XML read-back confirmed replacement text such as `化合物活性预测研究` and `计算机科学 李泽轩`.

Recommended use:

- Adopt `template_fill_pptx` as the preferred uploaded-template path.
- Let mimo generate a fill plan against `slide_library.json`, not a free-form deck JSON.
- Run `check-plan`; if it fails, ask mimo to shorten/remap before applying.
- Keep current python-pptx renderer only for no-template/free-design fallback.

### Presenton

Repository: `presenton/presenton`

Good as a complete self-hosted product/API, less direct as an embedded renderer.

Useful capabilities:

- Docker/API deployment.
- Supports `LLM=custom` with OpenAI-compatible endpoint, so mimo can likely be wired through `CUSTOM_LLM_URL`, `CUSTOM_LLM_API_KEY`, and `CUSTOM_MODEL`.
- Supports prompt/document generation and custom templates/themes.
- Provides `/api/v1/ppt/presentation/generate`.

Mismatch:

- Custom templates are mostly converted into HTML/Tailwind layout templates, not native clone-and-fill of uploaded PowerPoint slides.
- Running the full stack is heavier than the current 2 CPU / 4 GB baseline.
- Integrating it would likely mean running a separate service, not importing a small library.

Recommended use:

- Keep as a secondary product-level benchmark.
- Do not make it the first integration path unless we decide to outsource the whole PPT service.

## Proposed Architecture

1. If user uploads a PPTX template:
   - Analyze PPTX with `ppt-master` `template_fill_pptx.py analyze`.
   - Extract paper text/images with current input layer.
   - Use mimo to produce a `fill_plan.json`:
     - target story order controls output order;
     - source slides may be reused;
     - every replacement targets `slot_id`;
     - text length respects `geometry` and `font_size_px`;
     - image/table pages are chosen by layout affordance.
   - Run `check-plan`.
   - If warnings/errors are material, run one repair prompt.
   - Apply with `template_fill_pptx.py apply`.

2. If no PPTX template:
   - Use the current internal structure + `ppt_renderer.py`, or later migrate to a PPT Master-style SVG pipeline.

3. Improve prompting:
   - Replace current deck prompt with a strategist prompt that outputs:
     - page story;
     - selected source slide;
     - layout rationale;
     - slot replacements;
     - speaker notes;
     - image evidence mapping.
   - Add a compact `spec_lock` payload for colors, typography, template route, and image whitelist.

## Next Implementation Slice

- Vendor or reference-copy only the minimal `template_fill_pptx` scripts.
- Add `PPT_GENERATION_TEMPLATE_FILL_COMMAND`.
- Add task branch: uploaded template -> analyze -> mimo fill plan -> check-plan -> apply.
- Preserve one-stage frontend behavior.
- Add tests with a small generated PPTX template fixture.
