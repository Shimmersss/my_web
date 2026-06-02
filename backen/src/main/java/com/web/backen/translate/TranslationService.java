package com.web.backen.translate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
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
    private final BabelDocService babelDocService;
    private final ConcurrentHashMap<String, TranslationSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public TranslationService(PdfParseService pdfParseService, BabelDocService babelDocService) {
        this.pdfParseService = pdfParseService;
        this.babelDocService = babelDocService;
    }

    /**
     * 创建翻译任务预览：只获取 PDF 页数，不提取段落
     */
    public TranslationSession createSessionPreview(String fileName, byte[] pdfBytes) throws Exception {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        int totalPages = pdfParseService.getTotalPages(pdfBytes);
        TranslationSession session = new TranslationSession(taskId, fileName, pdfBytes);
        session.setTotalPages(totalPages);
        session.setPageRange(1, totalPages);
        sessions.put(taskId, session);
        log.info("创建翻译预览: taskId={}, file={}, totalPages={}", taskId, fileName, totalPages);
        return session;
    }

    /**
     * 开始翻译：记录页面范围，PDF 解析和翻译统一交给 BabelDOC
     */
    public TranslationSession startTranslation(String taskId, int startPage, int endPage, String fontFamily,
                                               int qps) throws Exception {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }

        int effectiveStart = Math.max(1, startPage);
        int effectiveEnd = Math.min(endPage, session.getTotalPages());
        if (effectiveStart > effectiveEnd) {
            throw new IllegalArgumentException("页面范围无效");
        }

        session.setPageRange(effectiveStart, effectiveEnd);
        session.setFontFamily(validateFontFamily(fontFamily));
        session.setQps(validateQps(qps));
        session.setStatus("ready");
        log.info("准备 BabelDOC 翻译: taskId={}, pages={}-{}, fontFamily={}, qps={}",
                taskId, effectiveStart, effectiveEnd, session.getFontFamily(), session.getQps());
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

        synchronized (session) {
            if ("translating".equals(session.getStatus())) {
                try {
                    emitter.send(SseEmitter.event().name("layout").data(
                            Map.of("message", "BabelDOC 翻译任务已在运行")
                    ));
                } catch (IOException ignored) {}
                emitter.complete();
                return;
            }
            if ("completed".equals(session.getStatus())) {
                try {
                    emitter.send(SseEmitter.event().name("done").data(Map.of("taskId", taskId)));
                } catch (IOException ignored) {}
                emitter.complete();
                return;
            }
            session.setStatus("translating");
        }

        executor.submit(() -> {
            try {
                try {
                    emitter.send(SseEmitter.event().name("layout").data(
                            Map.of("message", "正在使用 BabelDOC 分析版面、翻译并重建 PDF")
                    ));
                } catch (IOException e) {
                    log.warn("SSE 客户端已断开，BabelDOC 继续执行: taskId={}", taskId);
                }
                BabelDocService.TranslationResult result = babelDocService.translatePdf(
                        session.getPdfBytes(), session.getFileName(),
                        session.getStartPage(), session.getEndPage(), session.getFontFamily(), session.getQps(),
                        progress -> sendProgress(session, emitter, progress));
                session.setTranslatedPdfBytes(result.translatedPdfBytes());
                session.setBilingualPdfBytes(result.bilingualPdfBytes());
                session.setProgress(100);
                session.setProgressStage("completed");
                session.setStatus("completed");

                try {
                    emitter.send(SseEmitter.event().name("done").data(Map.of("taskId", taskId)));
                } catch (IOException e) {
                    log.warn("SSE 完成事件未送达，客户端可通过 status 恢复: taskId={}", taskId);
                }
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
     * 构建下载内容
     */
    public String buildDownloadContent(String taskId) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("翻译尚未完成");
        }

        byte[] translatedPdf = session.getTranslatedPdfBytes();
        if (translatedPdf == null || translatedPdf.length == 0) {
            throw new IllegalStateException("BabelDOC 翻译 PDF 尚未生成");
        }
        try (var document = Loader.loadPDF(translatedPdf)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new IllegalStateException("提取中文 TXT 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成翻译后的 PDF 文件
     */
    public byte[] buildTranslatedPdf(String taskId, String mode) {
        TranslationSession session = sessions.get(taskId);
        if (session == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        if (!"completed".equals(session.getStatus())) {
            throw new IllegalStateException("翻译尚未完成");
        }

        if (!Set.of("translated", "bilingual").contains(mode)) {
            throw new IllegalArgumentException("不支持的 PDF 模式: " + mode);
        }
        byte[] translatedPdf = "bilingual".equals(mode)
                ? session.getBilingualPdfBytes()
                : session.getTranslatedPdfBytes();
        if (translatedPdf == null || translatedPdf.length == 0) {
            throw new IllegalStateException("BabelDOC 翻译 PDF 尚未生成");
        }
        return translatedPdf;
    }

    private String validateFontFamily(String fontFamily) {
        String value = fontFamily == null || fontFamily.isBlank() ? "auto" : fontFamily;
        if (!Set.of("auto", "serif", "sans-serif", "script").contains(value)) {
            throw new IllegalArgumentException("不支持的字体族: " + value);
        }
        return value;
    }

    private int validateQps(int qps) {
        if (qps < 1 || qps > 12) {
            throw new IllegalArgumentException("并发数必须在 1-12 之间");
        }
        return qps;
    }

    private void sendProgress(TranslationSession session, SseEmitter emitter,
                              BabelDocService.ProgressUpdate progress) {
        if (Double.compare(session.getProgress(), progress.progress()) == 0
                && Objects.equals(session.getProgressStage(), progress.stage())) {
            return;
        }
        session.setProgress(progress.progress());
        session.setProgressStage(progress.stage());
        try {
            emitter.send(SseEmitter.event().name("progress").data(Map.of(
                    "progress", progress.progress(),
                    "stage", progress.stage(),
                    "stageLabel", stageLabel(progress.stage()),
                    "current", progress.current(),
                    "total", progress.total()
            )));
        } catch (IOException ignored) {
            // The task continues; the browser can recover the latest progress through /status.
        }
    }

    public String stageLabel(String stage) {
        return switch (stage) {
            case "Parse PDF and Create Intermediate Representation" -> "解析 PDF";
            case "DetectScannedFile" -> "检测 PDF 类型";
            case "Parse Page Layout" -> "分析页面版式";
            case "Parse Tables" -> "识别表格";
            case "Parse Paragraphs" -> "识别段落";
            case "Parse Formulas and Styles" -> "识别公式与样式";
            case "Translate Paragraphs" -> "翻译正文";
            case "Typesetting" -> "重新排版";
            case "Add Fonts" -> "映射字体";
            case "Generate drawing instructions" -> "生成绘制指令";
            case "Subset font" -> "嵌入字体";
            case "Save PDF" -> "保存 PDF";
            case "completed" -> "翻译完成";
            default -> stage == null || stage.isBlank() ? "处理中" : stage;
        };
    }
}
