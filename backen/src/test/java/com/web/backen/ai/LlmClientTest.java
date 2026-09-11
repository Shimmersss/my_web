package com.web.backen.ai;

import com.web.backen.settings.RuntimeConfigService;
import com.web.backen.config.LlmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmClientTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI", "CLAUDE"})
    void isolatedProviderKeepsProtocolCredentialsAndDomainPrompts(String protocol) {
        Fixture fixture = fixture(protocol);
        var request = fixture.server.expect(requestTo(fixture.endpoint))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("report-model"))
                .andExpect(jsonPath("$.max_tokens").value(12000));
        if (protocol.equals("CLAUDE")) {
            request.andExpect(header("x-api-key", "report-key"))
                    .andExpect(header("anthropic-version", "2023-06-01"))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andExpect(jsonPath("$.system").value("domain system prompt"))
                    .andExpect(jsonPath("$.messages[0].content").value("domain user prompt"));
        } else {
            request.andExpect(header("Authorization", "Bearer report-key"))
                    .andExpect(headerDoesNotExist("x-api-key"))
                    .andExpect(headerDoesNotExist("anthropic-version"))
                    .andExpect(jsonPath("$.messages[0].content").value("domain system prompt"))
                    .andExpect(jsonPath("$.messages[1].content").value("domain user prompt"));
        }
        request.andRespond(withSuccess(response(protocol), MediaType.APPLICATION_JSON));

        assertEquals("result", fixture.client.completeWithConfig("https://provider.test/v1", "report-key",
                "report-model", protocol, "domain system prompt", "domain user prompt", 12000));
        fixture.server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPENAI", "CLAUDE"})
    void visionEncodesRealImagesInProviderFormat(String protocol) throws Exception {
        Fixture fixture = fixture(protocol);
        Path source = temp.resolve("source.png");
        ImageIO.write(new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB), "png", source.toFile());
        var request = fixture.server.expect(requestTo(fixture.endpoint))
                .andExpect(jsonPath("$.model").value("vision-model"))
                .andExpect(jsonPath("$.max_tokens").value(2048));
        if (protocol.equals("CLAUDE")) {
            request.andExpect(jsonPath("$.messages[0].content[0].text").value("read this image"))
                    .andExpect(jsonPath("$.messages[0].content[1].source.media_type").value("image/jpeg"))
                    .andExpect(jsonPath("$.messages[0].content[1].source.data").value(startsWith("/9j/")));
        } else {
            request.andExpect(jsonPath("$.messages[1].content[0].text").value("read this image"))
                    .andExpect(jsonPath("$.messages[1].content[1].image_url.url")
                            .value(startsWith("data:image/jpeg;base64,/9j/")));
        }
        request.andRespond(withSuccess(response(protocol), MediaType.APPLICATION_JSON));

        assertEquals("result", fixture.client.completeWithImages("vision-model", "vision instruction",
                "read this image", List.of(source), 2048));
        fixture.server.verify();
    }

    @Test
    void authorizationFailureIsNotRetried() {
        Fixture fixture = fixture("OPENAI");
        fixture.server.expect(requestTo(fixture.endpoint)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThrows(RuntimeException.class, () -> fixture.client.complete("system", "user", 2048));
        fixture.server.verify();
    }

    @Test
    void connectionProbeKeepsItsSmallTokenBudget() {
        Fixture fixture = fixture("OPENAI");
        fixture.server.expect(requestTo(fixture.endpoint))
                .andExpect(jsonPath("$.max_tokens").value(256))
                .andExpect(header("Authorization", "Bearer probe-key"))
                .andRespond(withSuccess(response("OPENAI"), MediaType.APPLICATION_JSON));
        assertEquals("probe-model", fixture.client.testConnection("https://provider.test/v1", "probe-key",
                "probe-model", "OPENAI").get("model"));
        fixture.server.verify();
    }

    private Fixture fixture(String protocol) {
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        String base = "https://provider.test/v1";
        String endpoint = base + (protocol.equals("CLAUDE") ? "/messages" : "/chat/completions");
        when(runtime.llmUrl()).thenReturn(base);
        when(runtime.llmKey()).thenReturn("default-key");
        when(runtime.llmModel()).thenReturn("default-model");
        when(runtime.llmProtocol()).thenReturn(protocol);
        when(runtime.resolvedLlmProtocol()).thenReturn(protocol);
        when(runtime.resolveLlmProtocol(base, protocol)).thenReturn(protocol);
        when(runtime.llmEndpoint(base, protocol)).thenReturn(endpoint);
        when(runtime.llmEndpoint()).thenReturn(endpoint);
        // Default headers must not leak when a request uses another provider's credentials.
        RestClient.Builder builder = RestClient.builder().defaultHeader("x-api-key", "stale-key")
                .defaultHeader("anthropic-version", "stale-version").defaultHeader("Authorization", "Bearer stale-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new LlmClient(builder.build(), new LlmConfig(), runtime), server, endpoint);
    }

    private String response(String protocol) {
        return protocol.equals("CLAUDE") ? "{\"content\":[{\"type\":\"text\",\"text\":\" result \"}]}"
                : "{\"choices\":[{\"message\":{\"content\":\" result \"}}]}";
    }

    private record Fixture(LlmClient client, MockRestServiceServer server, String endpoint) {}
}
