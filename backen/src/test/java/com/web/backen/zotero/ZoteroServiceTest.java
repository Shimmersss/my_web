package com.web.backen.zotero;

import com.sun.net.httpserver.HttpServer;
import com.web.backen.config.ZoteroConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ZoteroServiceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsSlowAttachmentWithoutWaitingForCompleteDownload() throws Exception {
        byte[] first = "%PDF-".getBytes(StandardCharsets.UTF_8);
        byte[] rest = "slow-body".getBytes(StandardCharsets.UTF_8);
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, first.length + rest.length);
            exchange.getResponseBody().write(first);
            exchange.getResponseBody().flush();
            Thread.sleep(1_000);
            exchange.getResponseBody().write(rest);
            exchange.close();
        });

        long startedAt = System.nanoTime();
        try (ZoteroService.ProxiedFile file = service().fetchItemFile("PDF")) {
            long returnedInMillis = (System.nanoTime() - startedAt) / 1_000_000;
            assertThat(returnedInMillis).isLessThan(800);
            assertThat(file.contentType().toString()).isEqualTo("application/pdf");
            assertThat(file.contentLength()).isEqualTo(first.length + rest.length);
            assertThat(file.body().readAllBytes()).isEqualTo("%PDF-slow-body".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void streamsFirstFileFromZipAttachment() throws Exception {
        byte[] zip = zip("note.md", "# streamed");
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, zip.length);
            exchange.getResponseBody().write(zip);
            exchange.close();
        });

        try (ZoteroService.ProxiedFile file = service().fetchItemFile("ZIP")) {
            assertThat(file.contentType().toString()).isEqualTo("text/markdown;charset=UTF-8");
            assertThat(file.contentLength()).isEqualTo(-1);
            assertThat(file.body().readAllBytes()).isEqualTo("# streamed".getBytes(StandardCharsets.UTF_8));
        }
    }

    private ZoteroService service() {
        ZoteroConfig config = new ZoteroConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setUserId("user");
        config.setApiKey("key");
        return new ZoteroService(null, config);
    }

    private void startServer(ThrowingHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/users/user/items/PDF/file", exchange -> runHandler(handler, exchange));
        server.createContext("/users/user/items/ZIP/file", exchange -> runHandler(handler, exchange));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void runHandler(ThrowingHandler handler, com.sun.net.httpserver.HttpExchange exchange) {
        try {
            handler.handle(exchange);
        } catch (Exception e) {
            exchange.close();
        }
    }

    private byte[] zip(String filename, String content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(filename));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
