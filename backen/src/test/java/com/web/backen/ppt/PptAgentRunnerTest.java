package com.web.backen.ppt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PptAgentRunnerTest {
    @Test
    void htmlUsesCodexJsonPlanRunnerInsteadOfLegacyModelWorker() throws Exception {
        PptCodexRunner codex = mock(PptCodexRunner.class);
        PptGenerationSession session = new PptGenerationSession();
        session.setOutputFormat("html");
        @SuppressWarnings("unchecked")
        BiConsumer<String, Map<String, Object>> events = mock(BiConsumer.class);

        new PptAgentRunner(codex).run(session, Path.of("."), events);

        verify(codex).runHtml(session, events);
        verify(codex, never()).run(session, events);
    }

    @Test
    void pptxStillUsesPptdCodexRunner() throws Exception {
        PptCodexRunner codex = mock(PptCodexRunner.class);
        PptGenerationSession session = new PptGenerationSession();
        session.setOutputFormat("pptx");
        @SuppressWarnings("unchecked")
        BiConsumer<String, Map<String, Object>> events = mock(BiConsumer.class);

        new PptAgentRunner(codex).run(session, Path.of("."), events);

        verify(codex).run(session, events);
        verify(codex, never()).runHtml(session, events);
    }
}
