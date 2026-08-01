package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ppt-generation")
public class PptGenerationConfig {

    private String storageDir = "../.run/ppt-generation-tasks";
    private String templateCacheDir = "../.run/ppt-generation-tasks/_template-cache";
    private int maxHistory = 5;
    private int queueCapacity = 3;
    private int maxPromptChars = 8000;
    private long maxPaperBytes = 30L * 1024 * 1024;
    private long maxTemplateBytes = 30L * 1024 * 1024;
    private int maxPaperTextChars = 28000;
    private int maxExtractedImages = 24;
    private int maxArchiveEntries = 2000;
    private long maxArchiveUncompressedBytes = 120L * 1024 * 1024;
    private long maxArchiveEntryBytes = 32L * 1024 * 1024;
    private int maxArchiveCompressionRatio = 120;
    private String visionModel = "mimo-v2.5";
    private int timeoutSeconds = 900;
    private String agentCommand = "node";
    private String agentScript = "./scripts/ppt-agent/worker.mjs";
    private String agentProjectRoot = "..";
    private int agentTimeoutSeconds = 1800;
    private int agentNodeMaxOldSpaceMb = 384;
    private int agentMaxSources = 12;
    private String sofficeCommand = "soffice";
    private String pdftoppmCommand = "pdftoppm";
    private String chromeCommand = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    private String paperParserCommand = "uv run --with docling --with markitdown python";
    private String paperParserScript = "./scripts/ppt_document_parser.py";

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public String getTemplateCacheDir() { return templateCacheDir; }
    public void setTemplateCacheDir(String templateCacheDir) { this.templateCacheDir = templateCacheDir; }
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
    public int getMaxArchiveEntries() { return maxArchiveEntries; }
    public void setMaxArchiveEntries(int maxArchiveEntries) { this.maxArchiveEntries = maxArchiveEntries; }
    public long getMaxArchiveUncompressedBytes() { return maxArchiveUncompressedBytes; }
    public void setMaxArchiveUncompressedBytes(long maxArchiveUncompressedBytes) { this.maxArchiveUncompressedBytes = maxArchiveUncompressedBytes; }
    public long getMaxArchiveEntryBytes() { return maxArchiveEntryBytes; }
    public void setMaxArchiveEntryBytes(long maxArchiveEntryBytes) { this.maxArchiveEntryBytes = maxArchiveEntryBytes; }
    public int getMaxArchiveCompressionRatio() { return maxArchiveCompressionRatio; }
    public void setMaxArchiveCompressionRatio(int maxArchiveCompressionRatio) { this.maxArchiveCompressionRatio = maxArchiveCompressionRatio; }
    public String getVisionModel() { return visionModel; }
    public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getAgentCommand() { return agentCommand; }
    public void setAgentCommand(String agentCommand) { this.agentCommand = agentCommand; }
    public String getAgentScript() { return agentScript; }
    public void setAgentScript(String agentScript) { this.agentScript = agentScript; }
    public String getAgentProjectRoot() { return agentProjectRoot; }
    public void setAgentProjectRoot(String agentProjectRoot) { this.agentProjectRoot = agentProjectRoot; }
    public int getAgentTimeoutSeconds() { return agentTimeoutSeconds; }
    public void setAgentTimeoutSeconds(int agentTimeoutSeconds) { this.agentTimeoutSeconds = agentTimeoutSeconds; }
    public int getAgentNodeMaxOldSpaceMb() { return agentNodeMaxOldSpaceMb; }
    public void setAgentNodeMaxOldSpaceMb(int agentNodeMaxOldSpaceMb) { this.agentNodeMaxOldSpaceMb = agentNodeMaxOldSpaceMb; }
    public int getAgentMaxSources() { return agentMaxSources; }
    public void setAgentMaxSources(int agentMaxSources) { this.agentMaxSources = agentMaxSources; }
    public String getSofficeCommand() { return sofficeCommand; }
    public void setSofficeCommand(String sofficeCommand) { this.sofficeCommand = sofficeCommand; }
    public String getPdftoppmCommand() { return pdftoppmCommand; }
    public void setPdftoppmCommand(String pdftoppmCommand) { this.pdftoppmCommand = pdftoppmCommand; }
    public String getChromeCommand() { return chromeCommand; }
    public void setChromeCommand(String chromeCommand) { this.chromeCommand = chromeCommand; }
    public String getPaperParserCommand() { return paperParserCommand; }
    public void setPaperParserCommand(String paperParserCommand) { this.paperParserCommand = paperParserCommand; }
    public String getPaperParserScript() { return paperParserScript; }
    public void setPaperParserScript(String paperParserScript) { this.paperParserScript = paperParserScript; }
}
