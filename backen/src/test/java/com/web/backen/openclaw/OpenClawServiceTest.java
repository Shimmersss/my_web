package com.web.backen.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.config.OpenClawConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenClawServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void cachesHistoryAndInvalidatesItAfterSend() throws Exception {
        Path countFile = tempDir.resolve("calls.txt");
        Path cli = tempDir.resolve("openclaw-test.sh");
        Files.writeString(cli, """
                #!/usr/bin/env bash
                echo "$3" >> "%s"
                if [[ "$3" == "chat.history" ]]; then
                  echo '{"messages":[]}'
                else
                  echo '{}'
                fi
                """.formatted(countFile));
        cli.toFile().setExecutable(true);

        OpenClawConfig config = new OpenClawConfig();
        config.setCommand(cli.toString());
        config.setHistoryCacheMillis(60000);
        config.setMaxConcurrentCalls(2);
        OpenClawService service = new OpenClawService(config, new ObjectMapper());

        service.history("main");
        service.history("main");
        assertEquals(1, Files.readAllLines(countFile).size());

        service.send("main", "hello", List.of());
        service.history("main");
        assertEquals(3, Files.readAllLines(countFile).size());
    }
}
