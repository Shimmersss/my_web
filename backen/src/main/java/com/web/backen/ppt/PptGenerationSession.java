package com.web.backen.ppt;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PptGenerationSession {

    private String taskId;
    private String accessToken;
    private String clientRequestId;
    private String prompt;
    private String templateKey = "github-bjtu-blue";
    private String outputFormat = "pptx";
    private String researchMode = "auto";
    private String visualMode = "best_effort";
    private String fontFamily = "Microsoft YaHei";
    private String templateFileName;
    private int extractionPercent = 50;
    private String paperFileName;
    private String outputFileName;
    private String status = "queued";
    private String progressStage = "queued";
    private String errorMessage;
    private double progress;
    private int queuePosition;
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();
    private long completedAt;
    private long userId;
    private int creditCost;
    private Long creditTransactionId;
    private boolean creditRefunded;
    private boolean refundPending;
    private String refundError;
    private String revisionOfTaskId;
    private String revisionPrompt;
    private int sourceCount;
    private int agentIteration;
    private boolean qaValid;
    private boolean creationReady;
    private boolean quotaRequired;

    @JsonIgnore
    private Path taskDir;

    public PptGenerationSession() {}

    public PptGenerationSession(String taskId, String prompt, Path taskDir) {
        this.taskId = taskId;
        this.prompt = prompt;
        this.taskDir = taskDir;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; touch(); }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; touch(); }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; touch(); }
    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; touch(); }
    public String getResearchMode() { return researchMode; }
    public void setResearchMode(String researchMode) { this.researchMode = researchMode; touch(); }
    public String getVisualMode() { return "strict".equals(visualMode) ? "strict" : "best_effort"; }
    public void setVisualMode(String visualMode) { this.visualMode = visualMode; touch(); }
    public String getFontFamily() { return fontFamily == null || fontFamily.isBlank() ? "Microsoft YaHei" : fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; touch(); }
    public String getTemplateFileName() { return templateFileName; }
    public void setTemplateFileName(String templateFileName) { this.templateFileName = templateFileName; touch(); }
    public int getExtractionPercent() { return extractionPercent; }
    public void setExtractionPercent(int extractionPercent) { this.extractionPercent = extractionPercent; touch(); }
    public String getPaperFileName() { return paperFileName; }
    public void setPaperFileName(String paperFileName) { this.paperFileName = paperFileName; touch(); }
    public String getOutputFileName() { return outputFileName; }
    public void setOutputFileName(String outputFileName) { this.outputFileName = outputFileName; touch(); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; touch(); }
    public String getProgressStage() { return progressStage; }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; touch(); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
    public double getProgress() { return progress; }
    public void setProgress(double progress) { this.progress = progress; touch(); }
    public int getQueuePosition() { return queuePosition; }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; touch(); }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; touch(); }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; touch(); }
    public int getCreditCost() { return creditCost; }
    public void setCreditCost(int creditCost) { this.creditCost = creditCost; touch(); }
    public Long getCreditTransactionId() { return creditTransactionId; }
    public void setCreditTransactionId(Long creditTransactionId) { this.creditTransactionId = creditTransactionId; touch(); }
    public boolean isCreditRefunded() { return creditRefunded; }
    public void setCreditRefunded(boolean creditRefunded) { this.creditRefunded = creditRefunded; touch(); }
    public boolean isRefundPending() { return refundPending; }
    public void setRefundPending(boolean refundPending) { this.refundPending = refundPending; touch(); }
    public String getRefundError() { return refundError; }
    public void setRefundError(String refundError) { this.refundError = refundError; touch(); }
    public String getRevisionOfTaskId() { return revisionOfTaskId; }
    public void setRevisionOfTaskId(String revisionOfTaskId) { this.revisionOfTaskId = revisionOfTaskId; touch(); }
    public String getRevisionPrompt() { return revisionPrompt; }
    public void setRevisionPrompt(String revisionPrompt) { this.revisionPrompt = revisionPrompt; touch(); }
    public int getSourceCount() { return sourceCount; }
    public void setSourceCount(int sourceCount) { this.sourceCount = sourceCount; touch(); }
    public int getAgentIteration() { return agentIteration; }
    public void setAgentIteration(int agentIteration) { this.agentIteration = agentIteration; touch(); }
    public boolean isQaValid() { return qaValid; }
    public void setQaValid(boolean qaValid) { this.qaValid = qaValid; touch(); }
    public boolean isCreationReady() { return creationReady; }
    public void setCreationReady(boolean creationReady) { this.creationReady = creationReady; touch(); }
    public boolean isQuotaRequired() { return quotaRequired; }
    public void setQuotaRequired(boolean quotaRequired) { this.quotaRequired = quotaRequired; touch(); }

    @JsonIgnore
    public Path getTaskDir() { return taskDir; }
    public void setTaskDir(Path taskDir) { this.taskDir = taskDir; }

    @JsonIgnore
    public Path getMetadataPath() { return taskDir.resolve("task.json"); }
    @JsonIgnore
    public Path getTemplatePath() { return taskDir.resolve("template.pptx"); }
    @JsonIgnore
    public Path getPaperPath() { return taskDir.resolve("paper" + paperExtension()); }
    @JsonIgnore
    public Path getPreviewPath() { return taskDir.resolve("preview.json"); }
    @JsonIgnore
    public Path getPreviewDir() { return taskDir.resolve("preview"); }
    @JsonIgnore
    public Path getSourcesPath() { return taskDir.resolve("sources.json"); }
    @JsonIgnore
    public Path getAgentPlanPath() { return taskDir.resolve("agent-plan.json"); }
    @JsonIgnore
    public Path getImagesDir() { return taskDir.resolve("images"); }
    @JsonIgnore
    public Path getPptxOutputPath() { return taskDir.resolve("output.pptx"); }
    @JsonIgnore
    public Path getHtmlOutputPath() { return taskDir.resolve("output.html"); }
    @JsonIgnore
    public Path getOutputPath() {
        return "html".equalsIgnoreCase(outputFormat) ? getHtmlOutputPath() : getPptxOutputPath();
    }

    private String paperExtension() {
        if (paperFileName == null) return "";
        String lower = paperFileName.toLowerCase();
        if (lower.endsWith(".docx")) return ".docx";
        if (lower.endsWith(".pdf")) return ".pdf";
        if (lower.endsWith(".pptx")) return ".pptx";
        if (lower.endsWith(".xlsx")) return ".xlsx";
        if (lower.endsWith(".txt")) return ".txt";
        if (lower.endsWith(".md")) return ".md";
        if (lower.endsWith(".csv")) return ".csv";
        if (lower.endsWith(".html")) return ".html";
        if (lower.endsWith(".htm")) return ".htm";
        return "";
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
