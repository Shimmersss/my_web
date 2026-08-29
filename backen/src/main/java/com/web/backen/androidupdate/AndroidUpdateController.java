package com.web.backen.androidupdate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/app-update")
public class AndroidUpdateController {
    private static final String PACKAGE_NAME = "help.shimmer.app";
    private static final long MAX_APK_BYTES = 256L * 1024L * 1024L;
    private static final Pattern APK_FILE = Pattern.compile("shimmer-internal-[0-9A-Za-z.-]{1,64}\\.apk");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final Path storageDir;
    private final ObjectMapper objectMapper;

    public AndroidUpdateController(
            @Value("${android.update.storage-dir:../.run/android-updates}") String storageDir,
            ObjectMapper objectMapper) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest() throws IOException {
        UpdateManifest manifest = readManifest();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocolVersion", 1);
        response.put("packageName", PACKAGE_NAME);
        response.put("versionCode", manifest.versionCode());
        response.put("versionName", manifest.versionName());
        response.put("apkUrl", "https://shimmer.help/api/app-update/apk/" + manifest.apkFile());
        response.put("sha256", manifest.sha256());
        response.put("sizeBytes", manifest.sizeBytes());
        response.put("notes", manifest.notes());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(response);
    }

    @GetMapping("/apk/{filename:.+}")
    public ResponseEntity<FileSystemResource> apk(@PathVariable String filename) throws IOException {
        UpdateManifest manifest = readManifest();
        if (!manifest.apkFile().equals(filename)) {
            return ResponseEntity.notFound().build();
        }
        Path apk = storageDir.resolve(filename).normalize();
        if (!apk.getParent().equals(storageDir) || !Files.isRegularFile(apk)
                || Files.size(apk) != manifest.sizeBytes()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .contentLength(manifest.sizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(apk));
    }

    private UpdateManifest readManifest() {
        Path manifestPath = storageDir.resolve("latest.json");
        final JsonNode root;
        try {
            if (!Files.isRegularFile(manifestPath) || Files.size(manifestPath) > 64 * 1024) {
                throw new AndroidUpdateUnavailableException();
            }
            root = objectMapper.readTree(manifestPath.toFile());
        } catch (IOException error) {
            throw new AndroidUpdateUnavailableException();
        }
        if (root == null || !root.isObject() || root.size() != 8
                || root.path("protocolVersion").asInt(-1) != 1
                || !PACKAGE_NAME.equals(root.path("packageName").asText())) {
            throw new AndroidUpdateUnavailableException();
        }
        long versionCode = root.path("versionCode").asLong(-1);
        String versionName = root.path("versionName").asText("");
        String apkFile = root.path("apkFile").asText("");
        String sha256 = root.path("sha256").asText("");
        long sizeBytes = root.path("sizeBytes").asLong(-1);
        String notes = root.path("notes").asText("");
        if (versionCode <= 0 || versionName.isBlank() || versionName.length() > 80
                || !APK_FILE.matcher(apkFile).matches() || !SHA256.matcher(sha256).matches()
                || sizeBytes <= 0 || sizeBytes > MAX_APK_BYTES || notes.length() > 500) {
            throw new AndroidUpdateUnavailableException();
        }
        return new UpdateManifest(versionCode, versionName, apkFile, sha256, sizeBytes, notes);
    }

    private record UpdateManifest(long versionCode, String versionName, String apkFile,
                                  String sha256, long sizeBytes, String notes) {}
}
