package com.web.backen.ppt;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PptGenerationSession {

    private String taskId;
    private String accessToken;
    private String prompt;
    private String templateKey = "academic-blue";
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
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; touch(); }
    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; touch(); }
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
    public Path getDeckJsonPath() { return taskDir.resolve("deck.json"); }
    @JsonIgnore
    public Path getStyleJsonPath() { return taskDir.resolve("style.json"); }
    @JsonIgnore
    public Path getImageManifestPath() { return taskDir.resolve("image-manifest.json"); }
    @JsonIgnore
    public Path getImagesDir() { return taskDir.resolve("images"); }
    @JsonIgnore
    public Path getOutputPath() { return taskDir.resolve("output.pptx"); }

    private String paperExtension() {
        if (paperFileName == null) return "";
        String lower = paperFileName.toLowerCase();
        if (lower.endsWith(".docx")) return ".docx";
        if (lower.endsWith(".pdf")) return ".pdf";
        return "";
    }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
