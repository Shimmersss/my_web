package com.web.backen.ppt;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Dispatches both delivery formats through the locked Codex CLI authoring
 * profile. PPTX produces PPTD; HTML produces a bounded JSON plan. In both
 * cases, only repository-owned exporters/renderers can create deliverables.
 */
@Component
public class PptAgentRunner {
    private final PptCodexRunner codexRunner;

    public PptAgentRunner(PptCodexRunner codexRunner) {
        this.codexRunner = codexRunner;
    }

    public void run(PptGenerationSession session, Path storageRoot,
                    BiConsumer<String, Map<String, Object>> eventConsumer)
            throws IOException, InterruptedException {
        if ("pptx".equalsIgnoreCase(session.getOutputFormat())) {
            codexRunner.run(session, eventConsumer);
        } else if ("html".equalsIgnoreCase(session.getOutputFormat())) {
            codexRunner.runHtml(session, eventConsumer);
        } else {
            throw new IllegalArgumentException("不支持的演示输出格式");
        }
    }

    public void finalizePptdProject(PptGenerationSession session,
                                    BiConsumer<String, Map<String, Object>> eventConsumer)
            throws IOException, InterruptedException {
        codexRunner.finalizeExisting(session, eventConsumer);
    }

    public void cancelActiveTask() { codexRunner.cancelActiveProcesses(); }
}
