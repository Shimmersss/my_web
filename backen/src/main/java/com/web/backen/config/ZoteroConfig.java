package com.web.backen.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Data
@Configuration
@ConfigurationProperties(prefix = "zotero")
public class ZoteroConfig {

    private String apiKey;
    private String userId;
    private String baseUrl = "https://api.zotero.org";

    @Bean
    public RestClient zoteroRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Zotero-API-Version", "3")
                .defaultHeader("Zotero-API-Key", apiKey == null ? "" : apiKey)
                .build();
    }
}
