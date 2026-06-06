package com.web.backen.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.TranslationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void queuesTranslationAndKeepsRecentResultFilesOnDisk() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);

        when(pdfParseService.getTotalPages(any(Path.class))).thenReturn(3);
        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        String taskId = null;
        try {
            TranslationSession session = service.createSessionPreview(
                    "paper.pdf", new ByteArrayInputStream("pdf".getBytes()));
            taskId = session.getTaskId();
            assertTrue(Files.isRegularFile(session.getInputPdfPath()));

            service.startTranslation(session.getTaskId(), 1, 3, "auto", 4);
            awaitStatus(service, session.getTaskId(), "completed");

            TranslationSession completed = service.getSession(session.getTaskId());
            assertTrue(Files.isRegularFile(completed.getTranslatedPdfPath()));
            assertTrue(Files.isRegularFile(completed.getBilingualPdfPath()));
            assertEquals(session.getTaskId(), service.getRecentSessions().get(0).getTaskId());
        } finally {
            service.shutdown();
        }

        TranslationService restoredService = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        restoredService.initialize();
        try {
            assertNotNull(taskId);
            assertEquals(taskId, restoredService.getRecentSessions().get(0).getTaskId());
            assertEquals("completed", restoredService.getSession(taskId).getStatus());
        } finally {
            restoredService.shutdown();
        }
    }

    @Test
    void resumesQueuedTaskFromDiskAfterRestart() throws Exception {
        PdfParseService pdfParseService = mock(PdfParseService.class);
        BabelDocService babelDocService = mock(BabelDocService.class);
        TranslationConfig config = new TranslationConfig();
        config.setStorageDir(tempDir.toString());
        config.setMaxHistory(5);
        config.setQueueCapacity(2);
        config.setMaxQps(4);

        Path taskDir = Files.createDirectories(tempDir.resolve("resume01"));
        Files.writeString(taskDir.resolve("input.pdf"), "pdf");
        TranslationSession queued = new TranslationSession("resume01", "paper.pdf", taskDir);
        queued.setTotalPages(3);
        queued.setPageRange(1, 2);
        queued.setStatus("queued");
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(taskDir.resolve("task.json").toFile(), queued);

        when(babelDocService.translatePdf(any(Path.class), any(Path.class), anyString(),
                anyInt(), anyInt(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    Path resultDir = invocation.getArgument(1);
                    Path translated = resultDir.resolve("translated.pdf");
                    Path bilingual = resultDir.resolve("bilingual.pdf");
                    Files.writeString(translated, "translated");
                    Files.writeString(bilingual, "bilingual");
                    return new BabelDocService.TranslationResult(translated, bilingual);
                });

        TranslationService service = new TranslationService(
                pdfParseService, babelDocService, config, new ObjectMapper());
        service.initialize();
        try {
            awaitStatus(service, "resume01", "completed");
            verify(babelDocService).translatePdf(
                    eq(queued.getInputPdfPath()), eq(taskDir), eq("paper.pdf"), eq(1), eq(2), eq("auto"), eq(4), any());
        } finally {
            service.shutdown();
        }
    }

    private void awaitStatus(TranslationService service, String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(service.getSession(taskId).getStatus())) return;
            Thread.sleep(20);
        }
        fail("任务未进入状态: " + expected);
    }
}
