package com.web.backen.translate;

import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.BabelDocConfig;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BabelDocServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsToFivePagesPerChunk() {
        assertEquals(5, new BabelDocConfig().getMaxPagesPerChunk());
    }

    @Test
    void splitsLongTranslationAndMergesBothOutputsInPageOrder() throws Exception {
        RecordingBabelDocService service = newService();
        List<Double> progress = new ArrayList<>();
        List<String> stages = new ArrayList<>();

        BabelDocService.TranslationResult result = service.translatePdf(
                tempDir.resolve("input.pdf"), tempDir.resolve("result"), "paper.pdf",
                1, 10, "auto", 2, update -> {
                    progress.add(update.progress());
                    stages.add(update.stage());
                });

        assertEquals(List.of("1-4@2", "5-8@2", "9-10@2"), service.calls);
        assertEquals(10, pageCount(result.translatedPdf()));
        assertEquals(10, pageCount(result.bilingualPdf()));
        assertTrue(stages.contains("chunk-wait"));
        assertTrue(stages.contains("merge"));
        assertTrue(isNonDecreasing(progress));
        assertTrue(Files.notExists(tempDir.resolve("result/.babeldoc-chunks/1-10")));
    }

    @Test
    void reusesCompletedChunksWhenStableModeRetriesAfterResourcePressure() throws Exception {
        RecordingBabelDocService service = newService();
        service.failAcceleratedSecondChunk.set(true);
        Path resultDir = tempDir.resolve("result");

        assertThrows(BabelDocService.ResourcePressureException.class,
                () -> service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                        "paper.pdf", 1, 10, "auto", 4, ignored -> {
                        }));
        assertTrue(Files.isRegularFile(
                resultDir.resolve(".babeldoc-chunks/1-10/1-4/translated.pdf")));

        BabelDocService.TranslationResult result = service.translatePdf(
                tempDir.resolve("input.pdf"), resultDir, "paper.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("1-4@4", "5-8@4", "5-8@2", "9-10@2"), service.calls);
        assertEquals(10, pageCount(result.translatedPdf()));
        assertEquals(10, pageCount(result.bilingualPdf()));
    }

    @Test
    void keepsReferenceSectionActiveWhenFollowingChunksStartAfterHeading() throws Exception {
        RecordingBabelDocService service = newService();
        Path input = tempDir.resolve("references.pdf");
        writeTextPdf(input, 10, 4, "References");

        service.translatePdf(input, tempDir.resolve("result"), "references.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("5-8", "9-10"), service.forcedReferenceRanges);
    }

    @Test
    void stopsForcingReferenceSectionAfterAppendixAcrossChunks() throws Exception {
        RecordingBabelDocService service = newService();
        Path input = tempDir.resolve("references-appendix.pdf");
        writeTextPdf(input, 10, 4, "References");
        addTextToPdf(input, 7, "Supplementary Material");

        service.translatePdf(input, tempDir.resolve("result"), "references-appendix.pdf",
                1, 10, "auto", 2, ignored -> {
                });

        assertEquals(List.of("5-8"), service.forcedReferenceRanges);
    }

    @Test
    void invalidatesCompletedChunksWhenRenderingParametersChange() throws Exception {
        RecordingBabelDocService service = newService();
        service.failAcceleratedSecondChunk.set(true);
        Path resultDir = tempDir.resolve("result");

        assertThrows(BabelDocService.ResourcePressureException.class,
                () -> service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                        "paper.pdf", 1, 10, "auto", 4, ignored -> {
                        }));
        service.translatePdf(tempDir.resolve("input.pdf"), resultDir,
                "paper.pdf", 1, 10, "serif", 2, ignored -> {
                });

        assertEquals(List.of(
                "1-4@4", "5-8@4", "1-4@2", "5-8@2", "9-10@2"), service.calls);
    }

    private RecordingBabelDocService newService() {
        BabelDocConfig config = new BabelDocConfig();
        config.setMaxPagesPerChunk(4);
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.babelKey()).thenReturn("test-key");
        return new RecordingBabelDocService(config, runtimeConfig);
    }

    private int pageCount(Path pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return document.getNumberOfPages();
        }
    }

    private boolean isNonDecreasing(List<Double> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index) < values.get(index - 1)) return false;
        }
        return true;
    }

    private static class RecordingBabelDocService extends BabelDocService {
        private final List<String> calls = new ArrayList<>();
        private final List<String> forcedReferenceRanges = new ArrayList<>();
        private final AtomicBoolean failAcceleratedSecondChunk = new AtomicBoolean();

        private RecordingBabelDocService(BabelDocConfig config,
                                         RuntimeConfigService runtimeConfig) {
            super(config, runtimeConfig);
        }

        @Override
        protected TranslationResult translateRange(
                Path inputPdf, Path resultDir, String fileName, int startPage,
                int endPage, String fontFamily, int qps,
                boolean forceReferenceSection,
                Consumer<ProgressUpdate> progressConsumer) {
            calls.add(startPage + "-" + endPage + "@" + qps);
            if (forceReferenceSection) {
                forcedReferenceRanges.add(startPage + "-" + endPage);
            }
            if (startPage == 5 && qps == 4
                    && failAcceleratedSecondChunk.compareAndSet(true, false)) {
                throw new ResourcePressureException("test pressure");
            }
            try {
                Files.createDirectories(resultDir);
                int pages = endPage - startPage + 1;
                writePdf(resultDir.resolve("translated.pdf"), pages);
                writePdf(resultDir.resolve("bilingual.pdf"), pages);
                if (progressConsumer != null) {
                    progressConsumer.accept(
                            new ProgressUpdate(100, "Save PDF", pages, pages));
                }
                return new TranslationResult(
                        resultDir.resolve("translated.pdf"),
                        resultDir.resolve("bilingual.pdf"));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        protected void waitForResourceRecovery() {
            // The production implementation samples cgroup/system memory twice.
        }

        private void writePdf(Path path, int pages) throws IOException {
            try (PDDocument document = new PDDocument()) {
                for (int index = 0; index < pages; index++) {
                    document.addPage(new PDPage());
                }
                document.save(path.toFile());
            }
        }
    }

    private void writeTextPdf(Path path, int pages, int textPage, String text)
            throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= pages; pageNumber++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (pageNumber == textPage) {
                    try (PDPageContentStream content =
                                 new PDPageContentStream(document, page)) {
                        content.beginText();
                        content.setFont(new PDType1Font(
                                Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(72, 720);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            document.save(path.toFile());
        }
    }

    private void addTextToPdf(Path path, int pageNumber, String text) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".updated");
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            try (PDPageContentStream content = new PDPageContentStream(
                    document, document.getPage(pageNumber - 1),
                    PDPageContentStream.AppendMode.APPEND, true)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(temporary.toFile());
        }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
