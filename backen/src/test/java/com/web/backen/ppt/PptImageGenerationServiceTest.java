package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.imagegen.OpenAiImageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PptImageGenerationServiceTest {
    @TempDir Path temp;

    @Test
    void writesOnlyBoundedPngAssetsFromOpenAiCompatibleBase64Response() throws Exception {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.imageGenerationKey()).thenReturn("test-secret");
        when(runtime.imageGenerationModel()).thenReturn("gpt-image-2");
        when(runtime.imageGenerationQuality()).thenReturn("medium");
        when(runtime.imageGenerationMaxImages()).thenReturn(2);
        OpenAiImageClient imageClient = mock(OpenAiImageClient.class);
        // PNG signature plus enough bytes to clear the fixed lower-size guard.
        byte[] png = new byte[256]; png[0] = (byte) 0x89; png[1] = 0x50; png[2] = 0x4e; png[3] = 0x47;
        when(imageClient.generate(any(), eq("1536x1024"), eq("medium"))).thenReturn(png);

        PptImageGenerationService service = new PptImageGenerationService(runtime, new ObjectMapper(), imageClient);
        PptGenerationSession session = new PptGenerationSession("test", "AI 产品发布", temp);
        session.setImageGenerationMode("supplement");
        Path output = temp.resolve("generated-images");

        service.generate(session, output, (stage, payload) -> {});

        assertTrue(Files.isRegularFile(output.resolve("gpt-1.png")));
        assertTrue(Files.isRegularFile(output.resolve("gpt-2.png")));
        assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
        assertTrue(Files.isRegularFile(temp.resolve("presentation-plan.json")));
        assertTrue(Files.readString(output.resolve("generated-image-manifest.json")).contains("slideId"));
        assertFalse(Files.readString(output.resolve("manifest.json")).contains("test-secret"));
    }

    @Test
    void offModeDoesNotReadOrCallCredentials() throws Exception {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        PptImageGenerationService service = new PptImageGenerationService(runtime,
                new ObjectMapper(), mock(OpenAiImageClient.class));
        PptGenerationSession session = new PptGenerationSession("test", "无图", temp);
        session.setImageGenerationMode("off");

        service.generate(session, temp.resolve("not-created"), (stage, payload) -> {});

        assertFalse(Files.exists(temp.resolve("not-created")));
    }

    @Test
    void preferModeSupportsTenDistinctBoundedImageSlots() throws Exception {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.imageGenerationKey()).thenReturn("test-secret");
        when(runtime.imageGenerationModel()).thenReturn("gpt-image-2");
        when(runtime.imageGenerationQuality()).thenReturn("medium");
        when(runtime.imageGenerationMaxImages()).thenReturn(10);
        OpenAiImageClient imageClient = mock(OpenAiImageClient.class);
        byte[] png = new byte[256]; png[0] = (byte) 0x89; png[1] = 0x50; png[2] = 0x4e; png[3] = 0x47;
        when(imageClient.generate(any(), eq("1536x1024"), eq("medium"))).thenReturn(png);
        PptImageGenerationService service = new PptImageGenerationService(runtime, new ObjectMapper(), imageClient);
        PptGenerationSession session = new PptGenerationSession("test", "AI 产品发布", temp);
        session.setImageGenerationMode("prefer");
        session.setRequestedImageGenerationCount(10);

        service.generate(session, temp.resolve("ten-generated-images"), (stage, payload) -> {});

        assertEquals(10, PptImageGenerationService.requestedImageCount("prefer", 10, 10));
        assertTrue(Files.isRegularFile(temp.resolve("ten-generated-images/gpt-10.png")));
    }
}
