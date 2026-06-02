package com.web.backen.translate;

import com.web.backen.config.LlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final PdfParseService pdfParseService;
    private final PdfRenderService pdfRenderService;
    private final LlmService llmService;
    private final LlmConfig llmConfig;
    private final ConcurrentHashMap<String, TranslationSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public TranslationService(PdfParseService pdfParseService, PdfRenderService pdfRenderService,
                              LlmService llmService, LlmConfig llmConfig) {
        this.pdfParseService = pdfParseService;
        this.pdfRenderService = pdfRenderService;
        this.llmService = llmService;
        this.llmConfig = llmConfig;
    }

    /**
     * 创建翻译任务预览：只获取 PDF 页数，不提取段落
     */
    public TranslationSession createSessionPreview(String fileName, byte[] pdfBytes) throws Exception {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        int totalPages = pdfParseService.getTotalPages(pdfBytes);
        // 创建空段落列表的会话
        TranslationSession session = new TranslationSession(taskId, fileName, pdfBytes, List.of());
        session.setTotalPages(totalPages);
        sessions.put(taskId, session);
        log.info("创建翻译预览: taskId={}, file={}, totalPages={}", taskId, fileName, totalPages);
        return session;
    }

    /**
     * 开始翻译：按页面范围提取段落并开始翻译
     */
    public TranslationSession startTranslation(String taskId, int startPage, int endPage) throws Exception {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }

        List<PdfParseService.Paragraph> paragraphs = pdfParseService.extractParagraphs(
                session.getPdfBytes(), startPage, endPage);

        // 更新会话的段落列表
        session.updateParagraphs(paragraphs);
        log.info("开始翻译: taskId={}, pages={}-{}, paragraphs={}", taskId, startPage, endPage, paragraphs.size());
        return session;
    }

    /**
     * 创建翻译任务：解析 PDF，返回会话（翻译全部页面）
     */
    public TranslationSession createSession(String fileName, byte[] pdfBytes) throws Exception {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        List<PdfParseService.Paragraph> paragraphs = pdfParseService.extractParagraphs(pdfBytes);
        TranslationSession session = new TranslationSession(taskId, fileName, pdfBytes, paragraphs);
        session.setTotalPages(pdfParseService.getTotalPages(pdfBytes));
        sessions.put(taskId, session);
        log.info("创建翻译任务: taskId={}, file={}, paragraphs={}", taskId, fileName, paragraphs.size());
        return session;
    }

    /**
     * 获取任务状态
     */
    public TranslationSession getSession(String taskId) {
        return sessions.get(taskId);
    }

    /**
     * 异步翻译并通过 SSE 推送进度
     */
    public void translateAsync(String taskId, SseEmitter emitter) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", "任务不存在")));
            } catch (IOException ignored) {}
            emitter.complete();
            return;
        }

        session.setStatus("translating");

        executor.submit(() -> {
            try {
                List<PdfParseService.Paragraph> paragraphs = session.getParagraphs();

                for (int i = 0; i < paragraphs.size(); i++) {
                    PdfParseService.Paragraph para = paragraphs.get(i);
                    String sourceText = para.text();

                    String translated;
                    // 长段落按 chunk-size 拆分翻译
                    if (sourceText.length() > llmConfig.getChunkSize()) {
                        translated = translateLongText(sourceText);
                    } else {
                        translated = llmService.translate(sourceText);
                    }

                    session.setTranslation(i, translated);

                    // 推送 SSE 进度事件
                    Map<String, Object> progressData = new LinkedHashMap<>();
                    progressData.put("paragraphIndex", i);
                    progressData.put("pageNumber", para.pageNumber());
                    progressData.put("original", sourceText);
                    progressData.put("translated", translated);
                    progressData.put("completed", session.getCompletedParagraphs());
                    progressData.put("total", session.getTotalParagraphs());

                    emitter.send(SseEmitter.event().name("progress").data(progressData));
                }

                session.setStatus("completed");

                // 推送完成事件
                Map<String, Object> doneData = Map.of(
                        "taskId", taskId,
                        "total", session.getTotalParagraphs()
                );
                emitter.send(SseEmitter.event().name("done").data(doneData));
                emitter.complete();

            } catch (Exception e) {
                log.error("翻译任务失败: taskId={}", taskId, e);
                session.setStatus("error");
                session.setErrorMessage(e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error").data(
                            Map.of("message", e.getMessage() != null ? e.getMessage() : "翻译过程中发生未知错误")
                    ));
                } catch (IOException ignored) {}
                emitter.complete();
            }
        });
    }

    /**
     * 翻译超长文本：按句号边界拆分，分别翻译后拼接
     */
    private String translateLongText(String text) {
        int chunkSize = llmConfig.getChunkSize();
        List<String> chunks = splitAtSentenceBoundary(text, chunkSize);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String translated = llmService.translate(chunk, i > 0);
            result.append(translated);
            if (i < chunks.size() - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 按句号边界拆分长文本
     */
    private List<String> splitAtSentenceBoundary(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            if (start + maxChunkSize >= text.length()) {
                chunks.add(text.substring(start));
                break;
            }

            // 在 chunk-size 范围内找最后一个句号
            int end = start + maxChunkSize;
            int lastPeriod = -1;
            for (int i = end; i > start; i--) {
                char c = text.charAt(i);
                if (c == '.' && i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                    lastPeriod = i + 1;
                    break;
                }
            }

            if (lastPeriod <= start) {
                // 找不到句号，强制截断
                lastPeriod = end;
            }

            chunks.add(text.substring(start, lastPeriod).trim());
            start = lastPeriod;
        }

        return chunks;
    }

    /**
     * 构建下载内容
     */
    public String buildDownloadContent(String taskId, String mode) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("翻译尚未完成");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== PDF 论文翻译 ===\n");
        sb.append("文件: ").append(session.getFileName()).append("\n");
        sb.append("段落数: ").append(session.getTotalParagraphs()).append("\n");
        sb.append("模式: ").append("bilingual".equals(mode) ? "双语对照" : "纯中文翻译").append("\n");
        sb.append("=".repeat(40)).append("\n\n");

        List<PdfParseService.Paragraph> paragraphs = session.getParagraphs();
        List<String> translations = session.getTranslations();

        for (int i = 0; i < paragraphs.size(); i++) {
            PdfParseService.Paragraph para = paragraphs.get(i);
            String translated = translations.get(i);

            if ("bilingual".equals(mode)) {
                sb.append("【第 ").append(para.pageNumber()).append(" 页 · 段落 ").append(i + 1).append("】\n");
                sb.append("[原文] ").append(para.text()).append("\n\n");
                sb.append("[译文] ").append(translated != null ? translated : "(翻译失败)").append("\n");
                sb.append("-".repeat(40)).append("\n\n");
            } else {
                sb.append(translated != null ? translated : "(翻译失败)").append("\n\n");
            }
        }

        return sb.toString();
    }

    /**
     * 生成翻译后的 PDF 文件
     */
    public byte[] buildTranslatedPdf(String taskId) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("翻译尚未完成");
        }

        try {
            return pdfRenderService.renderTranslatedPdf(
                    session.getPdfBytes(), session.getParagraphs(), session.getTranslations());
        } catch (IOException e) {
            throw new RuntimeException("生成翻译 PDF 失败: " + e.getMessage(), e);
        }
    }
}
