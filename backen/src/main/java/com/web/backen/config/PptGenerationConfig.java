package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ppt-generation")
public class PptGenerationConfig {

    private String storageDir = "../.run/ppt-generation-tasks";
    private int maxHistory = 5;
    private int queueCapacity = 3;
    private int maxPromptChars = 8000;
    private long maxPaperBytes = 30L * 1024 * 1024;
    private long maxTemplateBytes = 30L * 1024 * 1024;
    private int maxPaperTextChars = 28000;
    private int maxExtractedImages = 8;
    private int maxVisionImages = 8;
    private int llmMaxTokens = 16384;
    private int visionMaxTokens = 4096;
    private String visionModel = "mimo-v2.5";
    private int timeoutSeconds = 900;
    private String nodeCommand = "node";
    private String runnerScript = "./scripts/pptx-generator/generate_deck.mjs";

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    public int getMaxPromptChars() { return maxPromptChars; }
    public void setMaxPromptChars(int maxPromptChars) { this.maxPromptChars = maxPromptChars; }
    public long getMaxPaperBytes() { return maxPaperBytes; }
    public void setMaxPaperBytes(long maxPaperBytes) { this.maxPaperBytes = maxPaperBytes; }
    public long getMaxTemplateBytes() { return maxTemplateBytes; }
    public void setMaxTemplateBytes(long maxTemplateBytes) { this.maxTemplateBytes = maxTemplateBytes; }
    public int getMaxPaperTextChars() { return maxPaperTextChars; }
    public void setMaxPaperTextChars(int maxPaperTextChars) { this.maxPaperTextChars = maxPaperTextChars; }
    public int getMaxExtractedImages() { return maxExtractedImages; }
    public void setMaxExtractedImages(int maxExtractedImages) { this.maxExtractedImages = maxExtractedImages; }
    public int getMaxVisionImages() { return maxVisionImages; }
    public void setMaxVisionImages(int maxVisionImages) { this.maxVisionImages = maxVisionImages; }
    public int getLlmMaxTokens() { return llmMaxTokens; }
    public void setLlmMaxTokens(int llmMaxTokens) { this.llmMaxTokens = llmMaxTokens; }
    public int getVisionMaxTokens() { return visionMaxTokens; }
    public void setVisionMaxTokens(int visionMaxTokens) { this.visionMaxTokens = visionMaxTokens; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getNodeCommand() { return nodeCommand; }
    public void setNodeCommand(String nodeCommand) { this.nodeCommand = nodeCommand; }
    public String getRunnerScript() { return runnerScript; }
    public void setRunnerScript(String runnerScript) { this.runnerScript = runnerScript; }
}
