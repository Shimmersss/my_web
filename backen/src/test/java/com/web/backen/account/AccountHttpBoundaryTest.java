package com.web.backen.account;

import com.web.backen.admin.AdminAccountController;
import com.web.backen.ai.LlmClient;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.github.GithubRankingService;
import com.web.backen.imagegen.ImageGenerationService;
import com.web.backen.matchmaking.MatchmakingTrialService;
import com.web.backen.ppt.PptCodexRunner;
import com.web.backen.ppt.PptGenerationService;
import com.web.backen.translate.TranslationService;
import com.web.backen.zotero.ZoteroCache;
import com.web.backen.zotero.ZoteroService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AuthController.class, AdminAccountController.class})
class AccountHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @MockBean AuthService auth;
    @MockBean QuotaService quota;
    @MockBean RuntimeConfigService runtime;
    @MockBean LlmClient llm;
    @MockBean ZoteroService zotero;
    @MockBean ZoteroCache cache;
    @MockBean GithubRankingService rankings;
    @MockBean PptGenerationService ppt;
    @MockBean TranslationService translation;
    @MockBean PptCodexRunner runner;
    @MockBean ImageGenerationService image;
    @MockBean MatchmakingTrialService trials;

    @Test
    void currentUserRetainsTrialAccessWithoutExposingInternalUsername() throws Exception {
        when(auth.currentUser(any())).thenReturn(Optional.of(new AuthUser(7, "internal-trial", "MATCHMAKING_TRIAL", 0, true)));
        when(auth.currentCsrfToken(any())).thenReturn(Optional.of("test-csrf"));
        when(trials.requireEnabled(7)).thenReturn(Map.of("status", "AVAILABLE"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("婚恋内测"))
                .andExpect(jsonPath("$.data.matchmakingTrial").value(true))
                .andExpect(jsonPath("$.data.trialAccess.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.csrfToken").value("test-csrf"));
        verifyNoInteractions(quota);
    }

    @Test
    void revokedTrialStillFailsAtTheHttpBoundary() throws Exception {
        when(auth.currentUser(any())).thenReturn(Optional.of(new AuthUser(7, "internal-trial", "MATCHMAKING_TRIAL", 0, true)));
        when(trials.requireEnabled(7)).thenThrow(new AuthException(403, "资格已失效"));
        mvc.perform(get("/api/auth/me")).andExpect(status().isForbidden());
    }

    @Test
    void adminMutationsRejectCsrfBeforeDoingWork() throws Exception {
        doThrow(new AuthException(403, "CSRF 校验失败")).when(auth).requireCsrf(any());
        mvc.perform(put("/api/admin/accounts/api-settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(runtime, cache, ppt, translation, image);
        verify(auth, never()).requireRoot(any());
    }

    @Test
    void adminDashboardStillRequiresRoot() throws Exception {
        when(auth.requireRoot(any())).thenThrow(new AuthException(403, "需要管理员权限"));
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isForbidden());
        verifyNoInteractions(quota, runtime, trials);
    }

    @Test
    void authorizedSettingsUpdateRetainsAllPostSaveActions() throws Exception {
        when(auth.requireRoot(any())).thenReturn(new AuthUser(1, "root", "ROOT", 0, true));
        when(runtime.publicSettings()).thenReturn(Map.of());
        mvc.perform(put("/api/admin/accounts/api-settings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        var ordered = inOrder(auth, runtime, cache, ppt, translation, image);
        ordered.verify(auth).requireCsrf(any());
        ordered.verify(auth).requireRoot(any());
        ordered.verify(runtime).update(Map.of());
        ordered.verify(cache).refreshAsync();
        ordered.verify(ppt).cleanupHistory();
        ordered.verify(translation).cleanupHistory();
        ordered.verify(image).cleanupHistory();
    }
}
