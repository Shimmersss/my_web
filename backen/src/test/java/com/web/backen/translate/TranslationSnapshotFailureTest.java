package com.web.backen.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.TranslationConfig;
import com.web.backen.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TranslationSnapshotFailureTest {
    @TempDir Path temp;
    @Test void chargeThenDiskFailureRetainsEvidenceAndRetriesCompensationBeforeCleanup() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); AtomicBoolean diskFailure = new AtomicBoolean();
        AtomicTaskStore store = new AtomicTaskStore(mapper) {
            @Override protected void replace(Path from, Path to) throws IOException {
                if (diskFailure.get()) throw new IOException("injected disk failure");
                super.replace(from, to);
            }
        };
        QuotaService quota = mock(QuotaService.class);
        when(quota.translationCreditPerPage()).thenReturn(1);
        when(quota.spend(anyLong(), anyInt(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            diskFailure.set(true); return 42L;
        });
        TranslationConfig config = new TranslationConfig(); config.setStorageDir(temp.resolve("tasks").toString());
        PdfParseService parser = mock(PdfParseService.class); when(parser.getTotalPages(any(Path.class))).thenReturn(1);
        BabelDocService babel = mock(BabelDocService.class);
        TranslationService service = new TranslationService(parser, babel, config, mapper, quota);
        service.infrastructure(new TaskCoordinator(temp.resolve("lock")), store, new RuntimePaths(temp.toString()));
        service.initialize();
        try {
            var session = service.createSessionPreview("test.pdf", "application/pdf", new ByteArrayInputStream(new byte[]{1}), 7);
            assertThrows(RuntimeException.class, () -> service.startTranslation(session.getTaskId(), 1, 1, "auto", 2, new AuthUser(7, "test", "USER", 10, true)));
            assertEquals("creating", mapper.readTree(session.getMetadataPath().toFile()).path("status").asText());
            assertTrue(session.isRefundPending()); assertTrue(store.isDirty(session.getMetadataPath()));
            service.cleanupHistory(); assertTrue(Files.exists(session.getMetadataPath()));
            verify(quota, never()).refund(anyLong(), anyString()); verifyNoInteractions(babel);
            diskFailure.set(false); service.reconcilePendingRefunds();
            verify(quota).refund(eq(42L), anyString());
            assertFalse(session.isRefundPending()); assertTrue(session.isCreditRefunded());
            assertEquals("error", mapper.readTree(session.getMetadataPath().toFile()).path("status").asText());
            service.reconcilePendingRefunds(); verify(quota, times(1)).refund(eq(42L), anyString());
        } finally { service.shutdown(); }
    }
}
