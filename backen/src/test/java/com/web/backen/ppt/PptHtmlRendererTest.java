package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptHtmlRendererTest {

    @TempDir
    Path tempDir;

    @Test
    void untrustedSlideTypeAndPaletteCannotBreakHtmlPresentation() throws Exception {
        Map<String, Object> slide = new LinkedHashMap<>();
        slide.put("type", "content\"><script>alert(1)</script>");
        slide.put("title", "<img src=x onerror=alert(2)>");
        slide.put("imageFile", "../outside.png");
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("title", "安全性检查");
        preview.put("palette", List.of("112233; color:red", "445566", "778899", "AABBCC", "DDEEFF"));
        preview.put("slides", List.of(slide));

        Path output = tempDir.resolve("output.html");
        new PptHtmlRenderer(new ObjectMapper()).render(output, preview, tempDir.resolve("images"));
        String html = Files.readString(output, StandardCharsets.UTF_8);

        assertTrue(html.contains("\"type\":\"content\""), "未知类型应降级为安全页面类型");
        assertFalse(html.contains("content\"><script>alert"), "页面类型不能注入 HTML class");
        assertFalse(html.contains("112233; color:red"), "颜色值不能注入 CSS");
    }

    @Test
    void chromeCanRasterizeStandaloneHtmlPreviewWhenAvailable() throws Exception {
        String chrome = findChrome();
        Assumptions.assumeTrue(chrome != null, "本机未安装 Chrome，跳过浏览器视觉 smoke");
        Map<String, Object> slide = new LinkedHashMap<>();
        slide.put("type", "cover");
        slide.put("section", "SMOKE");
        slide.put("title", "浏览器预览 smoke");
        slide.put("headline", "HTML 预览应能在无后端环境中直接绘制");
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("title", "HTML 视觉回归");
        preview.put("palette", List.of("0969DA", "1F2328", "54AEFF", "F6F8FA", "1F2328"));
        preview.put("slides", List.of(slide, slide, slide, slide, slide));
        Path output = tempDir.resolve("browser-preview.html");
        Path screenshot = tempDir.resolve("browser-preview.png");
        new PptHtmlRenderer(new ObjectMapper()).render(output, preview, tempDir.resolve("images"));
        Process process = new ProcessBuilder(chrome, "--headless=new", "--no-sandbox", "--disable-gpu",
                "--hide-scrollbars", "--window-size=1280,720", "--screenshot=" + screenshot,
                output.toUri().toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Chrome HTML 预览渲染超时");
        assertTrue(process.exitValue() == 0, "Chrome HTML 预览渲染失败: " + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(screenshot) && Files.size(screenshot) > 1024, "HTML 预览截图为空");
    }

    private String findChrome() {
        String[] candidates = {
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"
        };
        for (String candidate : candidates) if (Files.isExecutable(Path.of(candidate))) return candidate;
        return null;
    }
}
