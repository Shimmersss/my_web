---
name: research-presentation
description: Research and source presentation content from a prompt or uploaded material. Use for PPTX or HTML presentation tasks that need related literature, current web evidence, factual verification, source-aware visuals, or speaker-note citations.
---

# Research a presentation

1. Define the audience, communication job, central takeaway, and evidence gaps.
2. Read `references/source-policy.md`.
3. Search academic sources for research, technical, medical, scientific, or educational topics. Search the web for current, commercial, product, policy, or market claims.
4. Prefer primary papers, DOI pages, standards, official organizations, and first-party product sources.
5. Deduplicate papers by DOI, then normalized title and first author. Keep at most twelve sources.
6. Separate sourced facts from recommendations. Never invent metrics, quotations, authors, dates, or results.
7. Attach source IDs to every externally sourced claim and visual. Keep the canonical URL, title, authors, year, DOI, source type, and access time.
8. Use external images only when reuse rights or an explicit first-party media policy can be recorded. Otherwise use uploaded assets, template assets, or native charts.
9. For academic decks, reserve enough reference pages to cover every retained source and add `[Sources]` blocks to speaker notes. A non-empty research set with zero slide citations is a hard failure.
10. If general web search is unavailable, continue with open academic APIs and explicitly record the degradation.

Use the shared Agent tools exposed by the presentation worker. Do not call arbitrary URLs or write outside the assigned task directory.
