package com.web.backen.matchmaking;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingBenchmarksTest {
    @Test void cityUsesExplicitProvinceFallbackAndKeepsProvenance() {
        Map<String, Object> guangzhou = MatchmakingBenchmarks.context("广州");
        assertEquals("广东", guangzhou.get("province"));
        assertEquals("provinceFallback", guangzhou.get("granularity"));
        assertEquals(53669, guangzhou.get("disposableIncome"));
        assertEquals(2025, guangzhou.get("incomeStatYear"));
        assertEquals(676.3, guangzhou.get("nationalMarriageRegistrationsWan"));
        assertEquals(2025, guangzhou.get("nationalMarriageYear"));
        assertEquals(2024, guangzhou.get("marriageRegistrationsYear"));
        assertTrue(String.valueOf(guangzhou.get("incomeSourceUrl")).startsWith("https://www.stats.gov.cn/"));
        assertTrue(String.valueOf(guangzhou.get("marriageSourceUrl")).startsWith("https://www.mca.gov.cn/"));
    }

    @Test void municipalityIsNotMislabelledAsProvinceFallback() {
        assertEquals("municipality", MatchmakingBenchmarks.context("上海").get("granularity"));
        assertEquals(91987, MatchmakingBenchmarks.context("上海").get("disposableIncome"));
        assertThrows(IllegalArgumentException.class, () -> MatchmakingBenchmarks.context("不存在的城市"));
    }
}
