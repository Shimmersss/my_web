package help.shimmer.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LauncherActivity extends Activity {
    private static final String HOME_URL = "https://shimmer.help/";
    private static final String PRIMARY_ORIGIN = "https://shimmer.help";
    private static final String USER_AGENT_TOKEN = "ShimmerAndroid/0.3";
    private static final String BRIDGE_LOCATION =
            "resource://android/assets/shimmer_bridge/";
    private static final String BRIDGE_ID = "shimmer-bridge@shimmer.help";
    private static final String BRIDGE_APP = "shimmer";
    private static final int FILE_CHOOSER_REQUEST = 7101;
    private static final int SAVE_DOCUMENT_REQUEST = 7102;
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_TEXT_BYTES = 1024L * 1024L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private GeckoView geckoView;
    private GeckoSession session;
    private ProgressBar pageProgress;
    private View errorPanel;
    private boolean canGoBack;
    private boolean fullScreen;
    private boolean destroyed;
    private String currentUrl = HOME_URL;
    private PendingSave pendingSave;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> filePromptResult;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;
    private AppUpdateManager appUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        geckoView = findViewById(R.id.gecko_view);
        pageProgress = findViewById(R.id.page_progress);
        errorPanel = findViewById(R.id.error_panel);
        findViewById(R.id.retry_button).setOnClickListener(view -> reload());

        createSession();
        session.loadUri(resolveInitialUrl(getIntent()));

        appUpdateManager = new AppUpdateManager(this);
        geckoView.postDelayed(() -> {
            if (appUpdateManager != null) appUpdateManager.checkForUpdates();
        }, 1500L);
    }

    private void createSession() {
        String userAgent = GeckoSession.getDefaultUserAgent() + " " + USER_AGENT_TOKEN;
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .userAgentOverride(userAgent)
                .build();
        session = new GeckoSession(settings);
        session.setProgressDelegate(new PageProgressDelegate());
        session.setNavigationDelegate(new PageNavigationDelegate());
        session.setContentDelegate(new PageContentDelegate());
        session.setPromptDelegate(new ShimmerPromptDelegate(this, this::launchFilePrompt));
        session.open(((Application) getApplication()).getGeckoRuntime());
        geckoView.setSession(session);
        installBridge();
    }

    private void installBridge() {
        ((Application) getApplication()).getGeckoRuntime().getWebExtensionController()
                .ensureBuiltIn(BRIDGE_LOCATION, BRIDGE_ID)
                .accept(extension -> {
                    if (extension == null || destroyed || session == null) return;
                    session.getWebExtensionController().setMessageDelegate(
                            extension, new BridgeMessageDelegate(), BRIDGE_APP);
                }, error -> runOnUiThread(() -> Toast.makeText(
                        this, "App 文件桥接初始化失败", Toast.LENGTH_LONG).show()));
    }

    private void reload() {
        hideError();
        if (session == null || !session.isOpen()) {
            createSession();
            session.loadUri(currentUrl == null ? HOME_URL : currentUrl);
        } else {
            session.reload();
        }
    }

    private void handleNativeMessage(Object message, WebExtension.MessageSender sender) {
        if (!(message instanceof JSONObject)
                || sender.session != session
                || !sender.isTopLevel()
                || sender.environmentType != WebExtension.MessageSender.ENV_TYPE_CONTENT_SCRIPT
                || !isPrimaryPage(sender.url)
                || !isPrimaryPage(currentUrl)) return;
        JSONObject payload = (JSONObject) message;
        try {
            if (payload.toString().length() > 64 * 1024
                    || payload.optInt("protocolVersion", -1) != 1) return;
            String type = payload.optString("type", "");
            if ("download".equals(type)) {
                Uri uri = parseTrustedDownloadUri(payload.optString("url", ""));
                if (uri == null) throw new JSONException("untrusted download");
                session.loadUri(uri.toString());
            } else if ("saveText".equals(type)) {
                String text = payload.optString("text", "");
                if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                    throw new JSONException("text too large");
                }
                requestSave(PendingSave.forText(
                        sanitizeFilename(payload.optString("filename", "shimmer-export.txt")),
                        safeTextMimeType(payload.optString("mimeType", "text/plain")), text));
            }
        } catch (JSONException error) {
            Toast.makeText(this, "App 无法处理该下载请求", Toast.LENGTH_SHORT).show();
        }
    }

    private void receiveDownload(WebResponse response) {
        Uri source = parseTrustedDownloadUri(response.uri);
        if (source == null || !response.isSecure || response.statusCode < 200
                || response.statusCode >= 300 || response.body == null) {
            closeQuietly(response.body);
            Toast.makeText(this, "已阻止不受信任的下载响应", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingSave != null) {
            closeQuietly(response.body);
            Toast.makeText(this, "请先完成当前文件保存", Toast.LENGTH_SHORT).show();
            return;
        }

        long declared = parseLength(response.headers);
        if (declared > MAX_DOWNLOAD_BYTES) {
            closeQuietly(response.body);
            Toast.makeText(this, "下载文件超过 256 MB 限制", Toast.LENGTH_LONG).show();
            return;
        }
        String mimeType = safeMimeType(header(response.headers, "content-type"));
        String filename = downloadFilename(source, response.headers, mimeType);
        Toast.makeText(this, "正在准备下载…", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> cacheDownload(response.body, filename, mimeType));
    }

    private void cacheDownload(InputStream body, String filename, String mimeType) {
        File cached = null;
        try {
            File directory = new File(getCacheDir(), "gecko-downloads");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cache dir");
            cached = File.createTempFile("download-", ".part", directory);
            try (InputStream input = body; FileOutputStream output = new FileOutputStream(cached)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("download too large");
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            File ready = cached;
            runOnUiThread(() -> requestSave(PendingSave.forFile(filename, mimeType, ready)));
        } catch (Exception error) {
            closeQuietly(body);
            if (cached != null) cached.delete();
            runOnUiThread(() -> Toast.makeText(
                    this, "下载失败，请重试", Toast.LENGTH_LONG).show());
        }
    }

    private void requestSave(PendingSave save) {
        if (pendingSave != null) {
            save.cleanup();
            Toast.makeText(this, "请先完成当前文件保存", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingSave = save;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(save.mimeType)
                .putExtra(Intent.EXTRA_TITLE, save.filename);
        try {
            startActivityForResult(intent, SAVE_DOCUMENT_REQUEST);
        } catch (ActivityNotFoundException error) {
            pendingSave.cleanup();
            pendingSave = null;
            Toast.makeText(this, "系统没有可用的文件保存器", Toast.LENGTH_LONG).show();
        }
    }

    private void saveToUri(PendingSave save, Uri destination) {
        Toast.makeText(this, "正在保存文件…", Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            try (OutputStream output = requireOutputStream(destination)) {
                if (save.text != null) {
                    output.write(save.text.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (InputStream input = new FileInputStream(save.cachedFile)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, "文件已保存", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                getContentResolver().delete(destination, null, null);
                runOnUiThread(() -> Toast.makeText(
                        this, "文件保存失败，请重试", Toast.LENGTH_LONG).show());
            } finally {
                save.cleanup();
            }
        });
    }

    private void launchFilePrompt(GeckoSession.PromptDelegate.FilePrompt prompt,
                                  GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result) {
        if (filePromptResult != null || prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
            result.complete(prompt.dismiss());
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
                Toast.makeText(this, "暂不支持选择整个文件夹", Toast.LENGTH_LONG).show();
            }
            return;
        }
        filePrompt = prompt;
        filePromptResult = result;
        String[] types = prompt.mimeTypes == null ? new String[0] : prompt.mimeTypes;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(types.length == 1 ? safeMimeType(types[0]) : "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE);
        if (types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        try {
            startActivityForResult(intent, FILE_CHOOSER_REQUEST);
        } catch (ActivityNotFoundException error) {
            finishFilePrompt(null);
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void finishFilePrompt(Uri[] selected) {
        GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = filePromptResult;
        GeckoSession.PromptDelegate.FilePrompt prompt = filePrompt;
        filePromptResult = null;
        filePrompt = null;
        if (result == null || prompt == null) return;
        if (selected == null || selected.length == 0) result.complete(prompt.dismiss());
        else if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
            result.complete(prompt.confirm(this, selected));
        } else {
            result.complete(prompt.confirm(this, selected[0]));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (appUpdateManager != null && appUpdateManager.onActivityResult(requestCode)) return;
        if (requestCode == FILE_CHOOSER_REQUEST) {
            finishFilePrompt(readSelectedFiles(resultCode, data));
            return;
        }
        if (requestCode == SAVE_DOCUMENT_REQUEST) {
            PendingSave save = pendingSave;
            pendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && save != null) {
                saveToUri(save, data.getData());
            } else if (save != null) {
                save.cleanup();
            }
        }
    }

    private Uri[] readSelectedFiles(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) return null;
        LinkedHashSet<Uri> candidates = new LinkedHashSet<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                candidates.add(clipData.getItemAt(index).getUri());
            }
        }
        if (data.getData() != null) candidates.add(data.getData());
        ArrayList<Uri> accepted = new ArrayList<>();
        for (Uri uri : candidates) {
            if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) continue;
            try (ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "r")) {
                if (ignored != null) accepted.add(uri);
            } catch (IOException | SecurityException ignored) {
                // Reject unreadable providers before handing the URI to Gecko.
            }
        }
        return accepted.isEmpty() ? null : accepted.toArray(new Uri[0]);
    }

    private Uri parseTrustedDownloadUri(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return null;
        Uri uri = Uri.parse(rawUrl);
        if (!uri.isAbsolute()) uri = Uri.parse(HOME_URL).buildUpon()
                .encodedPath(uri.getEncodedPath()).encodedQuery(uri.getEncodedQuery()).build();
        if (!isPrimaryHttpsUri(uri) || uri.getFragment() != null) return null;
        String encodedPath = uri.getEncodedPath();
        if (encodedPath == null) return null;
        String normalized = encodedPath.toLowerCase(Locale.ROOT);
        if (normalized.contains("%2f") || normalized.contains("%5c") || normalized.contains("%00")) {
            return null;
        }
        String path = uri.getPath();
        String id = "[A-Za-z0-9_-]{1,128}";
        if (path.matches("/api/translate/download/" + id)) {
            return hasOnlyQueryParameters(uri, Collections.emptySet()) ? uri : null;
        }
        if (path.matches("/api/translate/download-(pdf|image)/" + id)) {
            if (!hasOnlyQueryParameters(uri, Collections.singleton("mode"))) return null;
            String mode = uri.getQueryParameter("mode");
            return mode == null || "translated".equals(mode) || "bilingual".equals(mode) ? uri : null;
        }
        if (path.matches("/api/ppt-generate/download/" + id)) {
            if (!hasOnlyQueryParameters(uri, Collections.singleton("artifact"))) return null;
            String artifact = uri.getQueryParameter("artifact");
            return artifact == null || "pptd".equals(artifact) ? uri : null;
        }
        if (path.matches("/api/image-generate/result/" + id)) {
            return hasOnlyQueryParameters(uri, Collections.singleton("v")) ? uri : null;
        }
        if (path.matches("/api/image-generate/presentation-assets/" + id + "/result")) {
            return hasOnlyQueryParameters(uri, Collections.emptySet()) ? uri : null;
        }
        if (path.matches("/api/zotero/file/[A-Za-z0-9]{8}")) {
            return hasOnlyQueryParameters(uri, Collections.emptySet()) ? uri : null;
        }
        return null;
    }

    private boolean hasOnlyQueryParameters(Uri uri, Set<String> allowedNames) {
        Set<String> actualNames = uri.getQueryParameterNames();
        if (!allowedNames.containsAll(actualNames)) return false;
        for (String name : actualNames) if (uri.getQueryParameters(name).size() != 1) return false;
        return true;
    }

    private boolean isTrustedHttpsUri(Uri uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && ("shimmer.help".equalsIgnoreCase(uri.getHost())
                    || "www.shimmer.help".equalsIgnoreCase(uri.getHost()))
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private boolean isPrimaryHttpsUri(Uri uri) {
        return isTrustedHttpsUri(uri) && "shimmer.help".equalsIgnoreCase(uri.getHost());
    }

    private boolean isPrimaryPage(String rawUrl) {
        if (rawUrl == null) return false;
        Uri uri = Uri.parse(rawUrl);
        return isPrimaryHttpsUri(uri);
    }

    private Uri canonicalize(Uri uri) {
        if (uri != null && "www.shimmer.help".equalsIgnoreCase(uri.getHost())) {
            return uri.buildUpon().authority("shimmer.help").build();
        }
        return uri;
    }

    private String resolveInitialUrl(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && isTrustedHttpsUri(intent.getData())) return canonicalize(intent.getData()).toString();
        return HOME_URL;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // launchMode=singleTask keeps this Activity alive. A fresh launcher or
        // App Link open therefore arrives here instead of onCreate(); recheck
        // without persisting cancellation so an outdated build prompts once
        // again on every explicit open.
        if (appUpdateManager != null) appUpdateManager.checkForUpdates();
        if (session != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && isTrustedHttpsUri(intent.getData())) {
            session.loadUri(canonicalize(intent.getData()).toString());
        }
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可打开该链接的应用", Toast.LENGTH_LONG).show();
        }
    }

    private void showError() {
        pageProgress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    private void setFullScreen(boolean enabled) {
        fullScreen = enabled;
        getWindow().getDecorView().setSystemUiVisibility(enabled
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    protected void onPause() {
        if (session != null && session.isOpen()) session.setActive(false);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.isOpen()) session.setActive(true);
    }

    @Override
    public void onBackPressed() {
        if (fullScreen && session != null) {
            session.exitFullScreen();
        } else if (canGoBack && session != null) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        finishFilePrompt(null);
        if (pendingSave != null) pendingSave.cleanup();
        pendingSave = null;
        if (session != null) {
            session.close();
            session = null;
        }
        ioExecutor.shutdownNow();
        if (appUpdateManager != null) appUpdateManager.close();
        appUpdateManager = null;
        super.onDestroy();
    }

    private final class PageProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(GeckoSession session, String url) {
            hideError();
            pageProgress.setProgress(0);
            pageProgress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onProgressChange(GeckoSession session, int progress) {
            pageProgress.setProgress(progress);
            if (progress >= 100) pageProgress.setVisibility(View.GONE);
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            pageProgress.setVisibility(View.GONE);
            if (!success && isPrimaryPage(currentUrl)) showError();
        }
    }

    private final class PageNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession session, LoadRequest request) {
            Uri uri = Uri.parse(request.uri);
            if (isPrimaryHttpsUri(uri)) {
                if (request.target == TARGET_WINDOW_NEW) {
                    session.loadUri(uri.toString());
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            if (isTrustedHttpsUri(uri)) {
                session.loadUri(canonicalize(uri).toString());
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme) || "mailto".equalsIgnoreCase(scheme)
                    || "tel".equalsIgnoreCase(scheme)) openExternal(uri);
            else Toast.makeText(LauncherActivity.this,
                    "已阻止不受支持的链接", Toast.LENGTH_SHORT).show();
            return GeckoResult.fromValue(AllowOrDeny.DENY);
        }

        @Override
        public void onLocationChange(GeckoSession session, String url,
                                     List<GeckoSession.PermissionDelegate.ContentPermission> perms,
                                     Boolean hasUserGesture) {
            if (url != null) currentUrl = url;
        }

        @Override
        public void onCanGoBack(GeckoSession session, boolean value) {
            canGoBack = value;
        }
    }

    private final class PageContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onExternalResponse(GeckoSession session, WebResponse response) {
            receiveDownload(response);
        }

        @Override
        public void onFullScreen(GeckoSession session, boolean value) {
            setFullScreen(value);
        }

        @Override
        public void onCrash(GeckoSession crashed) {
            recoverSession();
        }

        @Override
        public void onKill(GeckoSession killed) {
            recoverSession();
        }
    }

    private void recoverSession() {
        if (destroyed) return;
        geckoView.releaseSession();
        session = null;
        canGoBack = false;
        showError();
    }

    private final class BridgeMessageDelegate implements WebExtension.MessageDelegate {
        @Override
        public GeckoResult<Object> onMessage(String nativeApp, Object message,
                                             WebExtension.MessageSender sender) {
            if (BRIDGE_APP.equals(nativeApp)) handleNativeMessage(message, sender);
            return null;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static long parseLength(Map<String, String> headers) {
        try {
            String value = header(headers, "content-length");
            return value == null ? -1 : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String downloadFilename(Uri uri, Map<String, String> headers, String mimeType) {
        String disposition = header(headers, "content-disposition");
        if (disposition != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)filename\\s*=\\s*\\\"?([^\\\";]+)")
                    .matcher(disposition);
            if (matcher.find()) return sanitizeFilename(matcher.group(1));
        }
        String segment = uri.getLastPathSegment();
        String guessed = segment == null || segment.isEmpty() ? "shimmer-download" : segment;
        if (!guessed.contains(".")) {
            if ("application/pdf".equals(mimeType)) guessed += ".pdf";
            else if (mimeType.startsWith("image/")) guessed += ".png";
        }
        return sanitizeFilename(guessed);
    }

    private String sanitizeFilename(String filename) {
        String safe = filename == null ? "shimmer-download" : filename;
        safe = safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty()) safe = "shimmer-download";
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private String safeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isEmpty() || mimeType.length() > 120
                || mimeType.indexOf('\n') >= 0 || mimeType.indexOf('\r') >= 0) {
            return "application/octet-stream";
        }
        String base = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return base.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
                ? base : "application/octet-stream";
    }

    private String safeTextMimeType(String mimeType) {
        String safe = safeMimeType(mimeType);
        return safe.startsWith("text/") ? safe : "text/plain";
    }

    private OutputStream requireOutputStream(Uri destination) throws IOException {
        OutputStream output = getContentResolver().openOutputStream(destination, "w");
        if (output == null) throw new IOException("destination unavailable");
        return output;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) return;
        try {
            input.close();
        } catch (IOException ignored) {
            // Best effort cleanup.
        }
    }

    private static final class PendingSave {
        final String filename;
        final String mimeType;
        final String text;
        final File cachedFile;

        private PendingSave(String filename, String mimeType, String text, File cachedFile) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.text = text;
            this.cachedFile = cachedFile;
        }

        static PendingSave forText(String filename, String mimeType, String text) {
            return new PendingSave(filename, mimeType, text, null);
        }

        static PendingSave forFile(String filename, String mimeType, File file) {
            return new PendingSave(filename, mimeType, null, file);
        }

        void cleanup() {
            if (cachedFile != null) cachedFile.delete();
        }
    }
}
