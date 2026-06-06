package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "translation")
public class TranslationConfig {

    private String storageDir = "../.run/translation-tasks";
    private int maxHistory = 5;
    private int queueCapacity = 5;
    private int maxQps = 4;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxQps() { return maxQps; }
    public void setMaxQps(int maxQps) { this.maxQps = maxQps; }
}
