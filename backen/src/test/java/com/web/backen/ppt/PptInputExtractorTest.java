package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.PptGenerationConfig;
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
}
