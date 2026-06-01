package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@ConfigurationProperties(prefix = "zotero")
public class ZoteroConfig {

    private String apiKey;
    private String userId;
    private String baseUrl = "https://api.zotero.org";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Bean
    public RestClient zoteroRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Zotero-API-Version", "3")
                .defaultHeader("Zotero-API-Key", apiKey == null ? "" : apiKey)
                .build();
    }
}
