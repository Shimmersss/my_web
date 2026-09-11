package com.web.backen.github;

import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.ai.LlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GithubRankingServiceTest {

    private GithubRankingService service;

    @AfterEach
    void tearDown() {
        if (service != null) service.shutdown();
    }

    @Test
    void currentMonthQueryDoesNotIncludeFutureDays() {
        LocalDate today = LocalDate.of(2026, 8, 1);

        assertEquals("created:2026-08-01..2026-08-01 fork:false stars:>0",
                GithubRankingService.buildSearchQuery("monthly", today));
        assertEquals("created:2026-07-27..2026-08-01 fork:false stars:>0",
                GithubRankingService.buildSearchQuery("weekly", today));
        assertEquals(LocalDate.of(2026, 8, 1), GithubRankingService.periodEnd("monthly", today));
    }

    @Test
    void manualRefreshIsQueuedAndRunsBothPeriodsInWorker() {
        GithubProjectService github = mock(GithubProjectService.class);
        GithubRankingStore store = mock(GithubRankingStore.class);
        LlmClient llm = mock(LlmClient.class);
        RuntimeConfigService config = mock(RuntimeConfigService.class);
        when(store.read()).thenAnswer(invocation -> new LinkedHashMap<>());
        when(config.githubRankingManualCooldownMinutes()).thenReturn(30);
        when(config.githubRankingWeeklyLimit()).thenReturn(1);
        when(config.githubRankingMonthlyLimit()).thenReturn(1);
        when(config.githubRankingAiEnabled()).thenReturn(false);
        when(github.searchTopRepositories(anyString(), anyInt())).thenReturn(List.of());

        service = new GithubRankingService(github, store, llm, config);
        GithubRankingService.ManualRefreshResult result = service.requestManualRefresh();

        assertTrue(result.accepted());
        assertTrue(result.running());
        verify(github, timeout(2_000).times(2)).searchTopRepositories(anyString(), eq(1));
        assertFalse(service.requestManualRefresh().accepted());
    }
}
