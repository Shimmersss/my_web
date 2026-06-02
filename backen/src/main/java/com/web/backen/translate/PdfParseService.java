package com.web.backen.translate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class PdfParseService {

    private static final Pattern PAGE_NUMBER_PATTERN = Pattern.compile("^\\s*\\d+\\s*$");

    /**
     * 获取 PDF 总页数
     */
    public int getTotalPages(byte[] pdfBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return document.getNumberOfPages();
        }
    }

    /**
     * 从 PDF 字节数组中提取带位置信息的段落列表（全部页面）
     */
    public List<Paragraph> extractParagraphs(byte[] pdfBytes) throws IOException {
        return extractParagraphs(pdfBytes, 1, Integer.MAX_VALUE);
    }

    /**
     * 从 PDF 字节数组中提取指定页面范围的段落列表
     * @param startPage 起始页码（从 1 开始）
     * @param endPage 结束页码（包含）
     */
    public List<Paragraph> extractParagraphs(byte[] pdfBytes, int startPage, int endPage) throws IOException {
        if (pdfBytes.length < 4 || pdfBytes[0] != '%' || pdfBytes[1] != 'P'
                || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
            throw new IllegalArgumentException("上传的文件不是有效的 PDF 格式");
        }

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("PDF 已加密，请先解密后再上传");
            }

            // 第一步：用标准 stripper 提取纯文本（用于段落内容）
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);

            // 第二步：用位置捕获 stripper 提取坐标
            PositionStripper posStripper = new PositionStripper();
            posStripper.setSortByPosition(true);

            int totalPages = document.getNumberOfPages();
            int effectiveEnd = Math.min(endPage, totalPages);
            List<Paragraph> paragraphs = new ArrayList<>();
            int globalIndex = 0;

            for (int page = startPage; page <= effectiveEnd; page++) {
                // 提取纯文本并按段落拆分
                textStripper.setStartPage(page);
                textStripper.setEndPage(page);
                String pageText = textStripper.getText(document);
                if (pageText == null || pageText.isBlank()) continue;

                String[] textParagraphs = pageText.split("\\n\\s*\\n");

                // 提取每行的位置信息
                posStripper.setStartPage(page);
                posStripper.setEndPage(page);
                posStripper.lineBoxes.clear();
                posStripper.getText(document);

                List<LineBox> pageLines = new ArrayList<>(posStripper.lineBoxes);
                int lineIdx = 0;

                for (String tp : textParagraphs) {
                    String trimmed = tp.trim().replaceAll("\\n", " ").replaceAll("\\s+", " ").trim();
                    if (trimmed.isEmpty()) continue;
                    if (PAGE_NUMBER_PATTERN.matcher(trimmed).matches()) continue;
                    if (trimmed.length() < 15) continue;

                    // 计算这个段落包含多少行（根据原文中的换行数）
                    int lineCount = tp.split("\\n").length;

                    // 从 pageLines 中取对应行数，计算 bounding box
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                    float maxX = 0, maxY = 0;
                    float avgFontSize = 0;
                    int collected = 0;

                    for (int i = 0; i < lineCount && lineIdx < pageLines.size(); i++, lineIdx++) {
                        LineBox lb = pageLines.get(lineIdx);
                        if (lb.x < minX) minX = lb.x;
                        if (lb.y < minY) minY = lb.y;
                        if (lb.x + lb.width > maxX) maxX = lb.x + lb.width;
                        if (lb.y + lb.height > maxY) maxY = lb.y + lb.height;
                        avgFontSize += lb.fontSize;
                        collected++;
                    }

                    if (collected > 0) {
                        avgFontSize /= collected;
                        paragraphs.add(new Paragraph(page, globalIndex, trimmed,
                                minX, minY, maxX - minX, maxY - minY, avgFontSize));
                    } else {
                        // 位置信息不可用时，用默认值
                        paragraphs.add(new Paragraph(page, globalIndex, trimmed,
                                72, 72, 468, 20, 12));
                    }
                    globalIndex++;
                }
            }

            if (paragraphs.isEmpty()) {
                throw new IllegalArgumentException("该 PDF 无可提取的文本内容，可能为扫描件（暂不支持 OCR）");
            }

            return paragraphs;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("encrypted")) {
                throw new IllegalArgumentException("PDF 已加密，请先解密后再上传", e);
            }
            throw new IOException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 自定义 PDFTextStripper，捕获每行文字的位置信息
     */
    private static class PositionStripper extends PDFTextStripper {
        final List<LineBox> lineBoxes = new ArrayList<>();
        private final List<TextPosition> currentLinePositions = new ArrayList<>();

        public PositionStripper() throws IOException {}

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions != null && !positions.isEmpty()) {
                currentLinePositions.addAll(positions);
            }
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            if (!currentLinePositions.isEmpty()) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = 0, maxY = 0;
                float avgFontSize = 0;

                for (TextPosition tp : currentLinePositions) {
                    float x = tp.getXDirAdj();
                    float y = tp.getYDirAdj();
                    float w = tp.getWidthDirAdj();
                    float h = tp.getHeightDir();
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x + w > maxX) maxX = x + w;
                    if (y + h > maxY) maxY = y + h;
                    avgFontSize += tp.getFontSizeInPt();
                }
                avgFontSize /= currentLinePositions.size();

                lineBoxes.add(new LineBox(minX, minY, maxX - minX, maxY - minY, avgFontSize));
                currentLinePositions.clear();
            }
            super.writeLineSeparator();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            // 处理最后一行（如果没有以换行结尾）
            if (!currentLinePositions.isEmpty()) {
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
                float maxX = 0, maxY = 0;
                float avgFontSize = 0;
                for (TextPosition tp : currentLinePositions) {
                    float x = tp.getXDirAdj();
                    float y = tp.getYDirAdj();
                    float w = tp.getWidthDirAdj();
                    float h = tp.getHeightDir();
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x + w > maxX) maxX = x + w;
                    if (y + h > maxY) maxY = y + h;
                    avgFontSize += tp.getFontSizeInPt();
                }
                avgFontSize /= currentLinePositions.size();
                lineBoxes.add(new LineBox(minX, minY, maxX - minX, maxY - minY, avgFontSize));
                currentLinePositions.clear();
            }
            super.endPage(page);
        }
    }

    /**
     * 一行文字的 bounding box
     */
    record LineBox(float x, float y, float width, float height, float fontSize) {}

    /**
     * 段落记录（带位置信息）
     */
    public record Paragraph(int pageNumber, int index, String text,
                            float x, float y, float width, float height, float fontSize) {}
}
