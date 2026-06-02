package com.web.backen.translate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class PdfRenderService {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderService.class);

    // macOS 中文字体路径
    private static final String[] FONT_PATHS = {
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
    };
    private static final Set<String> TEXT_SHOW_OPERATORS = Set.of(
            OperatorName.SHOW_TEXT,
            OperatorName.SHOW_TEXT_ADJUSTED,
            OperatorName.SHOW_TEXT_LINE,
            OperatorName.SHOW_TEXT_LINE_AND_SPACE
    );

    /**
     * 将翻译结果渲染回原始 PDF。
     * 方案：只导出用户选择的页面范围；原页面作为矢量 Form XObject 背景 → 白色遮罩覆盖原文 → 绘制可复制中文译文。
     * 注意：这不是最终的"内容流级替换"，但相比整页截图背景，输出不再是扫描型 PDF，叠加译文可直接复制。
     */
    public byte[] renderTranslatedPdf(byte[] originalPdfBytes,
                                       List<PdfParseService.Paragraph> paragraphs,
                                       List<String> translations,
                                       int startPage,
                                       int endPage) throws IOException {
        try (PDDocument originalDoc = Loader.loadPDF(originalPdfBytes)) {
            int totalPages = originalDoc.getNumberOfPages();
            int effectiveStart = Math.max(1, startPage);
            int effectiveEnd = Math.min(endPage, totalPages);
            if (effectiveStart > effectiveEnd) {
                throw new IOException("页面范围无效");
            }

            // 按页面分组段落
            List<List<IndexedParagraph>> pageParagraphs = groupByPage(paragraphs, translations);

            // 创建新 PDF 文档
            try (PDDocument newDoc = new PDDocument()) {
                // 加载中文字体
                PDType0Font chineseFont = loadChineseFont(newDoc);
                PDFont latinFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                LayerUtility layerUtility = new LayerUtility(newDoc);

                for (int pageIdx = effectiveStart - 1; pageIdx <= effectiveEnd - 1; pageIdx++) {
                    // 获取原页面尺寸
                    PDPage origPage = originalDoc.getPage(pageIdx);
                    PDRectangle mediaBox = origPage.getMediaBox();
                    float pageHeight = mediaBox.getHeight();

                    // 创建新页面（与原页面同尺寸）
                    PDPage newPage = new PDPage(mediaBox);
                    newDoc.addPage(newPage);

                    // 获取该页的翻译段落
                    List<IndexedParagraph> paras = pageIdx < pageParagraphs.size()
                            ? pageParagraphs.get(pageIdx) : List.of();

                    try (PDPageContentStream cs = new PDPageContentStream(newDoc, newPage)) {
                        // 1. 绘制原页面矢量内容作为背景，避免整页栅格化。
                        //    同时删除文本显示操作，减少复制时读到底层英文。
                        PDFormXObject pageForm = layerUtility.importPageAsForm(originalDoc, pageIdx);
                        stripTextShowingOperators(pageForm);
                        cs.drawForm(pageForm);

                        // 2. 对每个翻译段落：白色遮罩 + 中文文字
                        for (IndexedParagraph ip : paras) {
                            PdfParseService.Paragraph p = ip.paragraph();
                            String outputText = p.translatable() ? ip.translated() : p.text();
                            if (outputText == null || outputText.isBlank()) continue;

                            float x = mediaBox.getLowerLeftX() + p.x();
                            float y = mediaBox.getLowerLeftY() + pageHeight - p.y() - p.height();
                            float w = p.width();
                            float h = p.height();
                            float fontSize = p.fontSize();
                            PDFont renderFont = (!p.translatable() && isLatinText(outputText)) ? latinFont : chineseFont;

                            if (p.translatable()) {
                                // 绘制白色矩形遮罩（覆盖原文区域）
                                cs.setNonStrokingColor(1f, 1f, 1f);
                                // 稍微扩大遮罩区域确保完全覆盖
                                float pad = 3f;
                                cs.addRect(x - pad, y - pad, w + pad * 2, h + pad * 2);
                                cs.fill();
                            }

                            TextLayout layout = p.translatable()
                                    ? fitTextLayout(chineseFont, outputText, w, h, fontSize)
                                    : protectedTextLayout(renderFont, outputText, w, fontSize);

                            // 绘制中文译文
                            cs.setNonStrokingColor(0f, 0f, 0f);
                            float renderHeight = Math.max(h, layout.fontSize() * 1.2f);
                            float currentY = y + renderHeight - layout.fontSize();

                            for (String line : layout.lines()) {
                                cs.beginText();
                                cs.setFont(renderFont, layout.fontSize());
                                cs.newLineAtOffset(x, currentY);
                                safeShowText(cs, renderFont, line);
                                cs.endText();
                                currentY -= layout.lineHeight();
                            }
                        }
                    }
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                newDoc.save(out);
                return out.toByteArray();
            }
        }
    }

    /**
     * 加载中文字体
     */
    private PDType0Font loadChineseFont(PDDocument doc) throws IOException {
        for (String path : FONT_PATHS) {
            File fontFile = new File(path);
            if (!fontFile.exists()) continue;

            try {
                log.info("加载中文字体: {}", path);
                return PDType0Font.load(doc, fontFile);
            } catch (Exception e) {
                log.warn("字体加载失败: {} - {}", path, e.getMessage());
            }
        }
        throw new IOException("未找到可用的中文字体");
    }

    /**
     * 安全绘制文字（跳过无法渲染的字符）
     */
    private void safeShowText(PDPageContentStream cs, PDFont font, String text) throws IOException {
        if (font instanceof PDType1Font) {
            cs.showText(text.replaceAll("[^\\x20-\\x7E]", "?"));
            return;
        }

        StringBuilder safeText = new StringBuilder();
        for (char c : text.toCharArray()) {
            try {
                // 测试字符是否能被编码
                font.encode(String.valueOf(c));
                safeText.append(c);
            } catch (Exception e) {
                // 无法渲染的字符用方框替代
                safeText.append('□');
            }
        }
        cs.showText(safeText.toString());
    }

    /**
     * 文字自动换行（基于字体真实宽度）
     */
    private List<String> wrapText(PDFont font, String text, float maxWidth, float fontSize) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                lines.add(line.toString());
                line = new StringBuilder();
                continue;
            }

            String candidate = line + String.valueOf(c);
            if (textWidth(font, candidate, fontSize) > maxWidth && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder();
            }

            line.append(c);
        }

        if (line.length() > 0) {
            lines.add(line.toString());
        }

        return lines;
    }

    private TextLayout fitTextLayout(PDType0Font font, String text, float width, float height, float sourceFontSize)
            throws IOException {
        float max = Math.max(sourceFontSize * 0.95f, 8f);
        float min = 4.2f;
        for (float size = max; size >= min; size -= 0.5f) {
            List<String> lines = wrapText(font, text, width, size);
            for (float multiplier : List.of(1.35f, 1.2f, 1.05f)) {
                float lineHeight = size * multiplier;
                if (lines.size() * lineHeight <= height + size * 0.35f) {
                    return new TextLayout(lines, size, lineHeight, false);
                }
            }
        }

        List<String> lines = wrapText(font, text, width, min);
        return new TextLayout(lines, min, min * 1.05f, true);
    }

    private TextLayout protectedTextLayout(PDFont font, String text, float width, float sourceFontSize)
            throws IOException {
        float fontSize = Math.max(sourceFontSize, 5.5f);
        float requiredWidth = textWidth(font, text, fontSize);
        while (requiredWidth > Math.max(width, 1f) && fontSize > 4.2f) {
            fontSize -= 0.5f;
            requiredWidth = textWidth(font, text, fontSize);
        }
        return new TextLayout(List.of(text), fontSize, fontSize * 1.2f, false);
    }

    private float textWidth(PDFont font, String text, float fontSize) throws IOException {
        StringBuilder safeText = new StringBuilder();
        for (char c : text.toCharArray()) {
            try {
                font.encode(String.valueOf(c));
                safeText.append(c);
            } catch (Exception ignored) {
                safeText.append('□');
            }
        }
        return font.getStringWidth(safeText.toString()) / 1000f * fontSize;
    }

    private boolean isLatinText(String text) {
        return text != null && text.chars().allMatch(c -> c <= 0x7F);
    }

    private record TextLayout(List<String> lines, float fontSize, float lineHeight, boolean overflow) {}

    /**
     * 删除导入页面 Form XObject 中的文本显示操作，保留图片、路径、表格线等矢量背景。
     * 这是向“内容流级替换”迈的一步：输出 PDF 复制时不再优先读到底层英文。
     */
    private void stripTextShowingOperators(PDFormXObject form) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(form);
        List<Object> tokens = parser.parse();
        List<Object> kept = new ArrayList<>();
        int operandStart = 0;

        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);
            if (!(token instanceof Operator operator)) {
                continue;
            }

            if (!TEXT_SHOW_OPERATORS.contains(operator.getName())) {
                kept.addAll(tokens.subList(operandStart, i + 1));
            }
            operandStart = i + 1;
        }
        if (operandStart < tokens.size()) {
            kept.addAll(tokens.subList(operandStart, tokens.size()));
        }

        try (OutputStream out = form.getContentStream().createOutputStream()) {
            new ContentStreamWriter(out).writeTokens(kept);
        }
    }

    /**
     * 按页面分组段落
     */
    private List<List<IndexedParagraph>> groupByPage(List<PdfParseService.Paragraph> paragraphs,
                                                      List<String> translations) {
        int maxPage = 0;
        for (PdfParseService.Paragraph p : paragraphs) {
            if (p.pageNumber() > maxPage) maxPage = p.pageNumber();
        }

        List<List<IndexedParagraph>> pages = new ArrayList<>();
        for (int i = 0; i < maxPage; i++) {
            pages.add(new ArrayList<>());
        }

        for (int i = 0; i < paragraphs.size(); i++) {
            PdfParseService.Paragraph p = paragraphs.get(i);
            String trans = i < translations.size() ? translations.get(i) : null;
            pages.get(p.pageNumber() - 1).add(new IndexedParagraph(p, trans));
        }

        return pages;
    }

    private record IndexedParagraph(PdfParseService.Paragraph paragraph, String translated) {}
}
