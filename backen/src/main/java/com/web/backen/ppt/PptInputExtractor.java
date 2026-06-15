package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.web.backen.config.PptGenerationConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private static final Pattern PPT_PIC = Pattern.compile("<p:pic[\\s\\S]*?</p:pic>");
    private static final Pattern PPT_PH = Pattern.compile("<p:ph\\b([^>]*)/?>");
    private static final Pattern PPT_BLIP = Pattern.compile("r:embed=\"(rId\\d+)\"");
    private static final Pattern PPT_OFF = Pattern.compile("<a:off\\b[^>]*x=\"(\\d+)\"[^>]*y=\"(\\d+)\"[^>]*/?>");
    private static final Pattern PPT_EXT = Pattern.compile("<a:ext\\b[^>]*cx=\"(\\d+)\"[^>]*cy=\"(\\d+)\"[^>]*/?>");
    private static final Pattern PPT_CNV_PR = Pattern.compile("<p:cNvPr\\b([^>]*)/?>");
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
        return extractPaperText(paper, fileName, imagesDir, extractionPercent, config.getMaxExtractedImages());
    }

    public String extractPaperText(Path paper, String fileName, Path imagesDir,
                                   int extractionPercent, int imageBudget) throws IOException {
        return extractPaperText(paper, fileName, imagesDir, extractionPercent, imageBudget, 0);
    }

    public String extractPaperText(Path paper, String fileName, Path imagesDir,
                                   int extractionPercent, int imageBudget, int minCandidateImages) throws IOException {
        if (paper == null || fileName == null || !Files.isRegularFile(paper)) return "";
        String lower = fileName.toLowerCase(Locale.ROOT);
        int effectiveBudget = effectiveImageLimit(imageBudget, extractionPercent, minCandidateImages);
        String text;
        if (lower.endsWith(".pdf")) {
            text = extractViaDocumentParser(paper, imagesDir, effectiveBudget);
            try (var document = Loader.loadPDF(paper.toFile())) {
                extractPdfPageImages(document, imagesDir, effectiveBudget);
                if (text == null || text.isBlank()) {
                    text = new PDFTextStripper().getText(document);
                }
            }
        } else if (lower.endsWith(".docx")) {
            text = extractViaDocumentParser(paper, imagesDir, effectiveBudget);
            if (text == null || text.isBlank()) {
                text = extractDocxTextAndImages(paper, imagesDir, effectiveBudget);
            }
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
        style.put("templateAnalysis", List.of());
        style.put("templateFit", "weak");
        style.put("templateRoute", "framework");

        if (template != null && Files.isRegularFile(template)) {
            Path assetsDir = Files.createDirectories(taskDir.resolve("template-assets"));
            clearDirectory(assetsDir);
            TemplateScan scan = scanTemplate(template, assetsDir, extractionPercent);
            if (!scan.colors().isEmpty()) style.put("palette", scan.colors());
            style.put("textSamples", scan.textSamples());
            style.put("frameworkMode", true);
            style.put("templateFramework", scan.framework());
            style.put("templateAnalysis", scan.analysis());
            style.put("templateFit", scan.fit());
            style.put("templateFitReason", scan.fitReason());
            style.put("templateRoute", "weak".equals(scan.fit()) ? "framework" : "template-fill");
            if (scan.assetsCopied() > 0) style.put("assetsDir", assetsDir.toAbsolutePath().toString());
        }

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskDir.resolve("style.json").toFile(), style);
    }

    private String extractDocxTextAndImages(Path docx, Path imagesDir, int imageLimit) throws IOException {
        Files.createDirectories(imagesDir);
        clearDirectory(imagesDir);
        StringBuilder text = new StringBuilder();
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

    private String extractViaDocumentParser(Path paper, Path imagesDir, int imageLimit) throws IOException {
        Path taskDir = imagesDir == null ? null : imagesDir.getParent();
        if (taskDir == null) return "";
        Path parserOutputDir = Files.createDirectories(taskDir.resolve("paper-parser"));
        clearDirectory(parserOutputDir);
        Path parserResult = parserOutputDir.resolve("parse-result.json");
        try {
            runDocumentParser(paper, parserOutputDir);
            if (!Files.isRegularFile(parserResult)) return "";
            JsonNode root = objectMapper.readTree(parserResult.toFile());
            String markdown = root.path("markdown").asText("");
            copyParserImages(parserOutputDir.resolve("images"), imagesDir, imageLimit);
            return markdown;
        } catch (Exception e) {
            return "";
        }
    }

    private void copyParserImages(Path sourceDir, Path targetDir, int limit) throws IOException {
        if (sourceDir == null || targetDir == null || !Files.isDirectory(sourceDir)) return;
        Files.createDirectories(targetDir);
        try (Stream<Path> files = Files.list(sourceDir)) {
            List<Path> selected = files
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedImage)
                    .sorted(Comparator
                            .comparingInt(this::parserImagePriority)
                            .thenComparing(path -> path.getFileName().toString()))
                    .limit(Math.max(0, limit))
                    .toList();
            for (Path source : selected) {
                if (!Files.isRegularFile(source)) continue;
                Files.copy(source, targetDir.resolve(source.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void runDocumentParser(Path paper, Path outputDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(splitCommand(config.getPaperParserCommand()));
        command.add(Path.of(config.getPaperParserScript()).toAbsolutePath().normalize().toString());
        command.add("--input");
        command.add(paper.toAbsolutePath().toString());
        command.add("--output");
        command.add(outputDir.toAbsolutePath().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readProcessOutput(process));
        if (!process.waitFor(documentParserTimeoutSeconds(), TimeUnit.SECONDS)) {
            terminateProcessTree(process);
            outputFuture.cancel(true);
            throw new IllegalStateException("文档解析超时");
        }
        String output = outputFuture.join();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("文档解析失败: " + tail(output, 1200));
        }
    }

    private long documentParserTimeoutSeconds() {
        return Math.min(45, Math.max(1, config.getTimeoutSeconds()));
    }

    private String readProcessOutput(Process process) {
        try (InputStream input = process.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return e.getMessage() == null ? "" : e.getMessage();
        }
    }

    private void terminateProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private String tail(String text, int maxLength) {
        if (text == null || text.isBlank()) return "(没有命令输出)";
        return text.length() <= maxLength ? text : text.substring(text.length() - maxLength);
    }

    private List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalStateException("PPT 论文解析命令未配置");
        }
        return Stream.of(command.trim().split("\\s+")).toList();
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

    private void extractPdfPageImages(PDDocument document, Path imagesDir, int imageBudget) throws IOException {
        Files.createDirectories(imagesDir);
        clearDirectory(imagesDir);
        PDFRenderer renderer = new PDFRenderer(document);
        int count = Math.min(document.getNumberOfPages(), Math.max(1, imageBudget));
        for (int pageIndex : prioritizedPdfPageIndexes(document, count)) {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, 96, ImageType.RGB);
            Path target = imagesDir.resolve("paper-page-" + (pageIndex + 1) + ".png");
            ImageIO.write(image, "png", target.toFile());
            image.flush();
        }
    }

    private List<Integer> prioritizedPdfPageIndexes(PDDocument document, int count) throws IOException {
        int totalPages = document.getNumberOfPages();
        if (totalPages <= 0 || count <= 0) return List.of();
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        List<PageEvidence> evidencePages = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            String text = cleanText(stripper.getText(document));
            int score = pageEvidenceScore(text);
            if (score > 0) {
                evidencePages.add(new PageEvidence(pageIndex, score));
            }
        }
        evidencePages.stream()
                .sorted(Comparator.comparingInt(PageEvidence::score).reversed()
                        .thenComparingInt(PageEvidence::pageIndex))
                .limit(count)
                .forEach(item -> indexes.add(item.pageIndex()));
        for (int index : sampledPageIndexes(totalPages, count)) {
            if (indexes.size() >= count) break;
            indexes.add(index);
        }
        int fallback = 0;
        while (indexes.size() < count && fallback < totalPages) {
            indexes.add(fallback++);
        }
        return new ArrayList<>(indexes);
    }

    private int pageEvidenceScore(String text) {
        if (text == null || text.isBlank()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        score += countMatches(lower, "\\bfig(?:ure)?\\.?\\s*\\d+") * 8;
        score += countMatches(text, "图\\s*\\d+") * 8;
        score += countMatches(lower, "\\btable\\s*\\d+") * 5;
        score += countMatches(text, "表\\s*\\d+") * 5;
        if (containsAny(lower, "caption", "workflow", "pipeline", "architecture", "framework", "network",
                "experiment", "result", "comparison", "visualization", "流程", "架构", "结构", "网络", "实验", "结果", "对比", "可视化")) {
            score += 3;
        }
        if (lower.length() > 1800 && score <= 3) {
            score -= 2;
        }
        return Math.max(0, score);
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
        List<Map<String, Object>> analysis = new ArrayList<>();
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
                    if (name.startsWith("ppt/slides/slide")) {
                        Matcher textMatcher = PPT_TEXT.matcher(xml);
                        List<String> slideSamples = new ArrayList<>();
                        while (textMatcher.find()) {
                            String sample = cleanText(unescapeXml(textMatcher.group(1)));
                            if (sample.length() >= 2 && sample.length() <= 80) {
                                if (samples.size() < 12) samples.add(sample);
                                slideSamples.add(sample);
                            }
                        }
                        Matcher slideMatcher = PPT_SLIDE_NAME.matcher(name);
                        if (slideMatcher.matches()) {
                            List<Map<String, Object>> pictureFrames = extractPictureFrames(xml);
                            List<Map<String, Object>> textFrames = extractTextFrames(xml);
                            String role = inferTemplateRole(xml, slideSamples);
                            int slideIndex = Integer.parseInt(slideMatcher.group(1));
                            Map<String, Object> analysisItem = Map.of(
                                    "index", slideIndex,
                                    "role", role,
                                    "textBlocks", countMatches(xml, "<p:sp\\b"),
                                    "textSlots", textFrames,
                                    "imageSlots", pictureFrames,
                                    "images", countMatches(xml, "<a:blip\\b"),
                                    "tables", countMatches(xml, "<a:tbl\\b"),
                                    "samples", slideSamples.stream().limit(4).toList()
                            );
                            analysis.add(analysisItem);
                            if (framework.size() < frameworkLimit) {
                                framework.add(Map.of(
                                        "index", slideIndex,
                                        "role", role,
                                        "textBlocks", countMatches(xml, "<p:sp\\b"),
                                        "images", countMatches(xml, "<a:blip\\b"),
                                        "tables", countMatches(xml, "<a:tbl\\b"),
                                        "samples", slideSamples.stream().limit(4).toList()
                                ));
                            }
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
        analysis.sort(Comparator.comparingInt(item -> (Integer) item.get("index")));
        String fit = assessTemplateFit(framework, analysis);
        String fitReason = assessTemplateFitReason(framework, analysis, fit);
        return new TemplateScan(new ArrayList<>(colors), samples, assets, framework, analysis, fit, fitReason);
    }

    private String assessTemplateFit(List<Map<String, Object>> framework, List<Map<String, Object>> analysis) {
        if (analysis == null || analysis.isEmpty()) return "weak";
        long usefulSlides = analysis.stream()
                .filter(item -> {
                    int textBlocks = ((Number) item.getOrDefault("textBlocks", 0)).intValue();
                    int images = ((Number) item.getOrDefault("images", 0)).intValue();
                    int tables = ((Number) item.getOrDefault("tables", 0)).intValue();
                    return textBlocks >= 3 || images > 0 || tables > 0;
                })
                .count();
        long roles = analysis.stream()
                .map(item -> String.valueOf(item.getOrDefault("role", "")))
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        long imageSlots = analysis.stream()
                .mapToLong(item -> countUsableImageSlots(item.get("imageSlots")))
                .sum();
        long frameworkSlides = framework == null ? 0 : framework.size();
        if (analysis.size() >= 6 && usefulSlides >= 5 && roles >= 4 && imageSlots >= 3 && frameworkSlides >= 4) {
            return "strong";
        }
        if (analysis.size() >= 4 && usefulSlides >= 3 && imageSlots >= 1 && frameworkSlides >= 2) {
            return "medium";
        }
        return "weak";
    }

    private String assessTemplateFitReason(List<Map<String, Object>> framework, List<Map<String, Object>> analysis, String fit) {
        long usefulSlides = analysis == null ? 0 : analysis.stream()
                .filter(item -> {
                    int textBlocks = ((Number) item.getOrDefault("textBlocks", 0)).intValue();
                    int images = ((Number) item.getOrDefault("images", 0)).intValue();
                    int tables = ((Number) item.getOrDefault("tables", 0)).intValue();
                    return textBlocks >= 3 || images > 0 || tables > 0;
                })
                .count();
        long roles = analysis == null ? 0 : analysis.stream()
                .map(item -> String.valueOf(item.getOrDefault("role", "")))
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        long imageSlots = analysis == null ? 0 : analysis.stream()
                .mapToLong(item -> countUsableImageSlots(item.get("imageSlots")))
                .sum();
        long frameworkSlides = framework == null ? 0 : framework.size();
        return "fit=" + fit
                + ", slides=" + (analysis == null ? 0 : analysis.size())
                + ", usefulSlides=" + usefulSlides
                + ", roles=" + roles
                + ", imageSlots=" + imageSlots
                + ", frameworkSlides=" + frameworkSlides;
    }

    private long countUsableImageSlots(Object slotsObject) {
        if (!(slotsObject instanceof List<?> slots)) return 0L;
        return slots.stream()
                .filter(slot -> slot instanceof Map<?, ?> map
                        && !"decorative".equalsIgnoreCase(String.valueOf(map.get("roleHint"))))
                .count();
    }

    private List<Map<String, Object>> extractTextFrames(String xml) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Matcher matcher = Pattern.compile("<p:sp[\\s\\S]*?</p:sp>").matcher(xml);
        while (matcher.find() && frames.size() < 16) {
            String shape = matcher.group();
            String placeholder = placeholderType(shape);
            int textLength = extractXmlText(shape).length();
            frames.add(Map.of(
                    "kind", "text",
                    "placeholder", placeholder,
                    "length", textLength
            ));
        }
        return frames;
    }

    private List<Map<String, Object>> extractPictureFrames(String xml) {
        List<Map<String, Object>> frames = new ArrayList<>();
        Matcher matcher = PPT_PIC.matcher(xml);
        while (matcher.find() && frames.size() < 12) {
            String pic = matcher.group();
            Matcher embedMatcher = PPT_BLIP.matcher(pic);
            Matcher offMatcher = PPT_OFF.matcher(pic);
            Matcher extMatcher = PPT_EXT.matcher(pic);
            Matcher cNvPrMatcher = PPT_CNV_PR.matcher(pic);
            String relId = embedMatcher.find() ? embedMatcher.group(1) : "";
            long x = 0L;
            long y = 0L;
            if (offMatcher.find()) {
                x = parseLong(offMatcher.group(1));
                y = parseLong(offMatcher.group(2));
            }
            long cx = 0L;
            long cy = 0L;
            if (extMatcher.find()) {
                cx = parseLong(extMatcher.group(1));
                cy = parseLong(extMatcher.group(2));
            }
            String name = "";
            String descr = "";
            if (cNvPrMatcher.find()) {
                String attrs = cNvPrMatcher.group(1);
                name = attribute(attrs, "name");
                descr = attribute(attrs, "descr");
            }
            frames.add(Map.of(
                    "kind", "image",
                    "relId", relId,
                    "name", name,
                    "descr", descr,
                    "x", x,
                    "y", y,
                    "w", cx,
                    "h", cy,
                    "area", String.valueOf(cx * cy),
                    "roleHint", pictureRoleHint(x, y, cx, cy)
            ));
        }
        return frames;
    }

    private String pictureRoleHint(long x, long y, long w, long h) {
        if (w <= 0 || h <= 0) return "decorative";
        long area = w * h;
        long slideArea = 12192000L * 6858000L;
        double ratio = area / (double) Math.max(1L, slideArea);
        if (ratio >= 0.28) return "full-image";
        if (ratio >= 0.12) return x < 2500000L ? "image-left" : "image-right";
        return "decorative";
    }

    private String placeholderType(String shapeXml) {
        Matcher matcher = PPT_PH.matcher(shapeXml);
        if (!matcher.find()) return "";
        String attrs = matcher.group(1);
        String type = attribute(attrs, "type");
        if (!type.isBlank()) return type;
        String idx = attribute(attrs, "idx");
        return idx.isBlank() ? "body" : "body-" + idx;
    }

    private String attribute(String attrs, String key) {
        Matcher matcher = Pattern.compile("\\b" + key + "=\"([^\"]*)\"").matcher(attrs == null ? "" : attrs);
        return matcher.find() ? matcher.group(1) : "";
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
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

    private int effectiveImageLimit(int imageBudget, int extractionPercent, int minCandidateImages) {
        int configured = Math.max(1, imageBudget);
        int scaled = scaledLimit(configured, extractionPercent);
        int floor = minCandidateImages <= 0 ? 0 : Math.min(configured, minCandidateImages);
        return Math.min(configured, Math.max(scaled, floor));
    }

    private int clampPercent(int value) {
        return Math.max(10, Math.min(100, value));
    }

    public List<String> listImagePaths(Path imagesDir) throws IOException {
        if (imagesDir == null || !Files.isDirectory(imagesDir)) return List.of();
        try (Stream<Path> paths = Files.list(imagesDir)) {
            return paths.filter(Files::isRegularFile)
                    .sorted(Comparator
                            .comparingInt(this::imageEvidencePriority)
                            .thenComparing(path -> path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().toString())
                    .toList();
        }
    }

    private int imageEvidencePriority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int score = 50;
        if (containsAny(name, "figure", "fig", "caption", "图", "chart", "plot", "result", "compare", "architecture",
                "framework", "workflow", "network", "model", "table", "paper-table", "paper-excel", "实验", "结果", "对比", "架构", "流程")) {
            score -= 30;
        }
        if (containsAny(name, "logo", "icon", "blank", "cover", "watermark", "公式", "formula")) {
            score += 20;
        }
        if (name.startsWith("paper-table-") || name.startsWith("paper-excel-")) score -= 25;
        if (name.startsWith("paper-page-")) score -= 5;
        return score;
    }

    private int parserImagePriority(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int score = imageEvidencePriority(path);
        if (name.contains("word") || name.contains("media") || name.matches(".*image\\d+\\.(png|jpe?g)$")) score -= 20;
        if (name.contains("table") || name.contains("excel")) score += 10;
        return score;
    }

    private boolean isSupportedImage(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
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
                                List<Map<String, Object>> framework,
                                List<Map<String, Object>> analysis,
                                String fit,
                                String fitReason) {}
    private record TableCandidate(List<List<String>> rows, int index, boolean excel) {}
    private record PageEvidence(int pageIndex, int score) {}
}
