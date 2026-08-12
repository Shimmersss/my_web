package com.web.backen.ppt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.RuntimeConfigService;
import com.web.backen.config.PptGenerationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
