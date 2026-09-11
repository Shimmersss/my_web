package com.web.backen.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.*;
import com.web.backen.imagegen.ImageGenerationService;
import com.web.backen.matchmaking.MatchmakingService;
import com.web.backen.ppt.*;
import com.web.backen.translate.TranslationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdmissionBoundaryTest {
    @TempDir Path temp;
    @Test void allFourDomainsAndTranslationRetryRejectMaintenanceBeforeChargingOrWriting() throws Exception {
        var coordinator = new TaskCoordinator(temp.resolve("lock")); coordinator.pause();
        var mapper = new ObjectMapper(); var snapshots = new AtomicTaskStore(mapper); var paths = new RuntimePaths(temp.toString());
        var quota = mock(QuotaService.class); var user = new AuthUser(7, "test", "USER", 20, true);
        var translate = new TranslationService(null, null, new TranslationConfig(), mapper, quota);
        var ppt = new PptGenerationService(new PptGenerationConfig(), mock(PptInputExtractor.class), mapper, quota);
        var image = new ImageGenerationService(new ImageGenerationConfig(), null, mapper, quota, null);
        var match = new MatchmakingService(null, mapper, null, quota, null, null, null, null, temp.toString());
        for (Object service : new Object[]{translate, ppt, image, match})
            ReflectionTestUtils.invokeMethod(service, "infrastructure", coordinator, snapshots, paths);
        try {
            assertEquals(503, assertThrows(AuthException.class, () -> translate.createSessionPreview("a.pdf", new ByteArrayInputStream(new byte[]{1}))).getStatus());
            assertThrows(AuthException.class, () -> translate.startTranslation("missing", 1, 1, "auto", 2, user));
            assertThrows(AuthException.class, () -> ppt.createTask("test", "", 100, null, null));
            assertThrows(AuthException.class, () -> image.create("test", "GENERATE", "1024x1024", "medium", null, null, user));
            assertThrows(AuthException.class, () -> match.createTask(user, Map.of()));
            assertEquals(4, ((Map<?, ?>) coordinator.snapshot().get("modules")).size());
            verifyNoInteractions(quota);
        } finally {
            translate.shutdown(); ppt.shutdown();
            ReflectionTestUtils.invokeMethod(image, "shutdown"); ReflectionTestUtils.invokeMethod(match, "shutdown");
        }
    }
}
