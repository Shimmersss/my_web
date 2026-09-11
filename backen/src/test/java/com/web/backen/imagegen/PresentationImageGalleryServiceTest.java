package com.web.backen.imagegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.RuntimeConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresentationImageGalleryServiceTest {
    @TempDir Path temp;

    @Test
    void publicationSurvivesSourceCleanupAndPreservesOwnershipAndIdempotency() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeConfigService runtime = mock(RuntimeConfigService.class);
        when(runtime.presentationImageMaxGlobalHistory()).thenReturn(100);
        when(runtime.presentationImageMaxHistory()).thenReturn(10);
        when(runtime.imageGenerationQuality()).thenReturn("medium");
        Path galleryRoot = temp.resolve("gallery");
        var gallery = new PresentationImageGalleryService(mapper, runtime, galleryRoot);
        Path task = temp.resolve("task");
        Path generated = Files.createDirectories(task.resolve("generated-images"));
        ImageIO.write(new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", generated.resolve("gpt-1.png").toFile());
        mapper.writeValue(generated.resolve("generated-image-manifest.json").toFile(), Map.of("images", List.of(
                Map.of("id", "GPT01", "fileName", "gpt-1.png", "slideId", "slide-1",
                        "slideIndex", 1, "slideTitle", "Overview", "prompt", "private illustration"),
                Map.of("id", "GPT02", "fileName", "../outside.png"))));
        var publication = new PresentationImageGalleryService.Publication("task-123", 7, "html", task);
        AuthUser owner = new AuthUser(7, "owner", "USER", 10, true);
        AuthUser other = new AuthUser(8, "other", "USER", 10, true);
        AuthUser root = new AuthUser(1, "root", "ROOT", 0, true);
        gallery.publish(publication);
        Map<String, Object> original = gallery.recent(owner).get(0);
        gallery.publish(publication);

        assertEquals(List.of(original), gallery.recent(owner));
        assertEquals("task-123", original.get("taskId"));
        assertEquals(7L, ((Number) original.get("userId")).longValue());
        assertEquals("html", original.get("outputFormat"));
        assertEquals("slide-1", original.get("slideId"));
        assertEquals("private illustration", original.get("prompt"));
        assertEquals("medium", original.get("quality"));
        assertEquals(List.of(), gallery.recent(other));
        assertThrows(AuthException.class, () -> gallery.file("task-123-gpt01", false, other));
        assertThrows(AuthException.class, () -> gallery.delete("task-123-gpt01", root));
        assertEquals(32, ImageIO.read(gallery.file("task-123-gpt01", false, root).toFile()).getWidth());
        assertEquals(24, ImageIO.read(gallery.file("task-123-gpt01", true, owner).toFile()).getHeight());

        try (var paths = Files.walk(task)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        var reopened = new PresentationImageGalleryService(mapper, runtime, galleryRoot);
        assertTrue(Files.isRegularFile(reopened.file("task-123-gpt01", false, owner)));
        reopened.delete("task-123-gpt01", owner);
        assertEquals(List.of(), reopened.recent(root));
    }
}
