package help.shimmer.app;

import android.app.ActivityManager;
import android.os.Build;
import android.os.Process;

import org.mozilla.geckoview.GeckoRuntime;

import java.util.List;

public final class Application extends android.app.Application {
    private volatile GeckoRuntime geckoRuntime;

    @Override
    public void onCreate() {
        super.onCreate();
        if (isMainProcess()) {
            // Gecko registers a ProcessLifecycleOwner observer during create().
            // Start it before the first Activity reaches ON_RESUME, while never
            // creating a parent runtime inside Gecko's :tab* child processes.
            geckoRuntime = GeckoRuntime.create(this);
        }
    }

    synchronized GeckoRuntime getGeckoRuntime() {
        if (geckoRuntime == null) {
            if (!isMainProcess()) {
                throw new IllegalStateException("GeckoRuntime requested outside the main process");
            }
            geckoRuntime = GeckoRuntime.create(this);
        }
        return geckoRuntime;
    }

    private boolean isMainProcess() {
        String processName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            processName = android.app.Application.getProcessName();
        } else {
            processName = null;
            ActivityManager manager = getSystemService(ActivityManager.class);
            List<ActivityManager.RunningAppProcessInfo> processes =
                    manager == null ? null : manager.getRunningAppProcesses();
            if (processes != null) {
                int pid = Process.myPid();
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == pid) {
                        processName = process.processName;
                        break;
                    }
                }
            }
        }
        return getPackageName().equals(processName);
    }
}
