package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "image-generation")
public class ImageGenerationConfig {
    private String storageDir = "../.run/image-generation-tasks";
    private int maxHistory = 5;
    private int maxGlobalHistory = 20;
    private int queueCapacity = 3;
    private int timeoutSeconds = 240;
    private long maxReferenceBytes = 20L * 1024 * 1024;
    private long maxOutputBytes = 12L * 1024 * 1024;
    private long maxReferencePixels = 16_000_000L;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    public int getMaxGlobalHistory() { return maxGlobalHistory; }
    public void setMaxGlobalHistory(int maxGlobalHistory) { this.maxGlobalHistory = maxGlobalHistory; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public long getMaxReferenceBytes() { return maxReferenceBytes; }
    public void setMaxReferenceBytes(long maxReferenceBytes) { this.maxReferenceBytes = maxReferenceBytes; }
    public long getMaxOutputBytes() { return maxOutputBytes; }
    public void setMaxOutputBytes(long maxOutputBytes) { this.maxOutputBytes = maxOutputBytes; }
    public long getMaxReferencePixels() { return maxReferencePixels; }
    public void setMaxReferencePixels(long maxReferencePixels) { this.maxReferencePixels = maxReferencePixels; }
}
