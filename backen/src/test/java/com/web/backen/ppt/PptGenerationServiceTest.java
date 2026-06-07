package com.web.backen.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PptGenerationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void protectsTasksAndRestoresStateWhenRenderQueueIsFull() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        AtomicBoolean blockExtraction = new AtomicBoolean(false);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);

        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt())).thenAnswer(invocation -> {
            if (blockExtraction.get()) {
                extractionStarted.countDown();
                assertTrue(releaseExtraction.await(3, TimeUnit.SECONDS));
            }
            return "";
        });
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenReturn(List.of());
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeck("original"));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            PptGenerationSession target = service.createTask("生成 3 页 PPT", null, null, 50, null, null);
            awaitStatus(service, target.getTaskId(), "outline_ready");
            JsonNode originalDeck = service.getDeck(target.getTaskId(), target.getAccessToken());

            assertThrows(IllegalArgumentException.class,
                    () -> service.getDeck(target.getTaskId(), "wrong-token"));
            assertTrue(service.getRecentSessions("wrong-token").isEmpty());
            assertEquals(target.getTaskId(), service.getRecentSessions(target.getAccessToken()).get(0).getTaskId());

            blockExtraction.set(true);
            PptGenerationSession blocker = service.createTask("阻塞任务", null, null, 50, null, null);
            assertTrue(extractionStarted.await(2, TimeUnit.SECONDS));
            service.createTask("占满等待队列", null, null, 50, null, null);

            JsonNode replacement = objectMapper.readTree(validDeck("replacement"));
            assertThrows(IllegalStateException.class,
                    () -> service.renderTask(target.getTaskId(), target.getAccessToken(), replacement, null, null));

            assertEquals("outline_ready", service.getSession(target.getTaskId()).getStatus());
            assertEquals(originalDeck, service.getDeck(target.getTaskId(), target.getAccessToken()));
            assertThrows(IllegalStateException.class,
                    () -> service.reviseDeck(target.getTaskId(), target.getAccessToken(), "修改标题", originalDeck));
            assertEquals("outline_ready", service.getSession(target.getTaskId()).getStatus());
            assertThrows(IllegalStateException.class,
                    () -> service.renderTask(blocker.getTaskId(), blocker.getAccessToken(), replacement, null, null));
        } finally {
            releaseExtraction.countDown();
            Thread.sleep(200);
            service.shutdown();
        }
    }

    private String validDeck(String title) {
        return """
                {"title":"%s","slides":[
                  {"type":"cover","title":"封面"},
                  {"type":"content","title":"内容"},
                  {"type":"thanks","title":"致谢"}
                ]}
                """.formatted(title);
    }

    private void awaitStatus(PptGenerationService service, String taskId, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(service.getSession(taskId).getStatus())) return;
            Thread.sleep(20);
        }
        fail("任务未进入状态: " + expected);
    }
}
