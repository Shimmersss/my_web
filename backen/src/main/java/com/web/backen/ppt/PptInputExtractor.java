package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.PptGenerationConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class PptInputExtractor {

    private static final Pattern XML_TEXT = Pattern.compile("<w:t[^>]*>([\\s\\S]*?)</w:t>");
    private static final Pattern DOCX_TABLE = Pattern.compile("<w:tbl[\\s\\S]*?</w:tbl>");
    private static final Pattern DOCX_TABLE_ROW = Pattern.compile("<w:tr[\\s\\S]*?</w:tr>");
    private static final Pattern DOCX_TABLE_CELL = Pattern.compile("<w:tc[\\s\\S]*?</w:tc>");
    private static final Pattern GENERIC_XML_TEXT = Pattern.compile("<t[^>]*>([\\s\\S]*?)</t>");
    private static final Pattern XLSX_ROW = Pattern.compile("<row[\\s\\S]*?</row>");
    private static final Pattern XLSX_CELL = Pattern.compile("<c\\b([^>]*)>[\\s\\S]*?</c>");
    private static final Pattern XLSX_VALUE = Pattern.compile("<v[^>]*>([\\s\\S]*?)</v>");
    private static final Pattern XLSX_INLINE_STRING = Pattern.compile("<is[\\s\\S]*?</is>");
    private static final Pattern PPT_TEXT = Pattern.compile("<a:t>([\\s\\S]*?)</a:t>");
    private static final Pattern PPT_COLOR = Pattern.compile("\\b(?:srgbClr\\s+val|color)=\"([0-9A-Fa-f]{6})\"");
    private static final Pattern PPT_SLIDE_NAME = Pattern.compile("ppt/slides/slide(\\d+)\\.xml");

    private final PptGenerationConfig config;
    private final ObjectMapper objectMapper;

    public PptInputExtractor(PptGenerationConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public String extractPaperText(Path paper, String fileName, Path imagesDir) throws IOException {
        return extractPaperText(paper, fileName, imagesDir, 50);
    }

    public String extractPaperText(Path paper, String fileName, Path imagesDir, int extractionPercent) throws IOException {
        if (paper == null || fileName == null || !Files.isRegularFile(paper)) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".pdf")) {
            try (var document = Loader.loadPDF(paper.toFile())) {
                text = new PDFTextStripper().getText(document);
                extractPdfPageImages(document, imagesDir, extractionPercent);
            }
        } else if (lower.endsWith(".docx")) {
            text = extractDocxTextAndImages(paper, imagesDir, extractionPercent);
        } else {
            throw new IllegalArgumentException("论文文件仅支持 PDF 或 DOCX");
        }
        return limitText(cleanText(text), config.getMaxPaperTextChars());
    }

    public void extractTemplateStyle(Path template, Path taskDir) throws IOException {
        extractTemplateStyle(template, taskDir, 50);
    }

    public void extractTemplateStyle(Path template, Path taskDir, int extractionPercent) throws IOException {
        Map<String, Object> style = new LinkedHashMap<>();
        style.put("palette", List.of("005BAC", "063A78", "D9A441", "EFF6FF", "1F2937"));
        style.put("layout", "wide");
        style.put("assetsDir", "");
        style.put("frameworkMode", false);
        style.put("templateFramework", List.of());

        if (template != null && Files.isRegularFile(template)) {
            Path assetsDir = Files.createDirectories(taskDir.resolve("template-assets"));
            clearDirectory(assetsDir);
            TemplateScan scan = scanTemplate(template, assetsDir, extractionPercent);
            if (!scan.colors().isEmpty()) style.put("palette", scan.colors());
            style.put("textSamples", scan.textSamples());
            style.put("frameworkMode", true);
            style.put("templateFramework", scan.framework());
            if (scan.assetsCopied() > 0) style.put("assetsDir", assetsDir.toAbsolutePath().toString());
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskDir.resolve("style.json").toFile(), style);
    }

    private String extractDocxTextAndImages(Path docx, Path imagesDir, int extractionPercent) throws IOException {
        Files.createDirectories(imagesDir);
        clearDirectory(imagesDir);
        StringBuilder text = new StringBuilder();
        int imageLimit = scaledLimit(config.getMaxExtractedImages(), extractionPercent);
        List<TableCandidate> tableCandidates = new ArrayList<>();
        int mediaCandidates = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("word/document.xml".equals(name)) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher matcher = XML_TEXT.matcher(xml);
                    while (matcher.find()) {
                        text.append(unescapeXml(matcher.group(1))).append(' ');
                    }
                    Matcher tableMatcher = DOCX_TABLE.matcher(xml);
                    int tableIndex = 1;
                    while (tableMatcher.find()) {
                        String tableXml = tableMatcher.group();
                        String tableText = extractXmlText(tableXml);
                        if (!tableText.isBlank()) {
                            text.append("\n[表格 ").append(tableIndex).append("] ").append(tableText).append('\n');
                            List<List<String>> rows = extractDocxTableRows(tableXml);
                            if (!rows.isEmpty()) tableCandidates.add(new TableCandidate(rows, tableIndex, false));
                            tableIndex++;
                        }
                    }
                } else if (name.startsWith("word/media/")) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        mediaCandidates++;
                    }
                } else if (name.startsWith("word/embeddings/") && name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                    List<List<String>> rows = extractXlsxRows(zip.readAllBytes());
                    if (!rows.isEmpty()) {
                        int tableIndex = tableCandidates.size() + 1;
                        text.append("\n[Excel表格 ").append(tableIndex).append("] ")
                                .append(rowsToText(rows)).append('\n');
                        tableCandidates.add(new TableCandidate(rows, tableIndex, true));
                    }
                }
                zip.closeEntry();
            }
        }

        int mediaLimit = mediaCandidates == 0 ? 0 : Math.min(mediaCandidates, Math.max(1, (int) Math.ceil(imageLimit * 0.6)));
        int copiedMedia = extractDocxMedia(docx, imagesDir, mediaLimit);
        int tableLimit = Math.max(0, imageLimit - copiedMedia);
        for (int index = 0; index < Math.min(tableLimit, tableCandidates.size()); index++) {
            TableCandidate candidate = tableCandidates.get(index);
            String prefix = candidate.excel() ? "paper-excel-" : "paper-table-";
            renderDocxTableImage(candidate.rows(), imagesDir.resolve(prefix + (index + 1) + ".png"), candidate.index());
        }
        return text.toString();
    }

    private int extractDocxMedia(Path docx, Path imagesDir, int limit) throws IOException {
        if (limit <= 0) return 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null && count < limit) {
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (lower.startsWith("word/media/")
                        && (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                    String ext = lower.endsWith(".png") ? ".png" : ".jpg";
                    Files.copy(zip, imagesDir.resolve("paper-image-" + (++count) + ext), StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    private void extractPdfPageImages(org.apache.pdfbox.pdmodel.PDDocument document, Path imagesDir, int extractionPercent) throws IOException {
        Files.createDirectories(imagesDir);
        clearDirectory(imagesDir);
        PDFRenderer renderer = new PDFRenderer(document);
        int percentLimit = (int) Math.ceil(document.getNumberOfPages() * (clampPercent(extractionPercent) / 100.0));
        int count = Math.min(document.getNumberOfPages(), Math.max(1, Math.min(config.getMaxExtractedImages(), percentLimit)));
        for (int pageIndex : sampledPageIndexes(document.getNumberOfPages(), count)) {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 96, ImageType.RGB);
            Path target = imagesDir.resolve("paper-page-" + (pageIndex + 1) + ".png");
            ImageIO.write(image, "png", target.toFile());
            image.flush();
        }
    }

    private List<Integer> sampledPageIndexes(int totalPages, int count) {
        if (totalPages <= 0 || count <= 0) return List.of();
        if (count >= totalPages) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < totalPages; i++) all.add(i);
            return all;
        }
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            int index = (int) Math.round(i * (totalPages - 1) / (double) Math.max(1, count - 1));
            indexes.add(Math.max(0, Math.min(totalPages - 1, index)));
        }
        int fallback = 0;
        while (indexes.size() < count && fallback < totalPages) {
            indexes.add(fallback++);
        }
        return new ArrayList<>(indexes);
    }

    private TemplateScan scanTemplate(Path pptx, Path assetsDir, int extractionPercent) throws IOException {
        LinkedHashSet<String> colors = new LinkedHashSet<>();
        List<String> samples = new ArrayList<>();
        List<Map<String, Object>> framework = new ArrayList<>();
        int assets = 0;
        int frameworkLimit = scaledLimit(16, extractionPercent);
        int assetLimit = scaledLimit(24, extractionPercent);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(pptx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.endsWith(".xml")) {
                    String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher colorMatcher = PPT_COLOR.matcher(xml);
                    while (colorMatcher.find() && colors.size() < 8) {
                        String color = colorMatcher.group(1).toUpperCase(Locale.ROOT);
                        if (!Set.of("FFFFFF", "000000").contains(color)) colors.add(color);
                    }
                    if (samples.size() < 12 && name.startsWith("ppt/slides/slide")) {
                        Matcher textMatcher = PPT_TEXT.matcher(xml);
                        List<String> slideSamples = new ArrayList<>();
                        while (textMatcher.find() && samples.size() < 12) {
                            String sample = cleanText(unescapeXml(textMatcher.group(1)));
                            if (sample.length() >= 2 && sample.length() <= 80) {
                                samples.add(sample);
                                slideSamples.add(sample);
                            }
                        }
                        Matcher slideMatcher = PPT_SLIDE_NAME.matcher(name);
                        if (slideMatcher.matches() && framework.size() < frameworkLimit) {
                            framework.add(Map.of(
                                    "index", Integer.parseInt(slideMatcher.group(1)),
                                    "role", inferTemplateRole(xml, slideSamples),
                                    "textBlocks", countMatches(xml, "<p:sp\\b"),
                                    "images", countMatches(xml, "<a:blip\\b"),
                                    "tables", countMatches(xml, "<a:tbl\\b"),
                                    "samples", slideSamples.stream().limit(4).toList()
                            ));
                        }
                    }
                } else if (name.startsWith("ppt/media/") && assets < assetLimit) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        String ext = lower.endsWith(".png") ? ".png" : ".jpg";
                        Files.copy(zip, assetsDir.resolve("template-asset-" + (++assets) + ext), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                zip.closeEntry();
            }
        }
        framework.sort(Comparator.comparingInt(item -> (Integer) item.get("index")));
        return new TemplateScan(new ArrayList<>(colors), samples, assets, framework);
    }

    private String inferTemplateRole(String xml, List<String> samples) {
        String text = String.join(" ", samples).toLowerCase(Locale.ROOT);
        if (text.contains("谢谢") || text.contains("thank")) return "thanks";
        if (text.contains("目录") || text.contains("contents") || text.contains("agenda")) return "contents";
        int images = countMatches(xml, "<a:blip\\b");
        int tables = countMatches(xml, "<a:tbl\\b");
        int textBlocks = countMatches(xml, "<p:sp\\b");
        if (tables > 0) return "comparison";
        if (images >= 2) return "image";
        if (textBlocks <= 3 && images == 0) return "section";
        if (images == 1) return "image-content";
        return "content";
    }

    private int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private String extractXmlText(String xml) {
        StringBuilder builder = new StringBuilder();
        Matcher matcher = XML_TEXT.matcher(xml);
        while (matcher.find()) {
            builder.append(unescapeXml(matcher.group(1))).append(' ');
        }
        return cleanText(builder.toString());
    }

    private List<List<String>> extractDocxTableRows(String tableXml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = DOCX_TABLE_ROW.matcher(tableXml);
        while (rowMatcher.find() && rows.size() < 24) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = DOCX_TABLE_CELL.matcher(rowMatcher.group());
            while (cellMatcher.find() && cells.size() < 8) {
                String text = extractXmlText(cellMatcher.group());
                cells.add(limitCellText(text));
            }
            if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private List<List<String>> extractXlsxRows(byte[] bytes) throws IOException {
        List<String> sharedStrings = new ArrayList<>();
        String firstSheetXml = "";
        try (ZipInputStream nested = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = nested.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedStrings = extractXlsxSharedStrings(new String(nested.readAllBytes(), StandardCharsets.UTF_8));
                } else if (firstSheetXml.isBlank() && name.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    firstSheetXml = new String(nested.readAllBytes(), StandardCharsets.UTF_8);
                }
                nested.closeEntry();
            }
        }
        if (firstSheetXml.isBlank()) return List.of();
        List<List<String>> rows = new ArrayList<>();
        Matcher rowMatcher = XLSX_ROW.matcher(firstSheetXml);
        while (rowMatcher.find() && rows.size() < 24) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = XLSX_CELL.matcher(rowMatcher.group());
            while (cellMatcher.find() && cells.size() < 8) {
                String cellXml = cellMatcher.group();
                String attrs = cellMatcher.group(1);
                cells.add(limitCellText(extractXlsxCellValue(cellXml, attrs, sharedStrings)));
            }
            if (cells.stream().anyMatch(cell -> !cell.isBlank())) rows.add(cells);
        }
        return rows;
    }

    private List<String> extractXlsxSharedStrings(String xml) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile("<si[\\s\\S]*?</si>").matcher(xml);
        while (matcher.find()) {
            values.add(extractGenericText(matcher.group()));
        }
        return values;
    }

    private String extractXlsxCellValue(String cellXml, String attrs, List<String> sharedStrings) {
        Matcher inlineMatcher = XLSX_INLINE_STRING.matcher(cellXml);
        if (inlineMatcher.find()) {
            return extractGenericText(inlineMatcher.group());
        }
        Matcher valueMatcher = XLSX_VALUE.matcher(cellXml);
        if (!valueMatcher.find()) return "";
        String raw = unescapeXml(valueMatcher.group(1));
        if (attrs != null && attrs.contains("t=\"s\"")) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
            } catch (NumberFormatException ignored) {
                return "";
            }
        }
        return raw;
    }

    private String extractGenericText(String xml) {
        StringBuilder builder = new StringBuilder();
        Matcher matcher = GENERIC_XML_TEXT.matcher(xml);
        while (matcher.find()) {
            builder.append(unescapeXml(matcher.group(1))).append(' ');
        }
        return cleanText(builder.toString());
    }

    private String rowsToText(List<List<String>> rows) {
        return rows.stream()
                .limit(8)
                .map(row -> String.join(" | ", row))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    private void renderDocxTableImage(List<List<String>> rows, Path target, int tableIndex) throws IOException {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns <= 0) return;
        columns = Math.min(columns, 8);
        int cellWidth = Math.max(120, Math.min(210, 1120 / columns));
        int padding = 12;
        Font titleFont = new Font("SansSerif", Font.BOLD, 22);
        Font headerFont = new Font("SansSerif", Font.BOLD, 18);
        Font bodyFont = new Font("SansSerif", Font.PLAIN, 17);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D probeGraphics = probe.createGraphics();
        probeGraphics.setFont(bodyFont);
        FontMetrics bodyMetrics = probeGraphics.getFontMetrics();
        int[] rowHeights = new int[rows.size()];
        for (int row = 0; row < rows.size(); row++) {
            int maxLines = 1;
            for (int col = 0; col < columns; col++) {
                String value = col < rows.get(row).size() ? rows.get(row).get(col) : "";
                maxLines = Math.max(maxLines, wrapText(value, bodyMetrics, cellWidth - padding * 2).size());
            }
            rowHeights[row] = Math.max(42, maxLines * 22 + padding * 2);
        }
        probeGraphics.dispose();
        probe.flush();

        int width = columns * cellWidth + 2;
        int height = 54 + Arrays.stream(rowHeights).sum() + 2;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.setFont(titleFont);
        graphics.setColor(new Color(31, 41, 55));
        graphics.drawString("表格 " + tableIndex, 14, 34);

        int y = 54;
        for (int row = 0; row < rows.size(); row++) {
            boolean header = row == 0;
            graphics.setColor(header ? new Color(239, 246, 255) : Color.WHITE);
            graphics.fillRect(1, y, width - 2, rowHeights[row]);
            graphics.setFont(header ? headerFont : bodyFont);
            FontMetrics metrics = graphics.getFontMetrics();
            for (int col = 0; col < columns; col++) {
                int x = col * cellWidth + 1;
                graphics.setColor(new Color(203, 213, 225));
                graphics.drawRect(x, y, cellWidth, rowHeights[row]);
                graphics.setColor(new Color(31, 41, 55));
                String value = col < rows.get(row).size() ? rows.get(row).get(col) : "";
                List<String> lines = wrapText(value, metrics, cellWidth - padding * 2);
                int textY = y + padding + metrics.getAscent();
                for (String line : lines) {
                    if (textY > y + rowHeights[row] - padding) break;
                    graphics.drawString(line, x + padding, textY);
                    textY += 22;
                }
            }
            y += rowHeights[row];
        }
        graphics.dispose();
        ImageIO.write(image, "png", target.toFile());
        image.flush();
    }

    private List<String> wrapText(String value, FontMetrics metrics, int maxWidth) {
        String text = cleanText(value);
        if (text.isBlank()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length(); offset++) {
            char c = text.charAt(offset);
            String candidate = line + String.valueOf(c);
            if (line.length() > 0 && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(c);
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines.stream().limit(5).toList();
    }

    private String limitCellText(String value) {
        String text = cleanText(value);
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private int scaledLimit(int max, int extractionPercent) {
        return Math.max(1, (int) Math.ceil(max * (clampPercent(extractionPercent) / 100.0)));
    }

    private int clampPercent(int value) {
        return Math.max(10, Math.min(100, value));
    }

    public List<String> listImagePaths(Path imagesDir) throws IOException {
        if (imagesDir == null || !Files.isDirectory(imagesDir)) return List.of();
        try (Stream<Path> paths = Files.list(imagesDir)) {
            return paths.filter(Files::isRegularFile)
                    .sorted()
                    .map(path -> path.toAbsolutePath().toString())
                    .toList();
        }
    }

    private void clearDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.toList()) {
                if (Files.isRegularFile(path)) Files.deleteIfExists(path);
            }
        }
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String limitText(String text, int maxChars) {
        if (text == null) return "";
        int limit = Math.max(1000, maxChars);
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private String unescapeXml(String value) {
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private record TemplateScan(List<String> colors, List<String> textSamples, int assetsCopied,
                                List<Map<String, Object>> framework) {}
    private record TableCandidate(List<List<String>> rows, int index, boolean excel) {}
}
