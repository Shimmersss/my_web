package com.web.backen.ppt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.PptGenerationConfig;
import com.web.backen.translate.LlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.mockito.ArgumentCaptor.forClass;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PptGenerationServiceTest {

    @TempDir
    Path tempDir;

    private record TemplateTaskResult(PptGenerationService service, PptGenerationSession session) {}

    @Test
    void directGenerationKeepsAuthorizationAndRejectsWhenQueueIsFull() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        Path renderer = createRendererStub();
        config.setRendererCommand("python3");
        config.setRendererScript(renderer.toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        AtomicBoolean blockExtraction = new AtomicBoolean(false);
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch releaseExtraction = new CountDownLatch(1);

        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
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
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeck("direct"));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            PptGenerationSession target = service.createTask("生成 3 页 PPT", null, 50, null, null);
            awaitStatus(service, target.getTaskId(), "completed");

            assertTrue(Files.isRegularFile(service.getOutput(target.getTaskId(), target.getAccessToken())));
            assertThrows(IllegalArgumentException.class,
                    () -> service.getAuthorizedSession(target.getTaskId(), "wrong-token"));
            assertTrue(service.getRecentSessions("wrong-token").isEmpty());
            assertEquals(target.getTaskId(), service.getRecentSessions(target.getAccessToken()).get(0).getTaskId());

            blockExtraction.set(true);
            service.createTask("阻塞任务", null, 50, null, null);
            assertTrue(extractionStarted.await(2, TimeUnit.SECONDS));
            service.createTask("占满等待队列", null, 50, null, null);

            assertThrows(IllegalStateException.class,
                    () -> service.createTask("队列外任务", null, 50, null, null));
            assertEquals("completed", service.getSession(target.getTaskId()).getStatus());
        } finally {
            releaseExtraction.countDown();
            Thread.sleep(200);
            service.shutdown();
        }
    }

    @Test
    void templateUploadUsesNativeTemplateFillPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createFailingRendererStub().toString());
        config.setTemplateFillCommand("python3");
        config.setTemplateFillScript(createTemplateFillStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":true,\"templateFit\":\"strong\",\"templateRoute\":\"native-fill\"}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(any(Path.class), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenAnswer(invocation -> {
            Path imagesDir = invocation.getArgument(0);
            Files.createDirectories(imagesDir);
            Path image = imagesDir.resolve("paper-figure-1.png");
            Files.write(image, Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="));
            return List.of(image.toString());
        });
        when(llmService.completeWithImages(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn("""
                        {"images":[{"index":1,"kind":"chart","title":"结果图","summary":"关键结果","bestUse":"结果页","importance":5,"useful":true,"layoutHint":"full-image"}]}
                        """);
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validFillPlan());

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            MockMultipartFile template = new MockMultipartFile(
                    "templateFile",
                    "template.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "template-bytes".getBytes(StandardCharsets.UTF_8));

            PptGenerationSession session = service.createTask("按上传模板生成 1 页 PPT", "academic-blue", 50, template, null);
            awaitStatus(service, session.getTaskId(), "completed");

            Path output = service.getOutput(session.getTaskId(), session.getAccessToken());
            assertEquals("native-template-fill", Files.readString(output, StandardCharsets.UTF_8));
            JsonNode fillPlan = objectMapper.readTree(session.getDeckJsonPath().toFile());
            assertEquals("template_fill_pptx_plan.v1", fillPlan.path("schema").asText());
            assertEquals("template.pptx", fillPlan.path("source_pptx").asText());
            assertTrue(Files.isRegularFile(session.getTaskDir().resolve("slide-library.json")));
            assertTrue(Files.isRegularFile(session.getTaskDir().resolve("fill-check-report.json")));
            JsonNode imageFillReport = objectMapper.readTree(session.getTaskDir().resolve("template-image-fill-report.json").toFile());
            assertEquals(1, imageFillReport.path("fillableRegionCount").asInt());
            assertEquals(1, imageFillReport.path("assignedImageCount").asInt());
            assertEquals("paper-figure-1", fillPlan.path("slides").get(0).path("image_edits").get(0).path("image_id").asText());
            assertEquals("s01_img9", fillPlan.path("slides").get(0).path("image_edits").get(0).path("region_id").asText());
            assertNotEquals("s01_img2", fillPlan.path("slides").get(0).path("image_edits").get(0).path("region_id").asText());
            verify(llmService, times(1)).complete(contains("template_fill_pptx_plan.v1"), anyString(), anyInt());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void templateUploadCanProduceStrippedPptxPackage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createFailingRendererStub().toString());
        config.setTemplateFillCommand("python3");
        config.setTemplateFillScript(createTemplateFillPptxStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":true,\"templateFit\":\"strong\",\"templateRoute\":\"native-fill\"}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(any(Path.class), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenReturn(List.of());
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validFillPlanWithoutImages());

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            MockMultipartFile template = new MockMultipartFile(
                    "templateFile",
                    "template.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "template-bytes".getBytes(StandardCharsets.UTF_8));

            PptGenerationSession session = service.createTask("按上传模板生成 1 页 PPT", "academic-blue", 50, template, null);
            awaitStatus(service, session.getTaskId(), "completed");

            Path output = service.getOutput(session.getTaskId(), session.getAccessToken());
            try (ZipFile zip = new ZipFile(output.toFile())) {
                assertNotNull(zip.getEntry("ppt/slides/slide1.xml"));
                assertNotNull(zip.getEntry("ppt/media/logo.png"));
                assertNull(zip.getEntry("ppt/media/content.png"));
                assertNull(zip.getEntry("ppt/charts/chart1.xml"));
                String slideXml = new String(zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(slideXml.contains("关键结果对比"));
                assertFalse(slideXml.contains("Old body should clear"));
            }
            JsonNode fillPlan = objectMapper.readTree(session.getDeckJsonPath().toFile());
            assertEquals(1, fillPlan.path("slides").size());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void directGenerationCreatesDeterministicVisualWhenImageIsMissing() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createRendererStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenAnswer(invocation -> {
            Path imagesDir = invocation.getArgument(0);
            if (!Files.isDirectory(imagesDir)) return List.of();
            try (var files = Files.list(imagesDir)) {
                return files.filter(Files::isRegularFile)
                        .map(path -> path.toAbsolutePath().toString())
                        .toList();
            }
        });
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn("""
                {"title":"答辩","slides":[
                  {"type":"cover","title":"封面"},
                  {"type":"content","title":"技术路线","bullets":["数据输入","模型训练","实验验证"],"layout":"image-top",
                   "visualSpec":{"type":"workflow","title":"技术路线","items":["数据输入","模型训练","结果分析"]}},
                  {"type":"thanks","title":"致谢"}
                ]}
                """);

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            PptGenerationSession session = service.createTask("生成 3 页技术分享 PPT", "academic-blue", 50, null, null);
            awaitStatus(service, session.getTaskId(), "completed");

            Path generated = session.getImagesDir().resolve("generated-visual-1.png");
            assertTrue(Files.isRegularFile(generated));
            JsonNode deck = objectMapper.readTree(session.getDeckJsonPath().toFile());
            assertEquals("generated-visual-1", deck.path("slides").get(1).path("imageId").asText());
            JsonNode manifest = objectMapper.readTree(session.getImageManifestPath().toFile());
            assertEquals("generated-visual-1", manifest.path("images").get(0).path("id").asText());
            assertTrue(manifest.path("images").get(0).path("useful").asBoolean());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void templateGeneratedVisualCreatesPngAndImageEdit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateTaskResult result = runTemplateTask(objectMapper, createTemplateFillStub("""
                [{
                    'slide_index': 1,
                    'page_type': 'content_candidate',
                    'text_summary': '技术路线',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': '技术路线',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'width': 800, 'height': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [{
                        'region_id': 's01_img1',
                        'shape_name': 'Content image',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'width': 620, 'height': 330}
                    }],
                    'tables': [],
                    'charts': []
                }]
                """), """
                {"images":[{"index":1,"kind":"other","title":"普通截图","summary":"无关页面","bestUse":"无","importance":1,"useful":false,"layoutHint":"none"}]}
                """, """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [{
                    "source_slide": 1,
                    "purpose": "content",
                    "replacements": [{"slot_id":"s01_sh5","text":"技术路线"}],
                    "image_edits": [],
                    "generated_visual": {"type":"workflow","title":"技术路线","items":["数据输入","模型训练","结果分析"]},
                    "table_edits": [],
                    "chart_edits": []
                  }]
                }
                """);
        try {
            Path generated = result.session().getImagesDir().resolve("generated-visual-1.png");
            assertTrue(Files.isRegularFile(generated));
            JsonNode fillPlan = objectMapper.readTree(result.session().getDeckJsonPath().toFile());
            JsonNode edit = fillPlan.path("slides").get(0).path("image_edits").get(0);
            assertEquals("generated-visual-1", edit.path("image_id").asText());
            assertEquals("s01_img1", edit.path("region_id").asText());
            JsonNode manifest = objectMapper.readTree(result.session().getImageManifestPath().toFile());
            assertTrue(manifest.path("images").findValuesAsText("id").contains("generated-visual-1"));
        } finally {
            result.service().shutdown();
        }
    }

    @Test
    void defenseDeckUsesExpandedImageBudgetAndBatchedVisionReview() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createRendererStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        AtomicInteger receivedImageBudget = new AtomicInteger(0);
        List<Integer> visionBatchSizes = new ArrayList<>();

        when(inputExtractor.extractPaperText(any(Path.class), eq("paper.pdf"), any(Path.class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    Path imagesDir = invocation.getArgument(2);
                    receivedImageBudget.set(invocation.getArgument(4));
                    assertEquals(15, invocation.getArgument(5, Integer.class));
                    Files.createDirectories(imagesDir);
                    byte[] png = Base64.getDecoder().decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
                    for (int index = 1; index <= 17; index++) {
                        Files.write(imagesDir.resolve(String.format("paper-figure-%02d.png", index)), png);
                    }
                    return "论文包含系统架构、技术路线、实验结果和对比分析";
                });
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenAnswer(invocation -> {
            Path imagesDir = invocation.getArgument(0);
            try (var files = Files.list(imagesDir)) {
                return files.filter(Files::isRegularFile)
                        .sorted()
                        .map(path -> path.toAbsolutePath().toString())
                        .toList();
            }
        });
        when(llmService.completeWithImages(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<Path> batch = invocation.getArgument(3, List.class);
                    visionBatchSizes.add(batch.size());
                    StringBuilder json = new StringBuilder("{\"images\":[");
                    for (int index = 1; index <= batch.size(); index++) {
                        if (index > 1) json.append(',');
                        json.append("{\"index\":").append(index)
                                .append(",\"kind\":\"chart\",\"title\":\"实验结果\",\"summary\":\"结果对比\",\"bestUse\":\"结果页\",\"importance\":5,\"useful\":true,\"layoutHint\":\"image-top\"}");
                    }
                    json.append("]}");
                    return json.toString();
                });
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeckWithSlides(25));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            MockMultipartFile paper = new MockMultipartFile(
                    "paperFile",
                    "paper.pdf",
                    "application/pdf",
                    "%PDF-1.4".getBytes(StandardCharsets.UTF_8));

            PptGenerationSession session = service.createTask("生成 25 页毕业答辩 PPT", "academic-blue", 100, null, paper);
            awaitStatus(service, session.getTaskId(), "completed");

            assertEquals(24, receivedImageBudget.get());
            assertEquals(List.of(8, 8, 1), visionBatchSizes);
            JsonNode manifest = objectMapper.readTree(session.getImageManifestPath().toFile());
            assertEquals(17, manifest.path("images").size());
            assertEquals("vision", manifest.path("source").asText());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void ordinaryPromptDoesNotReceiveDefenseOrTwentyTwoSlideDefaults() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createRendererStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenReturn(List.of());
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeck("公司介绍"));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            PptGenerationSession session = service.createTask("公司介绍 PPT", "academic-blue", 50, null, null);
            awaitStatus(service, session.getTaskId(), "completed");

            var systemCaptor = forClass(String.class);
            var payloadCaptor = forClass(String.class);
            verify(llmService).complete(systemCaptor.capture(), payloadCaptor.capture(), anyInt());
            assertFalse(systemCaptor.getValue().contains("22 slides"));
            assertFalse(systemCaptor.getValue().contains("thesis defense"));
            JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
            assertTrue(payload.path("targetSlideCount").isNull());
            assertFalse(payload.path("defenseMode").asBoolean(true));
            assertFalse(payload.path("slideCountPolicy").asText().contains("22"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void ordinaryTemplatePromptWithoutPaperDoesNotBatchAsTwentyTwoSlides() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path templateFillScript = createTemplateFillStub();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createFailingRendererStub().toString());
        config.setTemplateFillCommand("python3");
        config.setTemplateFillScript(templateFillScript.toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":true,\"templateFit\":\"strong\",\"templateRoute\":\"native-fill\"}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(any(Path.class), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenReturn(List.of());
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validFillPlan());

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            MockMultipartFile template = new MockMultipartFile(
                    "templateFile",
                    "template.pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "template-bytes".getBytes(StandardCharsets.UTF_8));
            PptGenerationSession session = service.createTask("公司介绍 PPT", "academic-blue", 50, template, null);
            awaitStatus(service, session.getTaskId(), "completed");

            var payloadCaptor = forClass(String.class);
            verify(llmService).complete(anyString(), payloadCaptor.capture(), anyInt());
            JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
            assertTrue(payload.path("targetSlideCount").isNull());
            assertFalse(payload.has("batchIndex"));
            assertFalse(payload.path("defenseMode").asBoolean(true));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void visionBatchFailureKeepsSuccessfulBatchAndConservativeFallback() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createRendererStub().toString());
        config.setMaxVisionImages(10);

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
        when(inputExtractor.extractPaperText(any(Path.class), eq("paper.pdf"), any(Path.class), anyInt(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    Path imagesDir = invocation.getArgument(2);
                    Files.createDirectories(imagesDir);
                    for (int index = 1; index <= 10; index++) {
                        Files.write(imagesDir.resolve("paper-page-" + index + ".png"), png);
                    }
                    return "论文包含实验结果";
                });
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenAnswer(invocation -> {
            Path imagesDir = invocation.getArgument(0);
            try (var files = Files.list(imagesDir)) {
                return files.filter(Files::isRegularFile).sorted().map(Path::toString).toList();
            }
        });
        AtomicInteger visionCalls = new AtomicInteger();
        when(llmService.completeWithImages(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> {
                    if (visionCalls.incrementAndGet() == 2) throw new IOException("vision batch failed");
                    return """
                            {"images":[
                              {"index":1,"kind":"chart","title":"结果图","summary":"结果对比","bestUse":"结果页","importance":5,"useful":true,"layoutHint":"image-top"}
                            ]}
                            """;
                });
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeckWithSlides(25));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            MockMultipartFile paper = new MockMultipartFile("paperFile", "paper.pdf", "application/pdf",
                    "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
            PptGenerationSession session = service.createTask("生成 25 页毕业答辩 PPT", "academic-blue", 100, null, paper);
            awaitStatus(service, session.getTaskId(), "completed");

            JsonNode manifest = objectMapper.readTree(session.getImageManifestPath().toFile());
            assertEquals("partial", manifest.path("source").asText());
            assertTrue(manifest.path("fallbackReason").asText().contains("batch 2"));
            assertEquals(10, manifest.path("images").size());
            assertTrue(manifest.path("images").get(0).path("useful").asBoolean());
            assertFalse(manifest.path("images").get(8).path("useful").asBoolean());
            assertTrue(manifest.path("images").get(8).path("importance").asInt() < 3);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void slideCountRepairFallbackCompletesDeckWhenMimoStillMissesCount() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createRendererStub().toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":false,\"templateFramework\":[]}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(isNull(), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenReturn(List.of());
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(validDeckWithSlides(3));

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        try {
            PptGenerationSession session = service.createTask("生成 5 页 PPT", "academic-blue", 50, null, null);
            awaitStatus(service, session.getTaskId(), "completed");
            JsonNode deck = objectMapper.readTree(session.getDeckJsonPath().toFile());
            assertEquals(5, deck.path("slides").size());
            assertTrue(deck.path("slideCountAdjustedLocally").asBoolean());
            assertTrue(deck.path("slides").get(3).path("localSlideCountFallback").asBoolean());
        } finally {
            service.shutdown();
        }
    }

    @Test
    void templateImageRegionsSupportLegacyGeometryAndAvoidDecorativeNameFalsePositives() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateTaskResult result = runTemplateTask(objectMapper, createTemplateFillStub("""
                [{
                    'slide_index': 1,
                    'page_type': 'content_candidate',
                    'text_summary': 'RGB heatmap result and pipeline diagram',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': '热力图结果',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'w': 800, 'h': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [{
                        'region_id': 's01_img1',
                        'shape_name': 'RGB heatmap pipeline diagram',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'w': 620, 'h': 330}
                    }],
                    'tables': [],
                    'charts': []
                }]
                """), """
                {"images":[{"index":1,"kind":"chart","title":"RGB heatmap","summary":"pipeline result","bestUse":"result heatmap","importance":5,"useful":true,"layoutHint":"full-image"}]}
                """, """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [{
                    "source_slide": 1,
                    "purpose": "result",
                    "replacements": [{"slot_id":"s01_sh5","text":"RGB heatmap result"}],
                    "image_edits": [],
                    "table_edits": [],
                    "chart_edits": []
                  }]
                }
                """);
        try {
            JsonNode fillPlan = objectMapper.readTree(result.session().getDeckJsonPath().toFile());
            JsonNode edit = fillPlan.path("slides").get(0).path("image_edits").get(0);
            assertEquals("paper-figure-1", edit.path("image_id").asText());
            assertEquals("s01_img1", edit.path("region_id").asText());
            assertEquals(620, edit.path("geometry").path("w").asInt());
            JsonNode report = objectMapper.readTree(result.session().getTaskDir().resolve("template-image-fill-report.json").toFile());
            assertEquals(1, report.path("fillableRegionCount").asInt());
            assertEquals(1, report.path("assignedImageCount").asInt());
        } finally {
            result.service().shutdown();
        }
    }

    @Test
    void templateCoverTocChapterAndEndingSlidesAreNotAutoFilledWithImages() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateTaskResult result = runTemplateTask(objectMapper, createTemplateFillStub("""
                [{
                    'slide_index': 1,
                    'page_type': 'cover',
                    'text_summary': '结果封面',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': '封面',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'width': 800, 'height': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [{
                        'region_id': 's01_img1',
                        'shape_name': 'Main visual',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'width': 620, 'height': 330}
                    }],
                    'tables': [],
                    'charts': []
                }, {
                    'slide_index': 2,
                    'page_type': 'toc',
                    'text_summary': '结果目录',
                    'slots': [],
                    'image_regions': [{
                        'region_id': 's02_img1',
                        'shape_name': 'Content image',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'width': 620, 'height': 330}
                    }],
                    'tables': [],
                    'charts': []
                }]
                """), """
                {"images":[{"index":1,"kind":"chart","title":"实验结果","summary":"结果对比","bestUse":"结果页","importance":5,"useful":true,"layoutHint":"full-image"}]}
                """, """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [{
                    "source_slide": 1,
                    "purpose": "result",
                    "replacements": [{"slot_id":"s01_sh5","text":"实验结果"}],
                    "image_edits": [{"image_id":"paper-figure-1","region_id":"s01_img1","caption":"封面主视觉"}],
                    "table_edits": [],
                    "chart_edits": []
                  }, {
                    "source_slide": 2,
                    "purpose": "result",
                    "replacements": [],
                    "image_edits": [{"image_id":"paper-figure-1","region_id":"s02_img1","caption":"目录图"}],
                    "table_edits": [],
                    "chart_edits": []
                  }]
                }
                """, "按上传模板生成 2 页 PPT");
        try {
            JsonNode fillPlan = objectMapper.readTree(result.session().getDeckJsonPath().toFile());
            assertEquals(0, fillPlan.path("slides").get(0).path("image_edits").size());
            assertEquals(0, fillPlan.path("slides").get(1).path("image_edits").size());
            JsonNode report = objectMapper.readTree(result.session().getTaskDir().resolve("template-image-fill-report.json").toFile());
            assertEquals(0, report.path("fillableRegionCount").asInt());
            assertEquals("no-fillable-template-regions", report.path("skippedReason").asText());
        } finally {
            result.service().shutdown();
        }
    }

    @Test
    void templateImagesAreNotAutoFilledWithoutSemanticMatch() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TemplateTaskResult result = runTemplateTask(objectMapper, createTemplateFillStub("""
                [{
                    'slide_index': 1,
                    'page_type': 'content_candidate',
                    'text_summary': '研究背景与系统需求',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': '研究背景',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'width': 800, 'height': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [{
                        'region_id': 's01_img1',
                        'shape_name': 'Content image',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'width': 620, 'height': 330}
                    }],
                    'tables': [],
                    'charts': []
                }]
                """), """
                {"images":[{"index":1,"kind":"chart","title":"消融实验","summary":"模型结果对比","bestUse":"实验结果页","importance":5,"useful":true,"layoutHint":"full-image"}]}
                """, """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [{
                    "source_slide": 1,
                    "purpose": "background",
                    "replacements": [{"slot_id":"s01_sh5","text":"研究背景与需求"}],
                    "image_edits": [],
                    "table_edits": [],
                    "chart_edits": []
                  }]
                }
                """);
        try {
            JsonNode fillPlan = objectMapper.readTree(result.session().getDeckJsonPath().toFile());
            assertEquals(0, fillPlan.path("slides").get(0).path("image_edits").size());
            JsonNode report = objectMapper.readTree(result.session().getTaskDir().resolve("template-image-fill-report.json").toFile());
            assertEquals(1, report.path("fillableRegionCount").asInt());
            assertEquals(0, report.path("assignedImageCount").asInt());
            assertEquals("no-semantic-match", report.path("skippedReason").asText());
        } finally {
            result.service().shutdown();
        }
    }

    private Path createRendererStub() throws Exception {
        Path script = tempDir.resolve("renderer_stub.py");
        Files.writeString(script, """
                import pathlib, sys
                out = pathlib.Path(sys.argv[sys.argv.index('--out') + 1])
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(b'pptx')
                print('{\"ok\": true}')
                """, StandardCharsets.UTF_8);
        return script;
    }

    private Path createFailingRendererStub() throws Exception {
        Path script = tempDir.resolve("renderer_fails_if_used.py");
        Files.writeString(script, """
                import sys
                print('renderer should not run for uploaded template tasks')
                sys.exit(7)
                """, StandardCharsets.UTF_8);
        return script;
    }

    private Path createTemplateFillStub() throws Exception {
        return createTemplateFillStub("""
                [{
                    'slide_index': 1,
                    'page_type': 'content_candidate',
                    'text_summary': '方法结果内容页',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': '原模板标题',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'w': 800, 'h': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [{
                        'region_id': 's01_img2',
                        'shape_name': 'Logo 标志',
                        'role': 'decorative',
                        'fillable': False,
                        'rejectReason': 'decorative_name',
                        'geometry': {'x': 24, 'y': 24, 'width': 100, 'height': 100}
                    }, {
                        'region_id': 's01_img9',
                        'shape_name': '内容图片占位符',
                        'role': 'content_image',
                        'fillable': True,
                        'rejectReason': '',
                        'geometry': {'x': 100, 'y': 180, 'width': 600, 'height': 320}
                    }],
                    'tables': [],
                    'charts': []
                }]
                """);
    }

    private Path createTemplateFillPptxStub() throws Exception {
        Path script = tempDir.resolve("template_fill_pptx_stub.py");
        Files.writeString(script, """
                import json, pathlib, sys, zipfile

                command = sys.argv[1]
                slides = [{
                    'slide_index': 1,
                    'page_type': 'content_candidate',
                    'text_summary': '旧模板正文',
                    'slots': [{
                        'slot_id': 's01_sh5',
                        'role': 'title',
                        'text': 'Old title',
                        'paragraph_count': 1,
                        'geometry': {'x': 100, 'y': 100, 'width': 800, 'height': 80},
                        'text_metrics': {'max_chars': 24}
                    }],
                    'image_regions': [],
                    'tables': [],
                    'charts': []
                }]

                if command == 'analyze':
                    out = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    out.write_text(json.dumps({
                        'schema': 'template_fill_pptx_library.v1',
                        'slide_count': 1,
                        'canvas_px': {'width': 1280, 'height': 720},
                        'slides': slides
                    }), encoding='utf-8')
                    sys.exit(0)

                if command == 'check-plan':
                    report = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    report.write_text(json.dumps({'ok': True}), encoding='utf-8')
                    sys.exit(0)

                if command == 'apply':
                    if '--strip-source-content' not in sys.argv:
                        print('missing --strip-source-content')
                        sys.exit(9)
                    plan = json.loads(pathlib.Path(sys.argv[3]).read_text(encoding='utf-8'))
                    text = plan['slides'][0]['replacements'][0]['text']
                    out = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    out.parent.mkdir(parents=True, exist_ok=True)
                    P = 'http://schemas.openxmlformats.org/presentationml/2006/main'
                    A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
                    R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
                    REL = 'http://schemas.openxmlformats.org/package/2006/relationships'
                    slide = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <p:sld xmlns:p="{P}" xmlns:a="{A}" xmlns:r="{R}"><p:cSld><p:spTree>
                <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>{text}</a:t></a:r></a:p></p:txBody></p:sp>
                <p:pic><p:nvPicPr><p:cNvPr id="5" name="Logo"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdLogo"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr/></p:pic>
                </p:spTree></p:cSld></p:sld>'''
                    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as archive:
                        archive.writestr('[Content_Types].xml', '''<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>
                <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                </Types>''')
                        archive.writestr('_rels/.rels', f'''<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>''')
                        archive.writestr('ppt/presentation.xml', f'''<p:presentation xmlns:p="{P}" xmlns:r="{R}"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>''')
                        archive.writestr('ppt/_rels/presentation.xml.rels', f'''<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>''')
                        archive.writestr('ppt/slides/slide1.xml', slide)
                        archive.writestr('ppt/slides/_rels/slide1.xml.rels', f'''<Relationships xmlns="{REL}"><Relationship Id="rIdLogo" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/logo.png"/></Relationships>''')
                        archive.writestr('ppt/media/logo.png', b'logo-image')
                    sys.exit(0)

                print('unknown command', command)
                sys.exit(2)
                """, StandardCharsets.UTF_8);
        return script;
    }

    private Path createTemplateFillStub(String slideLibrarySlidesPython) throws Exception {
        Path script = tempDir.resolve("template_fill_stub.py");
        Files.writeString(script, """
                import json, pathlib, sys

                slides = None
                command = sys.argv[1]
                if command == 'analyze':
                    out = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    out.write_text(json.dumps({
                        'schema': 'template_fill_pptx_library.v1',
                        'slide_count': len(slides),
                        'canvas_px': {'width': 1280, 'height': 720},
                        'slides': slides
                    }), encoding='utf-8')
                    sys.exit(0)

                if command == 'check-plan':
                    report = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    report.write_text(json.dumps({'ok': True}), encoding='utf-8')
                    sys.exit(0)

                if command == 'apply':
                    if '--strip-source-content' not in sys.argv:
                        print('missing --strip-source-content')
                        sys.exit(9)
                    out = pathlib.Path(sys.argv[sys.argv.index('-o') + 1])
                    out.write_text('native-template-fill', encoding='utf-8')
                    sys.exit(0)

                print('unknown command', command)
                sys.exit(2)
                """.replace("slides = None", "slides = " + slideLibrarySlidesPython.replace('\'', '"')),
                StandardCharsets.UTF_8);
        return script;
    }

    private TemplateTaskResult runTemplateTask(ObjectMapper objectMapper, Path templateFillScript,
                                               String visionResponse, String fillPlanResponse) throws Exception {
        return runTemplateTask(objectMapper, templateFillScript, visionResponse, fillPlanResponse, "按上传模板生成 1 页 PPT");
    }

    private TemplateTaskResult runTemplateTask(ObjectMapper objectMapper, Path templateFillScript,
                                               String visionResponse, String fillPlanResponse,
                                               String prompt) throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setStorageDir(tempDir.toString());
        config.setQueueCapacity(1);
        config.setMaxHistory(10);
        config.setRendererCommand("python3");
        config.setRendererScript(createFailingRendererStub().toString());
        config.setTemplateFillCommand("python3");
        config.setTemplateFillScript(templateFillScript.toString());

        PptInputExtractor inputExtractor = mock(PptInputExtractor.class);
        LlmService llmService = mock(LlmService.class);
        when(inputExtractor.extractPaperText(isNull(), isNull(), any(Path.class), anyInt(), anyInt(), anyInt())).thenReturn("");
        doAnswer(invocation -> {
            Path taskDir = invocation.getArgument(1);
            objectMapper.writeValue(taskDir.resolve("style.json").toFile(),
                    objectMapper.readTree("{\"palette\":[],\"frameworkMode\":true,\"templateFit\":\"strong\",\"templateRoute\":\"native-fill\"}"));
            return null;
        }).when(inputExtractor).extractTemplateStyle(any(Path.class), any(Path.class), anyInt());
        when(inputExtractor.listImagePaths(any())).thenAnswer(invocation -> {
            Path imagesDir = invocation.getArgument(0);
            Files.createDirectories(imagesDir);
            Path image = imagesDir.resolve("paper-figure-1.png");
            Files.write(image, Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="));
            return List.of(image.toString());
        });
        when(llmService.completeWithImages(anyString(), anyString(), anyString(), anyList(), anyInt()))
                .thenReturn(visionResponse);
        when(llmService.complete(anyString(), anyString(), anyInt())).thenReturn(fillPlanResponse);

        PptGenerationService service = new PptGenerationService(config, inputExtractor, llmService, objectMapper);
        service.initialize();
        MockMultipartFile template = new MockMultipartFile(
                "templateFile",
                "template.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "template-bytes".getBytes(StandardCharsets.UTF_8));

        PptGenerationSession session = service.createTask(prompt, "academic-blue", 50, template, null);
        awaitStatus(service, session.getTaskId(), "completed");
        return new TemplateTaskResult(service, session);
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

    private String validDeckWithSlides(int count) {
        StringBuilder builder = new StringBuilder("{\"title\":\"答辩\",\"slides\":[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) builder.append(',');
            String type = index == 1 ? "cover" : index == count ? "thanks" : "content";
            builder.append("{\"type\":\"").append(type).append("\",\"title\":\"第")
                    .append(index).append("页\",\"bullets\":[\"要点一\",\"要点二\",\"要点三\"]}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private String validFillPlan() {
        return """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [
                    {
                      "source_slide": 1,
                      "purpose": "result",
                      "replacements": [{"slot_id":"s01_sh5","text":"关键结果对比"}],
                      "image_edits": [{"image_id":"paper-figure-1","region_id":"s01_img2","caption":"模型误选装饰 logo 区域"}],
                      "table_edits": [],
                      "chart_edits": []
                    }
                  ]
                }
                """;
    }

    private String validFillPlanWithoutImages() {
        return """
                {
                  "schema": "template_fill_pptx_plan.v1",
                  "slides": [
                    {
                      "source_slide": 1,
                      "purpose": "result",
                      "replacements": [{"slot_id":"s01_sh5","text":"关键结果对比"}],
                      "image_edits": [],
                      "table_edits": [],
                      "chart_edits": []
                    }
                  ]
                }
                """;
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
