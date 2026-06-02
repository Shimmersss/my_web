package com.web.backen.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个翻译任务的内存状态
 */
public class TranslationSession {

    private final String taskId;
    private final String fileName;
    private final byte[] pdfBytes;
    private List<PdfParseService.Paragraph> paragraphs;
    private List<String> translations;
    private int totalParagraphs;
    private int totalPages;
    private volatile int completedParagraphs;
    private volatile String status; // "preview", "ready", "translating", "completed", "error"
    private volatile String errorMessage;
    private final long createdAt;

    public TranslationSession(String taskId, String fileName, byte[] pdfBytes, List<PdfParseService.Paragraph> paragraphs) {
        this.taskId = taskId;
        this.fileName = fileName;
        this.pdfBytes = pdfBytes;
        this.paragraphs = paragraphs;
        this.totalParagraphs = paragraphs.size();
        this.translations = new ArrayList<>(new ArrayList<>(java.util.Collections.nCopies(paragraphs.size(), null)));
        this.completedParagraphs = 0;
        this.status = paragraphs.isEmpty() ? "preview" : "ready";
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * 更新段落列表（用于页面范围选择后重新提取）
     */
    public void updateParagraphs(List<PdfParseService.Paragraph> newParagraphs) {
        this.paragraphs = newParagraphs;
        this.totalParagraphs = newParagraphs.size();
        this.translations = new ArrayList<>(java.util.Collections.nCopies(newParagraphs.size(), null));
        this.completedParagraphs = 0;
        this.status = "ready";
    }

    public String getTaskId() { return taskId; }
    public String getFileName() { return fileName; }
    public byte[] getPdfBytes() { return pdfBytes; }
    public List<PdfParseService.Paragraph> getParagraphs() { return paragraphs; }
    public List<String> getTranslations() { return translations; }
    public int getTotalParagraphs() { return totalParagraphs; }
    public int getTotalPages() { return totalPages; }
    public int getCompletedParagraphs() { return completedParagraphs; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public long getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public void setTranslation(int index, String translated) {
        translations.set(index, translated);
        completedParagraphs = index + 1;
    }
}
