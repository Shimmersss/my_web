package com.web.backen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ppt-generation")
public class PptGenerationConfig {

    private String storageDir = "../.run/ppt-generation-tasks";
    private int maxHistory = 5;
    private int maxGlobalHistory = 20;
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
    /** Native web_search is currently available through the pay-as-you-go API plugin. */
    private String mimoSearchEndpoint = "https://api.xiaomimimo.com/v1/chat/completions";
    private String mimoSearchKey = "";
    private String mimoSearchModel = "mimo-v2.5";
    private String sofficeCommand = "soffice";
    private String pdftoppmCommand = "pdftoppm";
    private String chromeCommand = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
    private String paperParserCommand = "uv run --with docling --with markitdown python";
    private String paperParserScript = "./scripts/ppt_document_parser.py";
    private String codexCommand = "./node_modules/.bin/codex";
    private String codexVendorRoot = "../vendor/open-kimi-ppt-skill";
    private String codexFinalizeScript = "./scripts/ppt-codex/finalize.mjs";
    private int codexTimeoutSeconds = 1800;
    private long codexMaxLogBytes = 2L * 1024 * 1024;
    private long codexMaxProjectBytes = 100L * 1024 * 1024;
    private int codexMaxProjectFiles = 500;
    /** OpenAI-compatible Images API. Kept separate from Codex CLI authentication. */
    private String imageGenerationEndpoint = "https://api.openai.com/v1";
    private String imageGenerationKey = "";
    private String imageGenerationModel = "gpt-image-2";
    private String imageGenerationQuality = "medium";
    private int imageGenerationMaxImages = 3;

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getMaxHistory() { return maxHistory; }
    public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
    public int getMaxGlobalHistory() { return maxGlobalHistory; }
    public void setMaxGlobalHistory(int maxGlobalHistory) { this.maxGlobalHistory = maxGlobalHistory; }
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
    public String getMimoSearchEndpoint() { return mimoSearchEndpoint; }
    public void setMimoSearchEndpoint(String mimoSearchEndpoint) { this.mimoSearchEndpoint = mimoSearchEndpoint; }
    public String getMimoSearchKey() { return mimoSearchKey; }
    public void setMimoSearchKey(String mimoSearchKey) { this.mimoSearchKey = mimoSearchKey; }
    public String getMimoSearchModel() { return mimoSearchModel; }
    public void setMimoSearchModel(String mimoSearchModel) { this.mimoSearchModel = mimoSearchModel; }
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
    public String getCodexCommand() { return codexCommand; }
    public void setCodexCommand(String codexCommand) { this.codexCommand = codexCommand; }
    public String getCodexVendorRoot() { return codexVendorRoot; }
    public void setCodexVendorRoot(String codexVendorRoot) { this.codexVendorRoot = codexVendorRoot; }
    public String getCodexFinalizeScript() { return codexFinalizeScript; }
    public void setCodexFinalizeScript(String codexFinalizeScript) { this.codexFinalizeScript = codexFinalizeScript; }
    public int getCodexTimeoutSeconds() { return codexTimeoutSeconds; }
    public void setCodexTimeoutSeconds(int codexTimeoutSeconds) { this.codexTimeoutSeconds = codexTimeoutSeconds; }
    public long getCodexMaxLogBytes() { return codexMaxLogBytes; }
    public void setCodexMaxLogBytes(long codexMaxLogBytes) { this.codexMaxLogBytes = codexMaxLogBytes; }
    public long getCodexMaxProjectBytes() { return codexMaxProjectBytes; }
    public void setCodexMaxProjectBytes(long codexMaxProjectBytes) { this.codexMaxProjectBytes = codexMaxProjectBytes; }
    public int getCodexMaxProjectFiles() { return codexMaxProjectFiles; }
    public void setCodexMaxProjectFiles(int codexMaxProjectFiles) { this.codexMaxProjectFiles = codexMaxProjectFiles; }
    public String getImageGenerationEndpoint() { return imageGenerationEndpoint; }
    public void setImageGenerationEndpoint(String imageGenerationEndpoint) { this.imageGenerationEndpoint = imageGenerationEndpoint; }
    public String getImageGenerationKey() { return imageGenerationKey; }
    public void setImageGenerationKey(String imageGenerationKey) { this.imageGenerationKey = imageGenerationKey; }
    public String getImageGenerationModel() { return imageGenerationModel; }
    public void setImageGenerationModel(String imageGenerationModel) { this.imageGenerationModel = imageGenerationModel; }
    public String getImageGenerationQuality() { return imageGenerationQuality; }
    public void setImageGenerationQuality(String imageGenerationQuality) { this.imageGenerationQuality = imageGenerationQuality; }
    public int getImageGenerationMaxImages() { return imageGenerationMaxImages; }
    public void setImageGenerationMaxImages(int imageGenerationMaxImages) { this.imageGenerationMaxImages = imageGenerationMaxImages; }
}
