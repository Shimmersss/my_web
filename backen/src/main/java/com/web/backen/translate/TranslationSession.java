package com.web.backen.translate;

/**
 * 一个翻译任务的内存状态
 */
public class TranslationSession {

    private final String taskId;
    private final String fileName;
    private final byte[] pdfBytes;
    private int totalPages;
    private int startPage = 1;
    private int endPage = 1;
    private volatile String status; // "preview", "ready", "translating", "completed", "error"
    private volatile String errorMessage;
    private volatile byte[] translatedPdfBytes;
    private volatile byte[] bilingualPdfBytes;
    private String fontFamily = "auto";
    private int qps = 8;
    private volatile double progress;
    private volatile String progressStage = "";
    private final long createdAt;

    public TranslationSession(String taskId, String fileName, byte[] pdfBytes) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.pdfBytes = pdfBytes;
        this.status = "preview";
        this.createdAt = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public String getFileName() { return fileName; }
    public byte[] getPdfBytes() { return pdfBytes; }
    public int getTotalPages() { return totalPages; }
    public int getStartPage() { return startPage; }
    public int getEndPage() { return endPage; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public byte[] getTranslatedPdfBytes() { return translatedPdfBytes; }
    public byte[] getBilingualPdfBytes() { return bilingualPdfBytes; }
    public String getFontFamily() { return fontFamily; }
    public int getQps() { return qps; }
    public double getProgress() { return progress; }
    public String getProgressStage() { return progressStage; }
    public long getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public void setPageRange(int startPage, int endPage) {
        this.startPage = startPage;
        this.endPage = endPage;
    }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setTranslatedPdfBytes(byte[] translatedPdfBytes) { this.translatedPdfBytes = translatedPdfBytes; }
    public void setBilingualPdfBytes(byte[] bilingualPdfBytes) { this.bilingualPdfBytes = bilingualPdfBytes; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public void setQps(int qps) { this.qps = qps; }
    public void setProgress(double progress) { this.progress = progress; }
    public void setProgressStage(String progressStage) { this.progressStage = progressStage; }
}
