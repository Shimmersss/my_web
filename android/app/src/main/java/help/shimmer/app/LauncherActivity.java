package help.shimmer.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabsService;

import com.google.androidbrowserhelper.trusted.TwaLauncher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LauncherActivity extends com.google.androidbrowserhelper.trusted.LauncherActivity {
    private static final String PREFERENCES = "browser_preferences";
    private static final String SELECTED_BROWSER = "selected_browser";

    private String selectedBrowserPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        selectedBrowserPackage = readAvailableSelection();
        super.onCreate(savedInstanceState);

        if (!isFinishing() && selectedBrowserPackage == null) {
            showBrowserChooser();
        }
    }

    @Override
    protected boolean shouldLaunchImmediately() {
        return selectedBrowserPackage != null;
    }

    @Override
    protected TwaLauncher createTwaLauncher() {
        return new TwaLauncher(this, selectedBrowserPackage);
    }

    @Override
    protected Uri getLaunchingUrl() {
        return super.getLaunchingUrl();
    }

    private String readAvailableSelection() {
        SharedPreferences preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        String savedPackage = preferences.getString(SELECTED_BROWSER, null);
        if (savedPackage == null) return null;

        for (BrowserOption option : findCompatibleBrowsers()) {
            if (savedPackage.equals(option.packageName)) return savedPackage;
        }

        preferences.edit().remove(SELECTED_BROWSER).apply();
        return null;
    }

    private void showBrowserChooser() {
        List<BrowserOption> browsers = findCompatibleBrowsers();
        if (browsers.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要兼容浏览器")
                    .setMessage("未检测到支持应用内网页的浏览器。请安装或启用支持 Custom Tabs 的浏览器后重试。")
                    .setPositiveButton("重试", (dialog, which) -> showBrowserChooser())
                    .setNegativeButton("退出", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }

        String[] labels = new String[browsers.size()];
        for (int index = 0; index < browsers.size(); index++) {
            labels[index] = browsers.get(index).label;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择打开闪闪小站的浏览器")
                .setSingleChoiceItems(labels, -1, (dialog, which) -> {
                    BrowserOption browser = browsers.get(which);
                    selectedBrowserPackage = browser.packageName;
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                            .edit()
                            .putString(SELECTED_BROWSER, selectedBrowserPackage)
                            .apply();
                    dialog.dismiss();
                    launchTwa();
                })
                .setNegativeButton("退出", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private List<BrowserOption> findCompatibleBrowsers() {
        PackageManager packageManager = getPackageManager();
        Intent serviceIntent = new Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION);
        List<ResolveInfo> services = packageManager.queryIntentServices(serviceIntent, 0);
        List<BrowserOption> browsers = new ArrayList<>();
        Set<String> seenPackages = new HashSet<>();

        for (ResolveInfo service : services) {
            if (service.serviceInfo == null) continue;
            String packageName = service.serviceInfo.packageName;
            if (!seenPackages.add(packageName)) continue;

            try {
                ApplicationInfo applicationInfo = packageManager.getApplicationInfo(packageName, 0);
                CharSequence applicationLabel = packageManager.getApplicationLabel(applicationInfo);
                String label = applicationLabel == null ? packageName : applicationLabel.toString();
                browsers.add(new BrowserOption(label, packageName));
            } catch (PackageManager.NameNotFoundException ignored) {
                // The package may have been removed while the chooser was being assembled.
            }
        }

        Collections.sort(browsers, new Comparator<BrowserOption>() {
            @Override
            public int compare(BrowserOption first, BrowserOption second) {
                return first.label.compareToIgnoreCase(second.label);
            }
        });
        return browsers;
    }

    private static final class BrowserOption {
        final String label;
        final String packageName;

        BrowserOption(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }
}
