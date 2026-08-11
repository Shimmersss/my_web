package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

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
        when(runtime.imageGenerationEndpoint()).thenReturn("https://images.example.test/v1/images/generations");
        when(runtime.imageGenerationModel()).thenReturn("gpt-image-2");
        when(runtime.imageGenerationQuality()).thenReturn("medium");
        when(runtime.imageGenerationMaxImages()).thenReturn(2);
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        // PNG signature plus enough bytes to clear the fixed lower-size guard.
        byte[] png = new byte[256]; png[0] = (byte) 0x89; png[1] = 0x50; png[2] = 0x4e; png[3] = 0x47;
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png) + "\"}]}");
        when(http.<String>send(any(), any())).thenReturn(response);

        PptGenerationConfig config = new PptGenerationConfig();
        PptImageGenerationService service = new PptImageGenerationService(config, runtime, new ObjectMapper(), http);
        PptGenerationSession session = new PptGenerationSession("test", "AI 产品发布", temp);
        session.setImageGenerationMode("supplement");
        Path output = temp.resolve("generated-images");

        service.generate(session, output, (stage, payload) -> {});

        assertTrue(Files.isRegularFile(output.resolve("ai-1.png")));
        assertTrue(Files.isRegularFile(output.resolve("ai-2.png")));
        assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
        assertFalse(Files.readString(output.resolve("manifest.json")).contains("test-secret"));
    }

    @Test
    void offModeDoesNotReadOrCallCredentials() throws Exception {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        PptImageGenerationService service = new PptImageGenerationService(new PptGenerationConfig(), runtime,
                new ObjectMapper(), mock(HttpClient.class));
        PptGenerationSession session = new PptGenerationSession("test", "无图", temp);
        session.setImageGenerationMode("off");

        service.generate(session, temp.resolve("not-created"), (stage, payload) -> {});

        assertFalse(Files.exists(temp.resolve("not-created")));
    }
}
