package com.web.backen.androidupdate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AndroidUpdateControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsValidatedManifestAndOnlyItsApk() throws Exception {
        byte[] apk = "signed-apk-fixture".getBytes();
        String apkFile = "shimmer-internal-0.2.1.apk";
        Files.write(tempDir.resolve(apkFile), apk);
        Files.writeString(tempDir.resolve("latest.json"), """
                {"protocolVersion":1,"packageName":"help.shimmer.app","versionCode":4,
                 "versionName":"0.2.1-internal","apkFile":"shimmer-internal-0.2.1.apk",
                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "sizeBytes":18,"notes":"安全更新"}
                """);
        AndroidUpdateController controller = controller();

        Map<String, Object> latest = controller.latest().getBody();
        assertEquals(4L, latest.get("versionCode"));
        assertEquals("https://shimmer.help/api/app-update/apk/" + apkFile, latest.get("apkUrl"));
        assertEquals(200, controller.apk(apkFile).getStatusCode().value());
        assertEquals(404, controller.apk("shimmer-internal-0.2.0.apk").getStatusCode().value());
    }

    @Test
    void rejectsUnexpectedFieldsTraversalAndLengthMismatch() throws Exception {
        Files.writeString(tempDir.resolve("latest.json"), """
                {"protocolVersion":1,"packageName":"help.shimmer.app","versionCode":4,
                 "versionName":"0.2.1-internal","apkFile":"../evil.apk",
                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "sizeBytes":1,"notes":"","extra":true}
                """);
        assertThrows(AndroidUpdateUnavailableException.class, () -> controller().latest());

        Files.writeString(tempDir.resolve("latest.json"), """
                {"protocolVersion":1,"packageName":"help.shimmer.app","versionCode":4,
                 "versionName":"0.2.1-internal","apkFile":"shimmer-internal-0.2.1.apk",
                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "sizeBytes":100,"notes":""}
                """);
        Files.writeString(tempDir.resolve("shimmer-internal-0.2.1.apk"), "short");
        ResponseEntity<?> response = controller().apk("shimmer-internal-0.2.1.apk");
        assertEquals(404, response.getStatusCode().value());
    }

    private AndroidUpdateController controller() {
        return new AndroidUpdateController(tempDir.toString(), new ObjectMapper());
    }
}
