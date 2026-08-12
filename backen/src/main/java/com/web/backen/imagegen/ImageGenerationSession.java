package com.web.backen.imagegen;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageGenerationSession {
    private String taskId;
    private String mode = "GENERATE";
    private String prompt;
    private String size = "1024x1024";
    private String quality = "medium";
    private String parentTaskId;
    private String referenceFileName;
    private String referenceContentType;
    private String status = "queued";
    private String progressStage = "queued";
    private String errorMessage;
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
    @JsonIgnore private Path taskDir;

    public ImageGenerationSession() {}
    public ImageGenerationSession(String taskId, String prompt, Path taskDir) {
        this.taskId = taskId; this.prompt = prompt; this.taskDir = taskDir;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getMode() { return "EDIT".equalsIgnoreCase(mode) ? "EDIT" : "GENERATE"; }
    public void setMode(String mode) { this.mode = mode; touch(); }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; touch(); }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; touch(); }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; touch(); }
    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; touch(); }
    public String getReferenceFileName() { return referenceFileName; }
    public void setReferenceFileName(String referenceFileName) { this.referenceFileName = referenceFileName; touch(); }
    public String getReferenceContentType() { return referenceContentType; }
    public void setReferenceContentType(String referenceContentType) { this.referenceContentType = referenceContentType; touch(); }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; touch(); }
    public String getProgressStage() { return progressStage; }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; touch(); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
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
    @JsonIgnore public Path getTaskDir() { return taskDir; }
    public void setTaskDir(Path taskDir) { this.taskDir = taskDir; }
    @JsonIgnore public Path getMetadataPath() { return taskDir.resolve("task.json"); }
    @JsonIgnore public Path getReferencePath() { return taskDir.resolve("reference" + ("image/jpeg".equals(referenceContentType) ? ".jpg" : ".png")); }
    @JsonIgnore public Path getResultPath() { return taskDir.resolve("output.png"); }
    @JsonIgnore public Path getPreviewPath() { return taskDir.resolve("preview.jpg"); }
    private void touch() { updatedAt = System.currentTimeMillis(); }
}
