package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "admin")
public class AdminConfig {

    private String key = "change-me";

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
