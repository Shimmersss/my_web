# HTML presentation quality contract

- Produce a standalone UTF-8 HTML file that works offline.
- Use a strict Content Security Policy and no remote scripts.
- Keep all visible content inside the 16:9 viewport at 1280×720.
- Support keyboard navigation, overview, and slide numbers.
- Preserve Reveal's `B` blackout shortcut; use `L` for the local low-power toggle.
- Honor `prefers-reduced-motion`, expose fragments in print/PDF, and keep `off` mode completely static.
- Use only server-owned motion presets and semantic layout components. Model output may select an allowlisted name but may not supply markup, CSS, JavaScript, timing, or selectors.
- Keep title, body, figures, and semantic components inside both the slide viewport and the navigation-safe bottom area.
- Wait for fonts, images, and two animation frames before each QA screenshot; screenshot the final state with every fragment visible.
- Escape all prompt, research, and uploaded-document text before insertion.
- Keep slide notes and `sources.json` synchronized.
- Preview images must come from the exact downloadable HTML.
