package com.web.backen.translate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
public class PdfRenderService {

    private static final Logger log = LoggerFactory.getLogger(PdfRenderService.class);

    private static final float DPI = 200f; // 渲染 DPI（越高越清晰，文件越大）

    // macOS 中文字体路径
    private static final String[] FONT_PATHS = {
            "/Library/Fonts/Arial Unicode.ttf",
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
    };

    /**
     * 将翻译结果渲染回原始 PDF
     * 方案：原页面渲染为图片作为背景 → 白色遮罩覆盖原文 → 绘制中文译文
     * 这样完美保留图片、公式、表格等所有视觉元素
     */
    public byte[] renderTranslatedPdf(byte[] originalPdfBytes,
                                       List<PdfParseService.Paragraph> paragraphs,
                                       List<String> translations) throws IOException {
        // 加载原始 PDF 用于渲染页面图片
        try (PDDocument originalDoc = Loader.loadPDF(originalPdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(originalDoc);
            int totalPages = originalDoc.getNumberOfPages();

            // 按页面分组段落
            List<List<IndexedParagraph>> pageParagraphs = groupByPage(paragraphs, translations);

            // 创建新 PDF 文档
            try (PDDocument newDoc = new PDDocument()) {
                // 加载中文字体
                PDType0Font chineseFont = loadChineseFont(newDoc);

                for (int pageIdx = 0; pageIdx < totalPages; pageIdx++) {
                    // 获取原页面尺寸
                    PDPage origPage = originalDoc.getPage(pageIdx);
                    PDRectangle mediaBox = origPage.getMediaBox();
                    float pageWidth = mediaBox.getWidth();
                    float pageHeight = mediaBox.getHeight();

                    // 将原页面渲染为高清图片
                    BufferedImage pageImage = renderer.renderImageWithDPI(pageIdx, DPI);
                    ByteArrayOutputStream imgOut = new ByteArrayOutputStream();
                    ImageIO.write(pageImage, "png", imgOut);
                    PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                            newDoc, imgOut.toByteArray(), "page_" + pageIdx + ".png");

                    // 创建新页面（与原页面同尺寸）
                    PDPage newPage = new PDPage(mediaBox);
                    newDoc.addPage(newPage);

                    // 获取该页的翻译段落
                    List<IndexedParagraph> paras = pageIdx < pageParagraphs.size()
                            ? pageParagraphs.get(pageIdx) : List.of();

                    try (PDPageContentStream cs = new PDPageContentStream(newDoc, newPage)) {
                        // 1. 绘制原页面图片作为背景（完美保留所有视觉内容）
                        cs.drawImage(pdImage, 0, 0, pageWidth, pageHeight);

                        // 2. 对每个翻译段落：白色遮罩 + 中文文字
                        for (IndexedParagraph ip : paras) {
                            if (ip.translated == null || ip.translated.isBlank()) continue;

                            PdfParseService.Paragraph p = ip.paragraph();
                            float x = p.x();
                            float y = p.y();
                            float w = p.width();
                            float h = p.height();
                            float fontSize = p.fontSize();

                            // 计算图片坐标到 PDF 坐标的缩放比例
                            float scaleX = pageWidth / (float) pageImage.getWidth();
                            float scaleY = pageHeight / (float) pageImage.getHeight();

                            // 绘制白色矩形遮罩（覆盖原文区域）
                            cs.setNonStrokingColor(1f, 1f, 1f);
                            // 稍微扩大遮罩区域确保完全覆盖
                            float pad = 3f;
                            cs.addRect(x - pad, y - pad, w + pad * 2, h + pad * 2);
                            cs.fill();

                            // 计算合适的中文字号
                            float cnFontSize = Math.max(fontSize * 0.9f, 8f);
                            float lineHeight = cnFontSize * 1.6f;

                            // 自动换行
                            List<String> lines = wrapText(ip.translated(), w, cnFontSize);

                            // 绘制中文译文
                            cs.setNonStrokingColor(0f, 0f, 0f);
                            float currentY = y + h - cnFontSize;

                            for (String line : lines) {
                                if (currentY < y) break; // 超出区域则停止
                                cs.beginText();
                                cs.setFont(chineseFont, cnFontSize);
                                cs.newLineAtOffset(x, currentY);
                                safeShowText(cs, chineseFont, line);
                                cs.endText();
                                currentY -= lineHeight;
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
    private void safeShowText(PDPageContentStream cs, PDType0Font font, String text) throws IOException {
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
     * 文字自动换行（基于字符宽度估算）
     */
    private List<String> wrapText(String text, float maxWidth, float fontSize) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        float lineWidth = 0;
        // 中文字符宽度约等于字号，英文约等于字号的一半
        float charWidthCn = fontSize * 1.0f;
        float charWidthEn = fontSize * 0.55f;
        float charWidthSpace = fontSize * 0.3f;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                lines.add(line.toString());
                line = new StringBuilder();
                lineWidth = 0;
                continue;
            }

            float charWidth;
            if (c > 0x7F) {
                charWidth = charWidthCn; // 中文字符
            } else if (c == ' ') {
                charWidth = charWidthSpace;
            } else {
                charWidth = charWidthEn; // 英文字符
            }

            if (lineWidth + charWidth > maxWidth && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder();
                lineWidth = 0;
            }

            line.append(c);
            lineWidth += charWidth;
        }

        if (line.length() > 0) {
            lines.add(line.toString());
        }

        return lines;
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
