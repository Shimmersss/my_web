package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PptGenerationServiceTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @TempDir
    Path tempDir;

    @Test
    void htmlAgentTaskPersistsRealPreviewResearchModeAndAccessControl() throws Exception {
        PptGenerationService service = service(successfulRunner(), null);
        AuthUser owner = new AuthUser(7, "owner", "USER", 100, true);
        try {
            PptGenerationSession task = service.createTask(
                    "Agent deck", "html-reveal-night", 100, null, null,
                    owner, "create-1", "html", "off");
            awaitStatus(service, task.getTaskId(), "completed");

            PptGenerationSession completed = service.getSession(task.getTaskId());
            assertEquals("off", completed.getResearchMode());
            assertTrue(completed.isQaValid());
            assertTrue(service.canAccess(completed, owner));
            assertFalse(service.canAccess(completed, new AuthUser(8, "other", "USER", 100, true)));
            assertEquals(completed.getTaskId(), service.getAuthorizedSession(completed.getTaskId(), completed.getAccessToken()).getTaskId());

            Map<String, Object> preview = service.preview(completed);
            assertEquals("html", preview.get("format"));
            assertEquals(1, ((java.util.List<?>) preview.get("slides")).size());
            assertTrue(Files.isRegularFile(service.previewImage(completed, "slide-1.png")));
            assertThrows(IllegalArgumentException.class, () -> service.previewImage(completed, "../metadata.json"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void htmlUsesFormatSpecificTemplateAndRejectsPptxUpload() throws Exception {
        PptGenerationService service = service(successfulRunner(), null);
        try {
            PptGenerationSession task = service.createTask(
                    "HTML deck", "", 100, null, null,
                    null, null, "html", "off");
            assertEquals("html-reveal-white", task.getTemplateKey());
            awaitStatus(service, task.getTaskId(), "completed");
            assertThrows(IllegalArgumentException.class, () -> service.createTask(
                    "HTML deck", "github-bjtu-blue", 100,
                    new MockMultipartFile("templateFile", "template.pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            new byte[]{0x50, 0x4b, 0x03, 0x04}),
                    null, null, null, "html", "off"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void idempotentCreateAndNaturalLanguageRevisionKeepVersionChain() throws Exception {
        PptGenerationService service = service(successfulRunner(), null);
        AuthUser root = new AuthUser(1, "root", "ROOT", 0, true);
        try {
            PptGenerationSession first = service.createTask(
                    "First", "html-reveal-white", 100, null, null,
                    root, "same-request", "html", "auto");
            PptGenerationSession duplicate = service.createTask(
                    "First", "html-reveal-white", 100, null, null,
                    root, "same-request", "html", "auto");
            assertEquals(first.getTaskId(), duplicate.getTaskId());
            awaitStatus(service, first.getTaskId(), "completed");

            assertThrows(IllegalArgumentException.class,
                    () -> service.createRevisionTask(first, " ", java.util.List.of(), root, "rev-empty"));
            PptGenerationSession revision = service.createRevisionTask(
                    first, "把结论页改成三项行动计划", java.util.List.of(), root, "rev-1");
            awaitStatus(service, revision.getTaskId(), "completed");
            assertEquals(first.getTaskId(), revision.getRevisionOfTaskId());
            assertEquals("把结论页改成三项行动计划", revision.getRevisionPrompt());
            assertTrue(Files.isRegularFile(revision.getTaskDir().resolve("previous-agent-plan.json")));
            assertTrue(Files.isRegularFile(revision.getTaskDir().resolve("previous-sources.json")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void agentFailureRefundsCreditsAndLeavesDiagnosticsState() throws Exception {
        PptAgentRunner runner = mock(PptAgentRunner.class);
        doThrow(new IllegalStateException("visual QA failed")).when(runner).run(any(), any(), any());
        QuotaService quota = mock(QuotaService.class);
        when(quota.pptCreditPerTask()).thenReturn(10);
        when(quota.spend(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenReturn(42L);
        PptGenerationService service = service(runner, quota);
        try {
            AuthUser owner = new AuthUser(9, "owner", "USER", 100, true);
            PptGenerationSession task = service.createTask(
                    "Fail", "html-reveal-black", 100, null, null,
                    owner, "fail-1", "html", "off");
            awaitStatus(service, task.getTaskId(), "error");
            assertTrue(service.getSession(task.getTaskId()).getErrorMessage().contains("visual QA failed"));
            verify(quota, timeout(1000)).refund(eq(42L), contains("失败"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void interruptedTaskIsRequeuedAndRecoversAfterServiceRestart() throws Exception {
        PptGenerationService first = service(successfulRunner(), null);
        PptGenerationSession task;
        try {
            task = first.createTask("Recover", "html-reveal-sky", 100,
                    null, null, null, null, "html", "off");
            awaitStatus(first, task.getTaskId(), "completed");
            task.setStatus("generating");
            task.setProgressStage("rendering");
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(task.getMetadataPath().toFile(), task);
        } finally {
            first.shutdown();
        }

        PptAgentRunner recoveredRunner = successfulRunner();
        PptGenerationService second = service(recoveredRunner, null);
        try {
            awaitStatus(second, task.getTaskId(), "completed");
            PptGenerationSession restored = second.getSession(task.getTaskId());
            assertNotNull(restored);
            assertEquals("completed", restored.getStatus());
            assertTrue(Files.isRegularFile(restored.getHtmlOutputPath()));
            verify(recoveredRunner, timeout(1000)).run(eq(restored), any(), any());
        } finally {
            second.shutdown();
        }
    }

    @Test
    void partialCreatingTaskFindsHiddenChargeAndRefundsInsteadOfRunning() throws Exception {
        Path taskDir = Files.createDirectories(tempDir.resolve("partial01"));
        PptGenerationSession creating = new PptGenerationSession("partial01", "Partial", taskDir);
        creating.setStatus("creating");
        creating.setProgressStage("creating");
        creating.setQuotaRequired(true);
        creating.setCreationReady(false);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(creating.getMetadataPath().toFile(), creating);
        QuotaService quota = mock(QuotaService.class);
        when(quota.findSpendTransactionId("partial01")).thenReturn(77L);

        PptGenerationService service = service(successfulRunner(), quota);
        try {
            assertEquals("error", service.getSession("partial01").getStatus());
            verify(quota).refund(eq(77L), contains("创建中断"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void readyCreatingTaskHydratesChargeAndResumes() throws Exception {
        Path taskDir = Files.createDirectories(tempDir.resolve("ready001"));
        PptGenerationSession creating = new PptGenerationSession("ready001", "Ready", taskDir);
        creating.setOutputFormat("html");
        creating.setTemplateKey("html-reveal-white");
        creating.setStatus("creating");
        creating.setProgressStage("creating");
        creating.setQuotaRequired(true);
        creating.setCreationReady(true);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(creating.getMetadataPath().toFile(), creating);
        QuotaService quota = mock(QuotaService.class);
        when(quota.findSpendTransactionId("ready001")).thenReturn(88L);

        PptGenerationService service = service(successfulRunner(), quota);
        try {
            awaitStatus(service, "ready001", "completed");
            assertEquals(88L, service.getSession("ready001").getCreditTransactionId());
            verify(quota, never()).refund(eq(88L), anyString());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void queueRemainsSingleWorkerAndBounded() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PptAgentRunner runner = mock(PptAgentRunner.class);
        doAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            writeAgentArtifacts(invocation.getArgument(0));
            return null;
        }).when(runner).run(any(), any(), any());
        PptGenerationService service = service(runner, null);
        try {
            PptGenerationSession one = service.createTask("one", "html-reveal-black", 100, null, null, null, null, "html", "off");
            assertTrue(started.await(1, TimeUnit.SECONDS));
            PptGenerationSession two = service.createTask("two", "html-reveal-black", 100, null, null, null, null, "html", "off");
            assertThrows(IllegalStateException.class,
                    () -> service.createTask("three", "html-reveal-black", 100, null, null, null, null, "html", "off"));
            release.countDown();
            awaitStatus(service, one.getTaskId(), "completed");
            awaitStatus(service, two.getTaskId(), "completed");
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    private PptGenerationService service(PptAgentRunner runner, QuotaService quota) throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        PptInputExtractor extractor = mock(PptInputExtractor.class);
        when(extractor.extractPaperText(any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn("");
        PptGenerationService service = new PptGenerationService(
                config, extractor, mock(LlmService.class), new ObjectMapper(), quota, runner);
        service.initialize();
        return service;
    }

    private PptAgentRunner successfulRunner() throws Exception {
        PptAgentRunner runner = mock(PptAgentRunner.class);
        doAnswer(invocation -> {
            PptGenerationSession session = invocation.getArgument(0);
            writeAgentArtifacts(session);
            return null;
        }).when(runner).run(any(), any(), any());
        return runner;
    }

    private static void writeAgentArtifacts(PptGenerationSession session) throws Exception {
        Files.createDirectories(session.getPreviewDir());
        Files.writeString(session.getHtmlOutputPath(),
                "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><div class=\"reveal\"><div class=\"slides\"><section>Agent output</section></div></div>"
                        + "x".repeat(300) + "</body></html>", StandardCharsets.UTF_8);
        Files.write(session.getPreviewDir().resolve("slide-1.png"), PNG);
        Files.writeString(session.getAgentPlanPath(),
                "{\"title\":\"Agent\",\"slides\":[{\"title\":\"Agent output\",\"sourceIds\":[]}]}",
                StandardCharsets.UTF_8);
        Files.writeString(session.getSourcesPath(), "{\"sources\":[],\"degraded\":false}", StandardCharsets.UTF_8);
        Files.writeString(session.getTaskDir().resolve("quality-report.json"),
                "{\"valid\":true,\"slideCount\":1,\"overflowSlides\":[],\"visualReview\":{\"valid\":true,\"issues\":[]}}",
                StandardCharsets.UTF_8);
        Files.writeString(session.getPreviewPath(),
                "{\"format\":\"html\",\"title\":\"Agent\",\"slides\":[{\"index\":1,\"title\":\"Agent output\",\"imageFile\":\"slide-1.png\",\"width\":1280,\"height\":720,\"sourceIds\":[]}],\"sources\":[],\"qa\":{\"valid\":true}}",
                StandardCharsets.UTF_8);
    }

    private static void awaitStatus(PptGenerationService service, String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            PptGenerationSession session = service.getSession(taskId);
            if (session != null && expected.equals(session.getStatus())) return;
            Thread.sleep(20);
        }
        fail("任务未进入状态: " + expected);
    }
}
