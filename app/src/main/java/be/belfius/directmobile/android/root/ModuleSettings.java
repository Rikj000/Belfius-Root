package be.belfius.directmobile.android.root;

import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Single source of truth for the module's user-visible settings.
 *
 * <p>Values live in the framework's <em>remote preferences</em> (libxposed API 102). The module app
 * writes them through the {@link XposedService} the framework hands to it when the app is launched;
 * the hooked Belfius process reads them with {@code XposedInterface.getRemotePreferences("settings")}
 * while the hooks are being installed. The legacy {@code XSharedPreferences} mechanism cannot be
 * used by a module targeting API 102 (and module apps are not hooked by themselves any more).
 *
 * <p>A change therefore only takes effect the next time the Belfius process starts.
 */
public final class ModuleSettings {
    /** The module's own application id (also used for adb toggling). */
    public static final String MODULE_PKG = "be.belfius.directmobile.android.root";
    /** Broadcast action that flips {@link #KEY_DEBUG} from a shell. */
    public static final String ACTION_SET_DEBUG = MODULE_PKG + ".SET_DEBUG";
    /** Remote preferences group. */
    public static final String PREFS = "settings";
    /** Emit the module's diagnostics to logcat. Off by default — see SettingsActivity. */
    public static final String KEY_DEBUG = "debug";

    private static final String TAG = "BelfiusRoot";
    /** The framework sends the service shortly after the module app is launched. */
    private static final long SERVICE_TIMEOUT_MS = 8000;

    private static final Object LOCK = new Object();
    private static volatile boolean registered;
    private static volatile CountDownLatch bound;
    private static volatile XposedService service;

    private ModuleSettings() {
    }

    /**
     * Registers the framework service listener. Must be called from every entry point of the module
     * app (activity, receiver) before touching the settings. Calling it again is a no-op.
     */
    public static void init() {
        synchronized (LOCK) {
            if (registered) return;
            bound = new CountDownLatch(1);
            registered = true;
        }
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService s) {
                service = s;
                CountDownLatch latch = bound;
                if (latch != null) latch.countDown();
                Log.i(TAG, "framework service bound: " + s.getFrameworkName()
                        + " " + s.getFrameworkVersion() + " (api " + s.getApiVersion() + ")");
            }

            @Override
            public void onServiceDied(XposedService s) {
                if (service == s) service = null;
                Log.w(TAG, "framework service died");
            }
        });
    }

    /** Blocks until the framework service is bound, or the timeout elapses. */
    private static XposedService awaitService() throws InterruptedException {
        CountDownLatch latch = bound;
        if (latch != null) latch.await(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        return service;
    }

    /** Reads the stored value, or {@code null} when the framework is unreachable. */
    public static Boolean read() throws InterruptedException {
        XposedService s = awaitService();
        if (s == null) return null;
        return s.getRemotePreferences(PREFS).getBoolean(KEY_DEBUG, false);
    }

    /**
     * Stores the value in the framework's remote preferences.
     *
     * @return {@code false} when the framework service is unreachable or the write failed.
     */
    public static boolean setDebug(boolean enabled) throws InterruptedException {
        XposedService s = awaitService();
        if (s == null) return false;
        // commit(), not apply(): the value must be in the framework database before the Belfius
        // process reads it while installing its hooks.
        return s.getRemotePreferences(PREFS).edit().putBoolean(KEY_DEBUG, enabled).commit();
    }
}
