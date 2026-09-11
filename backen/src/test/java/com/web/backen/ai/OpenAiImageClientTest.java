package com.web.backen.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.ImageGenerationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OpenAiImageClientTest {
    @TempDir Path temp;

    @Test
    void sendsGenerationJsonAndAcceptsOnlyBoundedBase64Png() throws Exception {
        Fixture fixture = fixture();
        byte[] output = fixture.client.generate("quiet forest", "1024x1024", "low");

        assertEquals(256, output.length);
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.http).send(request.capture(), any());
        assertEquals("https://images.test/v1/images/generations", request.getValue().uri().toString());
        assertEquals("Bearer secret-key", request.getValue().headers().firstValue("Authorization").orElseThrow());
    }

    @Test
    void sendsEditAsMultipartToDerivedEndpoint() throws Exception {
        Fixture fixture = fixture();
        Path reference = temp.resolve("reference.png");
        Files.write(reference, png());

        fixture.client.edit("make it warmer", "1024x1536", "high", reference, "image/png");

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(fixture.http).send(request.capture(), any());
        assertEquals("https://images.test/v1/images/edits", request.getValue().uri().toString());
        assertTrue(request.getValue().headers().firstValue("Content-Type").orElseThrow().startsWith("multipart/form-data; boundary="));
    }

    @Test
    void refusesRemoteUrlResponse() throws Exception {
        RuntimeConfigService runtime = runtime();
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream("{\"data\":[{\"url\":\"https://remote.test/image.png\"}]}".getBytes()));
        when(http.<java.io.InputStream>send(any(), any())).thenReturn(response);
        OpenAiImageClient client = new OpenAiImageClient(runtime, new ImageGenerationConfig(), new ObjectMapper(), http);

        assertThrows(OpenAiImageClient.ImageProviderException.class,
                () -> client.generate("prompt", "1024x1024", "medium"));
    }

    private Fixture fixture() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> response = mock(HttpResponse.class);
        String body = "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(png()) + "\"}]}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes()));
        when(http.<java.io.InputStream>send(any(), any())).thenReturn(response);
        return new Fixture(new OpenAiImageClient(runtime(), new ImageGenerationConfig(), new ObjectMapper(), http), http);
    }

    private RuntimeConfigService runtime() {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.imageGenerationKey()).thenReturn("secret-key");
        when(runtime.imageGenerationModel()).thenReturn("gpt-image-2");
        when(runtime.imageGenerationEndpoint()).thenReturn("https://images.test/v1/images/generations");
        when(runtime.imageEditEndpoint()).thenReturn("https://images.test/v1/images/edits");
        return runtime;
    }

    private byte[] png() {
        byte[] onePixel = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        return java.util.Arrays.copyOf(onePixel, 256);
    }
    private record Fixture(OpenAiImageClient client, HttpClient http) {}
}
