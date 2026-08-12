package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PptCodexRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsCompletedJsonlTurnWhenCompatibleCliWrapperExitsNonZero() throws Exception {
        String result = invokeRun(List.of("/bin/sh", "-c", "printf '%s\\n' '{\"type\":\"turn.completed\"}'; exit 1"));
        assertEquals("{\"type\":\"turn.completed\"}\n", result);
    }

    @Test
    void stillRejectsNonZeroExitWithoutCompletedJsonlTurn() throws Exception {
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> invokeRun(List.of("/bin/sh", "-c", "printf '%s\\n' '{\"type\":\"turn.failed\"}'; exit 1")));
        assertEquals("CodexProcessException", thrown.getCause().getClass().getSimpleName());
    }

    @Test
    void retainedVisualCountAcceptsOnlyUniqueValidatedLocalImages() throws Exception {
        Path assets = Files.createDirectories(tempDir.resolve("web-images"));
        Files.write(assets.resolve("WEB01.png"), new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0
        });
        Files.writeString(assets.resolve("WEB02.jpg"), "not-an-image");
        Files.writeString(assets.resolve("image-assets.json"), """
                {"images":[
                  {"fileName":"WEB01.png"},
                  {"fileName":"WEB01.png"},
                  {"fileName":"WEB02.jpg"},
                  {"fileName":"../WEB03.png"}
                ]}
                """);

        PptCodexRunner runner = new PptCodexRunner(new PptGenerationConfig(), mock(RuntimeConfigService.class),
                new ObjectMapper(), mock(PptImageGenerationService.class));
        Method method = PptCodexRunner.class.getDeclaredMethod("retainedVisualCount", Path.class);
        method.setAccessible(true);
        assertEquals(1, method.invoke(runner, assets));
    }

    private String invokeRun(List<String> command) throws Exception {
        PptCodexRunner runner = new PptCodexRunner(new PptGenerationConfig(), mock(RuntimeConfigService.class),
                new ObjectMapper(), mock(PptImageGenerationService.class));
        Method run = PptCodexRunner.class.getDeclaredMethod("run", List.class, Path.class, Path.class, String.class,
                Duration.class, BiConsumer.class, boolean.class);
        run.setAccessible(true);
        @SuppressWarnings("unchecked")
        BiConsumer<String, Map<String, Object>> events = (event, data) -> { };
        return (String) run.invoke(runner, command, tempDir, null, "", Duration.ofSeconds(5), events, true);
    }
}
