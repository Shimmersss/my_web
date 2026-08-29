package com.web.backen.matchmaking;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingBenchmarksTest {
    @Test void cityUsesExplicitProvinceFallbackAndKeepsProvenance() {
        Map<String, Object> guangzhou = MatchmakingBenchmarks.context("广州");
        assertEquals("广东", guangzhou.get("province"));
        assertEquals("provinceFallback", guangzhou.get("granularity"));
        assertEquals(51474, guangzhou.get("disposableIncome"));
        assertTrue(String.valueOf(guangzhou.get("sourceUrl")).startsWith("https://www.stats.gov.cn/"));
    }

    @Test void municipalityIsNotMislabelledAsProvinceFallback() {
        assertEquals("municipality", MatchmakingBenchmarks.context("上海").get("granularity"));
        assertThrows(IllegalArgumentException.class, () -> MatchmakingBenchmarks.context("不存在的城市"));
    }
}
