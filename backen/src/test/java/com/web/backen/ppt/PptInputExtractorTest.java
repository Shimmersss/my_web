package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.web.backen.config.PptGenerationConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PptInputExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsDocxPicturesAndTablesWithinSharedImageBudget() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setMaxExtractedImages(8);
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());
        Path docx = tempDir.resolve("paper.docx");
        createDocxWithTablesAndImages(docx, 10, 10);

        Path imagesDir = tempDir.resolve("images");
        extractor.extractPaperText(docx, "paper.docx", imagesDir, 100);
        List<String> images = extractor.listImagePaths(imagesDir);

        assertEquals(8, images.size());
        assertTrue(images.stream().anyMatch(path -> path.contains("paper-image-")));
        assertTrue(images.stream().anyMatch(path -> path.contains("paper-table-")));
    }

    @Test
    void scansAllTemplateSlidesAndInfersFitBeyondTheFirstTwoPages() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());
        Path template = tempDir.resolve("template.pptx");
        createTemplatePptx(template);
        Path taskDir = tempDir.resolve("task");
        Files.createDirectories(taskDir);

        extractor.extractTemplateStyle(template, taskDir, 100);

        JsonNode style = new ObjectMapper().readTree(taskDir.resolve("style.json").toFile());
        assertEquals(6, style.path("templateAnalysis").size());
        assertNotEquals("weak", style.path("templateFit").asText(""));
        assertEquals("template-fill", style.path("templateRoute").asText(""));
    }

    @Test
    void pdfImageSamplingPrefersCaptionAndFigurePages() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setMaxExtractedImages(2);
        config.setPaperParserCommand("false");
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());
        Path pdf = tempDir.resolve("paper.pdf");
        createPdf(pdf, List.of(
                "Introduction and motivation.",
                "Figure 1. System architecture and workflow.",
                "Related work and implementation notes.",
                "Fig. 2 Experiment result comparison and visualization.",
                "Conclusion and outlook."
        ));

        Path imagesDir = tempDir.resolve("pdf-images");
        extractor.extractPaperText(pdf, "paper.pdf", imagesDir, 100);
        List<String> images = extractor.listImagePaths(imagesDir);

        assertEquals(2, images.size());
        assertTrue(images.stream().anyMatch(path -> path.endsWith("paper-page-2.png")));
        assertTrue(images.stream().anyMatch(path -> path.endsWith("paper-page-4.png")));
    }

    @Test
    void defenseDynamicMinimumKeepsAtLeastFifteenCandidatesAtDefaultPercent() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setMaxExtractedImages(24);
        config.setPaperParserCommand("false");
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());

        for (int pages : List.of(20, 25, 30)) {
            Path pdf = tempDir.resolve("defense-" + pages + ".pdf");
            createPdf(pdf, numberedPages(pages));
            Path imagesDir = tempDir.resolve("defense-images-" + pages);

            extractor.extractPaperText(pdf, "paper.pdf", imagesDir, 50, 24, 15);

            assertTrue(extractor.listImagePaths(imagesDir).size() >= 15,
                    "expected at least 15 candidates for " + pages + " pages");
        }
    }

    @Test
    void parserImagesAreCopiedWithinEffectiveBudget() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setPaperParserCommand("python3");
        config.setPaperParserScript(createParserStub().toString());
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());
        Path docx = tempDir.resolve("parser.docx");
        Files.writeString(docx, "parser-input", StandardCharsets.UTF_8);
        Path imagesDir = tempDir.resolve("parser-images");

        extractor.extractPaperText(docx, "paper.docx", imagesDir, 50, 10, 0);

        List<String> images = extractor.listImagePaths(imagesDir);
        assertEquals(5, images.size());
        assertTrue(images.stream().allMatch(path -> path.contains("word-media-image")));
    }

    @Test
    void parserTimeoutFallsBackToBuiltInDocxExtraction() throws Exception {
        PptGenerationConfig config = new PptGenerationConfig();
        config.setTimeoutSeconds(1);
        config.setPaperParserCommand("python3");
        config.setPaperParserScript(createSleepingParserStub().toString());
        PptInputExtractor extractor = new PptInputExtractor(config, new ObjectMapper());
        Path docx = tempDir.resolve("fallback.docx");
        createDocxWithText(docx, "内置解析兜底文本");

        long started = System.nanoTime();
        String text = extractor.extractPaperText(docx, "paper.docx", tempDir.resolve("fallback-images"), 100);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(text.contains("内置解析兜底文本"));
        assertTrue(elapsedMillis < 5000, "parser timeout fallback should not hang");
    }

    private void createDocxWithTablesAndImages(Path target, int tableCount, int imageCount) throws Exception {
        StringBuilder document = new StringBuilder("<w:document xmlns:w=\"w\"><w:body>");
        for (int index = 0; index < tableCount; index++) {
            document.append("<w:tbl><w:tr><w:tc><w:p><w:r><w:t>指标")
                    .append(index)
                    .append("</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>")
                    .append(index * 10)
                    .append("</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
        }
        document.append("</w:body></w:document>");

        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        Path png = tempDir.resolve("source.png");
        ImageIO.write(image, "png", png.toFile());
        byte[] imageBytes = Files.readAllBytes(png);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(document.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int index = 0; index < imageCount; index++) {
                zip.putNextEntry(new ZipEntry("word/media/image" + index + ".png"));
                zip.write(imageBytes);
                zip.closeEntry();
            }
        }
    }

    private void createPdf(Path target, List<String> pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(target.toFile());
        }
    }

    private List<String> numberedPages(int count) {
        List<String> pages = new java.util.ArrayList<>();
        for (int index = 1; index <= count; index++) {
            pages.add("Figure " + index + ". Experiment result comparison and visualization.");
        }
        return pages;
    }

    private Path createParserStub() throws Exception {
        Path script = tempDir.resolve("parser_stub.py");
        Files.writeString(script, """
                import json, pathlib, sys

                out = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
                images = out / 'images'
                images.mkdir(parents=True, exist_ok=True)
                png = bytes.fromhex('89504E470D0A1A0A0000000D49484452000000010000000108060000001F15C4890000000D49444154789C636000000200015E027FEA0000000049454E44AE426082')
                for i in range(12):
                    (images / f'word-media-image{i+1:02d}.png').write_bytes(png)
                (out / 'parse-result.json').write_text(json.dumps({'markdown': 'parser markdown'}), encoding='utf-8')
                """, StandardCharsets.UTF_8);
        return script;
    }

    private Path createSleepingParserStub() throws Exception {
        Path script = tempDir.resolve("sleeping_parser_stub.py");
        Files.writeString(script, """
                import time
                time.sleep(30)
                """, StandardCharsets.UTF_8);
        return script;
    }

    private void createDocxWithText(Path target, String text) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"w\"><w:body><w:p><w:r><w:t>"
                    + text
                    + "</w:t></w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void createTemplatePptx(Path target) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (int index = 1; index <= 6; index++) {
                zip.putNextEntry(new ZipEntry("ppt/slides/slide" + index + ".xml"));
                zip.write(templateSlideXml(index).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private String templateSlideXml(int index) {
        return switch (index) {
            case 1 -> slideXml("CONTENTS", 1, true, false, true);
            case 2 -> slideXml("1", 0, false, false, false);
            case 3 -> slideXml("方法", 1, false, true, true);
            case 4 -> slideXml("图示", 1, true, false, true);
            case 5 -> slideXml("Thank you", 1, false, false, true);
            default -> slideXml("结果", 1, true, false, true);
        };
    }

    private String slideXml(String text, int imageCount, boolean includeImage, boolean includeTable, boolean imageLarge) {
        StringBuilder builder = new StringBuilder();
        builder.append("<p:sld xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\">");
        builder.append("<p:cSld><p:spTree>");
        builder.append("<p:sp><p:nvSpPr><p:cNvPr id=\"1\" name=\"title\"/></p:nvSpPr><p:txBody><a:p><a:r><a:t>")
                .append(text)
                .append("</a:t></a:r></a:p></p:txBody></p:sp>");
        builder.append("<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"body\"/></p:nvSpPr><p:txBody><a:p><a:r><a:t>正文</a:t></a:r></a:p></p:txBody></p:sp>");
        builder.append("<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"body2\"/></p:nvSpPr><p:txBody><a:p><a:r><a:t>说明</a:t></a:r></a:p></p:txBody></p:sp>");
        if (includeTable) {
            builder.append("<p:graphicFrame><a:tbl><a:tr><a:tc><a:txBody><a:p><a:r><a:t>1</a:t></a:r></a:p></a:txBody></a:tc></a:tr></a:tbl></p:graphicFrame>");
        }
        if (includeImage) {
            for (int i = 0; i < imageCount; i++) {
                long cx = imageLarge ? 6400000L : 3200000L;
                long cy = imageLarge ? 2200000L : 1800000L;
            builder.append("<p:pic><p:nvPicPr><p:cNvPr id=\"")
                    .append(10 + i)
                    .append("\" name=\"pic")
                    .append(i + 1)
                    .append("\"/></p:nvPicPr><p:blipFill><a:blip r:embed=\"rId")
                    .append(20 + i)
                    .append("\"/></p:blipFill><p:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
                    .append(cx)
                    .append("\" cy=\"")
                    .append(cy)
                    .append("\"/></a:xfrm></p:spPr></p:pic>");
            }
        }
        builder.append("</p:spTree></p:cSld></p:sld>");
        return builder.toString();
    }
}
