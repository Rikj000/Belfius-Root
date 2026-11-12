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
    /** Broadcast action that flips {@link #KEY_SCANNER} from a shell. */
    public static final String ACTION_SET_SCANNER = MODULE_PKG + ".SET_SCANNER";
    /** Remote preferences group. */
    public static final String PREFS = "settings";
    /** Emit the module's diagnostics to logcat. Off by default — see MainActivity. */
    public static final String KEY_DEBUG = "debug";
    /**
     * Add the isolated-scanner disarm on top of the per-method bypass. Off by default: off is the
     * behaviour this module has always shipped, so a user who never opens the settings screen keeps
     * the verified bypass, byte for byte.
     */
    public static final String KEY_SCANNER = "scanner";

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

    /**
     * Reads a stored value, or {@code null} when the framework is unreachable.
     */
    public static Boolean read(String key) throws InterruptedException {
        XposedService s = awaitService();
        if (s == null) return null;
        return s.getRemotePreferences(PREFS).getBoolean(key, false);
    }

    /**
     * Identity of the framework this app is talking to, or {@code null} when the service is
     * unreachable (module disabled, not scoped, or simply not bound yet). Used by the About screen;
     * blocks like {@link #read(String)} do.
     */
    public static Framework framework() throws InterruptedException {
        XposedService s = awaitService();
        if (s == null) return null;
        return new Framework(s.getFrameworkName(), s.getFrameworkVersion(), s.getApiVersion());
    }

    /** As the service reports it: {@code name} + {@code version} identify the framework build. */
    public static final class Framework {
        public final String name;
        public final String version;
        public final int apiVersion;

        Framework(String name, String version, int apiVersion) {
            this.name = name;
            this.version = version;
            this.apiVersion = apiVersion;
        }
    }

    /**
     * Stores a value in the framework's remote preferences.
     *
     * @return {@code false} when the framework service is unreachable or the write failed.
     */
    public static boolean write(String key, boolean value) throws InterruptedException {
        XposedService s = awaitService();
        if (s == null) return false;
        // commit(), not apply(): the value must be in the framework database before the Belfius
        // process reads it while installing its hooks.
        return s.getRemotePreferences(PREFS).edit().putBoolean(key, value).commit();
    }
}
