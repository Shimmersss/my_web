package help.shimmer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppUpdateManager implements AutoCloseable {
    private static final String PACKAGE_NAME = "help.shimmer.app";
    private static final String UPDATE_MANIFEST_URL =
            "https://shimmer.help/api/app-update/latest";
    private static final String UPDATE_APK_PREFIX = "/api/app-update/apk/";
    private static final int INSTALL_PERMISSION_REQUEST = 7201;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final long MAX_APK_BYTES = 256L * 1024L * 1024L;

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile boolean closed;
    private File pendingInstall;
    private AlertDialog updateDialog;
    private AlertDialog progressDialog;

    AppUpdateManager(Activity activity) {
        this.activity = activity;
    }

    void checkForUpdates() {
        if (closed || (updateDialog != null && updateDialog.isShowing())
                || !checking.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                UpdateInfo update = readUpdateInfo();
                if (update.versionCode > installedVersionCode()) {
                    runOnUiThread(() -> showUpdatePrompt(update));
                }
            } catch (Exception ignored) {
                // Automatic checks are deliberately quiet when offline or when the feed is unavailable.
            } finally {
                checking.set(false);
            }
        });
    }

    private UpdateInfo readUpdateInfo() throws Exception {
        HttpURLConnection connection = openHttps(UPDATE_MANIFEST_URL, "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IllegalStateException("HTTP " + status);
            byte[] payload = readLimited(connection.getInputStream(), MAX_MANIFEST_BYTES);
            JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            if (json.optInt("protocolVersion", -1) != 1) throw new IllegalArgumentException("protocol");
            if (!PACKAGE_NAME.equals(json.optString("packageName"))) throw new IllegalArgumentException("package");
            long versionCode = json.optLong("versionCode", -1);
            String versionName = json.optString("versionName", "");
            String apkUrl = json.optString("apkUrl", "");
            String sha256 = json.optString("sha256", "").toLowerCase(Locale.ROOT);
            long sizeBytes = json.optLong("sizeBytes", -1);
            if (versionCode <= 0 || versionName.length() > 80 || versionName.isEmpty()) {
                throw new IllegalArgumentException("version");
            }
            Uri uri = Uri.parse(apkUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"shimmer.help".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || uri.getPath() == null || !uri.getPath().startsWith(UPDATE_APK_PREFIX)) {
                throw new IllegalArgumentException("url");
            }
            if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("digest");
            if (sizeBytes <= 0 || sizeBytes > MAX_APK_BYTES) throw new IllegalArgumentException("size");
            return new UpdateInfo(versionCode, versionName, apkUrl, sha256, sizeBytes,
                    json.optString("notes", "").substring(0,
                            Math.min(500, json.optString("notes", "").length())));
        } finally {
            connection.disconnect();
        }
    }

    private void showUpdatePrompt(UpdateInfo update) {
        if (!isActivityUsable() || (updateDialog != null && updateDialog.isShowing())) return;
        String message = "发现新版本 " + update.versionName;
        if (!update.notes.isEmpty()) message += "\n\n" + update.notes;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Shimmer App 更新")
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载更新", (ignoredDialog, which) -> downloadUpdate(update))
                .create();
        updateDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (updateDialog == dialog) updateDialog = null;
        });
        dialog.show();
    }

    private void downloadUpdate(UpdateInfo update) {
        showProgress();
        executor.execute(() -> {
            File candidate = null;
            try {
                File updateDir = new File(activity.getCacheDir(), "updates");
                if (!updateDir.isDirectory() && !updateDir.mkdirs()) {
                    throw new IllegalStateException("update directory");
                }
                candidate = new File(updateDir, "shimmer-update-" + update.versionCode + ".apk");
                downloadAndVerify(update, candidate);
                verifyApkIdentity(candidate, update.versionCode);
                pendingInstall = candidate;
                runOnUiThread(this::requestInstall);
            } catch (Exception error) {
                if (candidate != null && candidate.isFile()) candidate.delete();
                runOnUiThread(() -> Toast.makeText(activity,
                        "更新包验证失败，请稍后重试", Toast.LENGTH_LONG).show());
            } finally {
                runOnUiThread(this::hideProgress);
            }
        });
    }

    private void downloadAndVerify(UpdateInfo update, File destination) throws Exception {
        HttpURLConnection connection = openHttps(update.apkUrl, "application/vnd.android.package-archive");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("download HTTP");
            }
            long declared = connection.getContentLengthLong();
            if (declared != -1 && declared != update.sizeBytes) throw new IllegalArgumentException("length");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_APK_BYTES || total > update.sizeBytes) {
                        throw new IllegalArgumentException("oversize");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (total != update.sizeBytes) throw new IllegalArgumentException("size mismatch");
            String actual = toHex(digest.digest());
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                    update.sha256.getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("digest mismatch");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void verifyApkIdentity(File apk, long expectedVersionCode) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(PACKAGE_NAME, flags);
        if (candidate == null || !PACKAGE_NAME.equals(candidate.packageName)
                || versionCode(candidate) != expectedVersionCode
                || expectedVersionCode <= versionCode(installed)) {
            throw new SecurityException("APK identity mismatch");
        }
        Signature[] candidateSignatures = signatures(candidate);
        Signature[] installedSignatures = signatures(installed);
        if (candidateSignatures.length != 1 || installedSignatures.length != 1
                || !MessageDigest.isEqual(candidateSignatures[0].toByteArray(),
                installedSignatures[0].toByteArray())) {
            throw new SecurityException("APK signer mismatch");
        }
    }

    private void requestInstall() {
        if (!isActivityUsable() || pendingInstall == null || !pendingInstall.isFile()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivityForResult(settingsIntent, INSTALL_PERMISSION_REQUEST);
            } catch (ActivityNotFoundException error) {
                Toast.makeText(activity, "请在系统设置中允许此 App 安装更新", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".files", pendingInstall);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "系统没有可用的安装程序", Toast.LENGTH_LONG).show();
        }
    }

    boolean onActivityResult(int requestCode) {
        if (requestCode != INSTALL_PERMISSION_REQUEST) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls()) {
            requestInstall();
        } else {
            Toast.makeText(activity, "未获得安装权限，更新已取消", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private HttpURLConnection openHttps(String rawUrl, String accept) throws Exception {
        URL url = new URL(rawUrl);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !"shimmer.help".equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null) {
            throw new SecurityException("untrusted update origin");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(120_000);
        connection.setRequestProperty("Accept", accept);
        connection.setRequestProperty("User-Agent", "ShimmerAndroidUpdater/1");
        return connection;
    }

    private byte[] readLimited(InputStream input, long limit) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > limit) throw new IllegalArgumentException("response too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    private long installedVersionCode() throws PackageManager.NameNotFoundException {
        return versionCode(activity.getPackageManager().getPackageInfo(PACKAGE_NAME, 0));
    }

    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            return info.signingInfo.getApkContentsSigners();
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private void showProgress() {
        if (!isActivityUsable()) return;
        progressDialog = new AlertDialog.Builder(activity)
                .setTitle("正在下载更新")
                .setMessage("下载完成后会由 Android 系统确认安装。")
                .setCancelable(false)
                .create();
        progressDialog.show();
    }

    private void hideProgress() {
        if (progressDialog != null) {
            progressDialog.dismiss();
            progressDialog = null;
        }
    }

    private boolean isActivityUsable() {
        return !closed && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed());
    }

    private void runOnUiThread(Runnable action) {
        if (!closed) activity.runOnUiThread(() -> {
            if (isActivityUsable()) action.run();
        });
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        activity.runOnUiThread(() -> {
            if (updateDialog != null) {
                updateDialog.dismiss();
                updateDialog = null;
            }
            hideProgress();
        });
    }

    private static final class UpdateInfo {
        final long versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final long sizeBytes;
        final String notes;

        UpdateInfo(long versionCode, String versionName, String apkUrl,
                   String sha256, long sizeBytes, String notes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.notes = notes;
        }
    }
}
