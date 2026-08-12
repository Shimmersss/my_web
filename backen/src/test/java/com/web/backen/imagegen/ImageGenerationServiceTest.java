package com.web.backen.imagegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.ImageGenerationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageGenerationServiceTest {
    @TempDir Path temp;
    private ImageGenerationService service;

    @AfterEach void close() { if (service != null) service.shutdown(); }

    @Test
    void completesOneRootTaskAndCreatesPreview() throws Exception {
        OpenAiImageClient client = mock(OpenAiImageClient.class);
        when(client.generate(anyString(), eq("1024x1024"), eq("medium"))).thenReturn(validPng());
        service = service(client, mock(QuotaService.class));

        ImageGenerationSession session = service.create("安静的森林", "GENERATE", "1024x1024", "medium",
                null, null, new AuthUser(1, "root", "ROOT", 0, true));
        awaitTerminal(session);

        assertEquals("completed", session.getStatus());
        assertTrue(java.nio.file.Files.isRegularFile(session.getResultPath()));
        assertTrue(java.nio.file.Files.isRegularFile(session.getPreviewPath()));
        assertEquals(1, service.recent(new AuthUser(1, "root", "ROOT", 0, true)).size());
    }

    @Test
    void failedPaidTaskIsRefundedAndProviderDetailIsHidden() throws Exception {
        OpenAiImageClient client = mock(OpenAiImageClient.class);
        when(client.generate(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("secret upstream body /tmp/private"));
        QuotaService quota = mock(QuotaService.class);
        when(quota.imageCredit("high")).thenReturn(8);
        when(quota.spend(eq(7L), eq(8), eq("IMAGE_GENERATION"), anyString(), anyString())).thenReturn(42L);
        service = service(client, quota);

        ImageGenerationSession session = service.create("测试", "GENERATE", "1024x1024", "high",
                null, null, new AuthUser(7, "user", "USER", 20, true));
        awaitTerminal(session);

        assertEquals("failed", session.getStatus());
        assertEquals("生图失败，请稍后重试", session.getErrorMessage());
        assertFalse(session.getErrorMessage().contains("secret"));
        verify(quota).refund(42L, "生图任务未完成退款");
    }

    @Test
    void rejectsInvalidReferenceAndCrossUserHistoryAccess() throws Exception {
        service = service(mock(OpenAiImageClient.class), mock(QuotaService.class));
        AuthUser root = new AuthUser(1, "root", "ROOT", 0, true);
        MockMultipartFile invalid = new MockMultipartFile("referenceFile", "bad.gif", "image/gif", "GIF89a".getBytes());
        assertThrows(IllegalArgumentException.class,
                () -> service.create("编辑", "EDIT", "1024x1024", "medium", null, invalid, root));

        OpenAiImageClient client = mock(OpenAiImageClient.class);
        when(client.generate(anyString(), anyString(), anyString())).thenReturn(validPng());
        service.shutdown(); service = service(client, mock(QuotaService.class));
        ImageGenerationSession session = service.create("测试归属", "GENERATE", "1024x1024", "medium", null, null, root);
        assertThrows(AuthException.class, () -> service.requireOwned(session.getTaskId(), new AuthUser(2, "other", "USER", 0, true)));
        assertDoesNotThrow(() -> service.requireReadable(session.getTaskId(), new AuthUser(3, "root-auditor", "ROOT", 0, true)));
        awaitTerminal(session);
    }

    @Test
    void rootCanAuditEveryRecentTaskWithoutGainingMutationOwnership() throws Exception {
        OpenAiImageClient client = mock(OpenAiImageClient.class);
        when(client.generate(anyString(), anyString(), anyString())).thenReturn(validPng());
        service = service(client, mock(QuotaService.class));
        AuthUser root = new AuthUser(1, "root", "ROOT", 0, true);
        AuthUser member = new AuthUser(2, "member", "USER", 0, true);
        ImageGenerationSession own = service.create("root work", "GENERATE", "1024x1024", "medium", null, null, root);
        ImageGenerationSession other = service.create("member work", "GENERATE", "1024x1024", "medium", null, null, member);

        awaitTerminal(own); awaitTerminal(other);

        assertEquals(2, service.recent(root).size());
        assertDoesNotThrow(() -> service.requireReadable(other.getTaskId(), root));
        assertThrows(AuthException.class, () -> service.requireOwned(other.getTaskId(), root));
    }

    @Test
    void insufficientBalanceDoesNotLeaveAQueuedTask() throws Exception {
        QuotaService quota = mock(QuotaService.class);
        when(quota.imageCredit("medium")).thenReturn(4);
        when(quota.spend(eq(9L), eq(4), anyString(), anyString(), anyString()))
                .thenThrow(new AuthException(402, "额度不足"));
        service = service(mock(OpenAiImageClient.class), quota);
        AuthUser user = new AuthUser(9, "poor", "USER", 0, true);

        AuthException error = assertThrows(AuthException.class,
                () -> service.create("测试", "GENERATE", "1024x1024", "medium", null, null, user));

        assertEquals(402, error.getStatus());
        assertTrue(service.recent(user).isEmpty());
    }

    private ImageGenerationService service(OpenAiImageClient client, QuotaService quota) throws Exception {
        ImageGenerationConfig config = new ImageGenerationConfig(); config.setStorageDir(temp.resolve(java.util.UUID.randomUUID().toString()).toString());
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.imageGenerationKey()).thenReturn("configured"); when(runtime.imageMaxHistory()).thenReturn(5); when(runtime.imageMaxGlobalHistory()).thenReturn(20);
        ImageGenerationService result = new ImageGenerationService(config, client, new ObjectMapper(), quota, runtime); result.initialize(); return result;
    }
    private void awaitTerminal(ImageGenerationSession session) throws InterruptedException {
        for (int i = 0; i < 100 && !java.util.Set.of("completed", "failed").contains(session.getStatus()); i++) Thread.sleep(10);
        assertTrue(java.util.Set.of("completed", "failed").contains(session.getStatus()), "task did not finish");
    }
    private byte[] validPng() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB); ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out); return out.toByteArray();
    }
}
