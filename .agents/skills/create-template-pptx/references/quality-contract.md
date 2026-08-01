# PPTX quality contract

- Every output slide must have a valid `sourceSlide`.
- Every visible string must map to a valid stable text slot, and every replacement image must map to both an allowlisted image region and a task-local approved image ID.
- Preserve inherited geometry and source-deck structure.
- Titles must not wrap unexpectedly; body copy must fit within declared capacity without shrink-to-fit or clipping.
- Remove unresolved sample copy and empty placeholders.
- Keep slide-level `[Sources]` notes synchronized with `sources.json`.
- The final package must pass archive, relationship, orphan-part, and slide-count checks.
- Preview PNGs must be rendered from the exact downloadable PPTX.
