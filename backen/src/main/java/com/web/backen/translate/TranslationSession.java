package com.web.backen.translate;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.file.Path;

/**
 * 一个翻译任务的轻量状态。PDF 文件只保存路径，不长期驻留 JVM 堆。
 */
public class TranslationSession {

    private String taskId;
    private String fileName;
    private int totalPages;
    private int startPage = 1;
    private int endPage = 1;
    private volatile String status = "preview";
    private volatile String errorMessage;
    private String fontFamily = "auto";
    private int qps = 4;
    private volatile double progress;
    private volatile String progressStage = "";
    private long createdAt;
    private volatile long updatedAt;
    private volatile long completedAt;
    private volatile int queuePosition;

    @JsonIgnore
    private Path taskDir;

    public TranslationSession() {}

    public TranslationSession(String taskId, String fileName, Path taskDir) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.taskDir = taskDir;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public String getTaskId() { return taskId; }
    public String getFileName() { return fileName; }
    public int getTotalPages() { return totalPages; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public String getFontFamily() { return fontFamily; }
    public int getQps() { return qps; }
    public double getProgress() { return progress; }
    public String getProgressStage() { return progressStage; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public long getCompletedAt() { return completedAt; }
    public int getQueuePosition() { return queuePosition; }
    @JsonIgnore
    public Path getTaskDir() { return taskDir; }
    @JsonIgnore
    public Path getInputPdfPath() { return taskDir.resolve("input.pdf"); }
    @JsonIgnore
    public Path getTranslatedPdfPath() { return taskDir.resolve("translated.pdf"); }
    @JsonIgnore
    public Path getBilingualPdfPath() { return taskDir.resolve("bilingual.pdf"); }
    @JsonIgnore
    public Path getMetadataPath() { return taskDir.resolve("task.json"); }

    public void setTaskId(String taskId) { this.taskId = taskId; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setTaskDir(Path taskDir) { this.taskDir = taskDir; }
    public void setStatus(String status) { this.status = status; touch(); }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; touch(); }
    public void setPageRange(int startPage, int endPage) {
        this.startPage = startPage;
        this.endPage = endPage;
        touch();
    }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; touch(); }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; touch(); }
    public void setQps(int qps) { this.qps = qps; touch(); }
    public void setProgress(double progress) { this.progress = progress; touch(); }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; touch(); }
    public void setCompletedAt(long completedAt) { this.completedAt = completedAt; touch(); }
    public void setQueuePosition(int queuePosition) { this.queuePosition = queuePosition; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    private void touch() {
        this.updatedAt = System.currentTimeMillis();
    }
}
