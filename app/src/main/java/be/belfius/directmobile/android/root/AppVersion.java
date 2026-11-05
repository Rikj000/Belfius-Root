package be.belfius.directmobile.android.root;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

import static be.belfius.directmobile.android.root.Hooks.hookMethod;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.logAlways;

/**
 * Resolves the running Belfius version once per process, then hands the profile to whoever installs
 * the hooks.
 *
 * <p>Nothing at package-load time can tell us the version: {@code PackageLoadedParam} exposes no
 * versionCode ({@code ApplicationInfo} carries only the SDK levels), and the app's own names are
 * version-specific — its Application class is {@code o.ben} on v25.2.0, {@code o.bBk} on v25.4.1 and
 * {@code o.fP} on v26.3.0, so the class we would hook to find out differs per version too.
 *
 * <p>{@code Instrumentation.newApplication} breaks that circle: public framework API, hooked like
 * {@code android.os.Debug}, and it runs <em>before</em> the app's {@code attachBaseContext} — which is
 * where the packer injects its dex — while handing us the app's own Context, whose PackageManager
 * knows the versionCode. No reflection, no hidden API.
 */
final class AppVersion {
    /** Called once per process, with the resolved profile. */
    interface OnResolved {
        void run(VersionProfile profile);
    }

    private static final AtomicBoolean warnedUnresolved = new AtomicBoolean();

    private static volatile VersionProfile profile;
    private static volatile long versionCode;
    private static volatile String versionName;

    private AppVersion() {
    }

    /**
     * Installs the one-shot version probe. The lookup happens <em>before</em> {@code proceed()}:
     * creating the Application — and with it the {@code attachBaseContext} chain the packer injects
     * into — happens inside the original call.
     */
    static void installProbe(ClassLoader classLoader, final OnResolved onResolved) {
        try {
            hookMethod(classLoader, "android.app.Instrumentation", "newApplication",
                    new Class<?>[] { ClassLoader.class, String.class, Context.class }, chain -> {
                        resolve((Context) chain.getArg(2), onResolved);
                        return chain.proceed();
                    });
            log("Version probe installed on Instrumentation.newApplication");
        } catch (Throwable t) {
            log("Version probe failed: " + t);
        }
    }

    private static void resolve(Context context, OnResolved onResolved) {
        if (profile != null) return; // a second Application in the same process: keep the first verdict

        VersionProfile resolved;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(BelfiusRoot.BELFIUS_PKG, 0);
            versionCode = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            versionName = info.versionName;
            resolved = VersionProfile.of(versionCode);
        } catch (Throwable t) {
            // Unknown version: run everything rather than risk leaving a detection unhooked.
            log("VERSION lookup failed, running every hook set: " + t);
            resolved = VersionProfile.UNKNOWN;
        }
        profile = resolved;
        // Always-on, like the "hooks armed" verdict: which build is running is the one fact every
        // later log line is read against, and the verification script asserts it with debug off.
        logAlways("BelfiusRoot: VERSION " + describe() + " profile=" + resolved.label());
        if (resolved == VersionProfile.UNKNOWN) {
            logAlways("BelfiusRoot: UNKNOWN-VERSION " + describe()
                    + " - newer than this module knows, running every hook set");
        }
        onResolved.run(resolved);
    }

    /** The resolved profile, or null while the probe has not fired yet. */
    static VersionProfile current() {
        return profile;
    }

    /**
     * For code that can run before the probe has resolved (the class-load watcher sees classes long
     * before {@code newApplication}). The union is the safe answer, and it is announced once instead
     * of being assumed silently.
     */
    static VersionProfile currentOrUnknown() {
        VersionProfile resolved = profile;
        if (resolved != null) return resolved;
        if (warnedUnresolved.compareAndSet(false, true)) {
            logAlways("BelfiusRoot: UNKNOWN-VERSION - version probe has not fired yet,"
                    + " running every hook set");
        }
        return VersionProfile.UNKNOWN;
    }

    /** "v26.3.0 (262601628)", or "unknown" before the probe resolved. */
    static String describe() {
        long code = versionCode;
        if (code == 0) return "unknown";
        String name = versionName;
        return "v" + (name != null ? name : "?") + " (" + code + ")";
    }
}
