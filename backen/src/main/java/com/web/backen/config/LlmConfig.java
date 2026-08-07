package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private String apiUrl = "https://token-plan-cn.xiaomimimo.com/anthropic";
    private String apiKey = "";
    private String model = "claude-sonnet-4-20250514";
    private int maxTokens = 8192;
    private int chunkSize = 6000;
    private int connectTimeoutSeconds = 20;
    private int readTimeoutSeconds = 180;
    private long visionImageMaxBytes = 4L * 1024 * 1024;
    private long visionBatchMaxBytes = 16L * 1024 * 1024;
    private int visionImageMaxPixels = 4_000_000;
    private int visionMaxTokens = 12_000;

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }
    public long getVisionImageMaxBytes() { return visionImageMaxBytes; }
    public void setVisionImageMaxBytes(long visionImageMaxBytes) { this.visionImageMaxBytes = visionImageMaxBytes; }
    public long getVisionBatchMaxBytes() { return visionBatchMaxBytes; }
    public void setVisionBatchMaxBytes(long visionBatchMaxBytes) { this.visionBatchMaxBytes = visionBatchMaxBytes; }
    public int getVisionImageMaxPixels() { return visionImageMaxPixels; }
    public void setVisionImageMaxPixels(int visionImageMaxPixels) { this.visionImageMaxPixels = visionImageMaxPixels; }
    public int getVisionMaxTokens() { return visionMaxTokens; }
    public void setVisionMaxTokens(int visionMaxTokens) { this.visionMaxTokens = visionMaxTokens; }

    @Bean
    public RestClient llmRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSeconds)))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(5, readTimeoutSeconds)));
        return RestClient.builder()
                .baseUrl(apiUrl)
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", apiKey == null ? "" : apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .defaultHeader("Content-Type", "application/json")
                .build();
    }
}
