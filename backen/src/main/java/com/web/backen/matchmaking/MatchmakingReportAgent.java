package com.web.backen.matchmaking;

import java.util.Map;

/** Provider seam: v1 is backed by the site's existing Mimo-compatible LLM service. */
public interface MatchmakingReportAgent {
    Map<String, Object> write(Map<String, Object> structuredInput);
}
