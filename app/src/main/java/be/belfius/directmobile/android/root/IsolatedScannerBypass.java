package be.belfius.directmobile.android.root;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static be.belfius.directmobile.android.root.Hooks.hookMethod;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.logAlways;

/**
 * Disarms the isolated scanner service Belfius added in v26.3.0: {@code o.cDe}, declared with
 * {@code isolatedProcess="true"} and {@code useAppZygote="true"}, which the app binds at startup so
 * it runs under a one-off UID — outside every root manager's mount filtering — and can report
 * "rooted" home. v26.1.0 has no such service, which is why a downgrade made the error disappear.
 *
 * <p><b>This is additive, not a substitute.</b> It blocks the scanner's <em>report</em>; the packer
 * audit that {@link PackerBypass} normalises measures something else entirely — the hooks this
 * framework installs in the main process, which exist whether or not the scanner ever starts. Two
 * different tripwires, so switching this on never removes a hook from the shipped bypass.
 *
 * <p>Two root-free ways to reach the state {@code pm disable <pkg>/<service>} produces: mutate the
 * app's own component state through {@code PackageManager#setComponentEnabledSetting}, or refuse the
 * request before it reaches the system. This does the latter, which leaves no trace in the app's
 * package state (a RASP check could read the enabled flag back) and is undone the moment the
 * setting is turned off — {@code bindService} returns {@code false} either way.
 *
 * <p><b>Why it is keyed off the manifest flag and not the class name:</b> {@code ServiceInfo#flags}
 * carries {@link ServiceInfo#FLAG_ISOLATED_PROCESS} for any service declared
 * {@code isolatedProcess="true"}, whatever Belfius decides to call it in the next build. The class
 * name is also not enough on its own — {@code o.cDe} is meaningless once the packer re-obfuscates,
 * and this module cannot see into the process anyway: it is forked from the app's <em>own</em>
 * zygote, so no framework hooks it, so the module is never loaded there (verified in logcat:
 * {@code VectorModuleManager} loads only into the main process).
 *
 * <p>The hooks therefore live in the process that <em>makes</em> the request. {@code ContextImpl} is
 * the funnel every Context eventually reaches; {@code ContextWrapper} is the public API the app
 * actually calls and is hooked too, because {@code ContextImpl} is non-SDK and its members may be
 * refused by hidden-API enforcement on some builds.
 */
final class IsolatedScannerBypass {
    /** The module's "scanner" setting, read once per process — see {@link ModuleSettings}. */
    static volatile boolean enabled;

    /** Where a service can be requested from. */
    private static final String[] TARGET_CLASSES = {
            "android.app.ContextImpl",
            "android.content.ContextWrapper"
    };

    private static final Class<?>[] BIND_ARGS = new Class<?>[] {
            Intent.class, ServiceConnection.class, int.class
    };
    private static final Class<?>[] BIND_AS_USER_ARGS = new Class<?>[] {
            Intent.class, ServiceConnection.class, int.class, android.os.UserHandle.class
    };
    private static final Class<?>[] START_ARGS = new Class<?>[] { Intent.class };

    /**
     * What each entry point returns when the service must not run: {@code false} for the two
     * {@code bindService} overloads, {@code null} for the two starters. Not calling
     * {@code chain.proceed()} is what keeps the system from ever creating the process — returning
     * the un-started value rather than an exception matches what a caller sees when the component
     * is genuinely missing, which is the state {@code pm disable} puts it in.
     */
    private static final Site[] SITES = {
            new Site("bindService", BIND_ARGS, Boolean.FALSE),
            new Site("bindServiceAsUser", BIND_AS_USER_ARGS, Boolean.FALSE),
            new Site("startService", START_ARGS, null),
            new Site("startForegroundService", START_ARGS, null)
    };

    /** Per-component result cache: the app binds far more often than it changes its manifest. */
    private final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    private final AtomicBoolean firstBlock = new AtomicBoolean();

    private volatile boolean installed;

    /** Reads the setting alongside the debug toggle, before any hook can need it. */
    static void readSetting() {
        enabled = ModuleLog.readPref(ModuleSettings.KEY_SCANNER);
    }

    /**
     * Whether this build should get the scanner disarm <em>in addition to</em> its normal hook set.
     * Only the builds that actually ship a scanner qualify: on {@link VersionProfile#V26_1} and
     * {@link VersionProfile#LEGACY} there is nothing to disarm, so the setting is a no-op there and
     * {@code BelfiusHooks} says so on the verdict line.
     */
    boolean isActiveFor(VersionProfile profile) {
        return enabled && profile.hasIsolatedScanner();
    }

    boolean isInstalled() {
        return installed;
    }

    /** Installs the block. Idempotent; called from {@code BelfiusHooks.installFor}. */
    void install(ClassLoader classLoader) {
        if (installed) return;
        installed = true;

        StringBuilder landed = new StringBuilder();
        int hooked = 0;
        for (String className : TARGET_CLASSES) {
            int perClass = 0;
            for (Site site : SITES) {
                if (hookSite(classLoader, className, site)) perClass++;
            }
            if (perClass > 0) {
                if (hooked > 0) landed.append(", ");
                landed.append(className).append('=').append(perClass);
                hooked += perClass;
            }
        }

        // Always-on: which surface accepted the hooks is the one fact the "scanner process gone"
        // observation below is read against, so it has to show up with diagnostics off.
        if (hooked > 0) {
            logAlways("BelfiusRoot: SCANNER-DISARM armed - " + hooked + " service-start hook(s) on "
                    + landed);
        } else {
            logAlways("BelfiusRoot: SCANNER-DISARM FAILED - no service-start method could be"
                    + " hooked, the isolated scanner will still run!");
        }
    }

    private boolean hookSite(ClassLoader classLoader, String className, final Site site) {
        try {
            hookMethod(classLoader, className, site.methodName, site.parameterTypes, chain -> {
                String target = scannerTarget((Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                if (target == null) return chain.proceed();
                report(site.methodName, target);
                return site.blockedResult;
            });
            log("SCANNER-DISARM hooked " + className + "#" + site.methodName);
            return true;
        } catch (Throwable t) {
            log("SCANNER-DISARM skipped " + className + "#" + site.methodName + ": " + t);
            return false;
        }
    }

    /**
     * The component to withhold, formatted as {@code package/class}, or {@code null} when this
     * request may proceed. Only the app's own services are ever inspected — anything else is
     * answered by the package name alone, before any binder call, because a bind to another
     * package is by definition not our scanner.
     */
    private String scannerTarget(Context context, Intent intent) {
        if (context == null || intent == null) return null;

        ComponentName component = intent.getComponent();
        if (component != null) {
            if (!BelfiusRoot.BELFIUS_PKG.equals(component.getPackageName())) return null;
            String key = component.flattenToString();
            return isolated(context, key, component) ? key : null;
        }

        String pkg = intent.getPackage();
        if (pkg != null && !BelfiusRoot.BELFIUS_PKG.equals(pkg)) return null;
        try {
            ResolveInfo resolved = context.getPackageManager().resolveService(intent, 0);
            ServiceInfo info = resolved == null ? null : resolved.serviceInfo;
            if (info == null || !BelfiusRoot.BELFIUS_PKG.equals(info.packageName)) return null;
            boolean hasFlag = (info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
            String key = info.packageName + "/" + info.name;
            seen.put(key, hasFlag);
            return hasFlag ? key : null;
        } catch (Throwable t) {
            log("SCANNER-DISARM could not resolve " + intent + ": " + t);
            return null;
        }
    }

    private boolean isolated(Context context, String key, ComponentName component) {
        Boolean cached = seen.get(key);
        if (cached != null) return cached;
        boolean hasFlag = false;
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
            hasFlag = (info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0;
        } catch (Throwable t) {
            log("SCANNER-DISARM could not read " + key + ": " + t);
        }
        seen.put(key, hasFlag);
        return hasFlag;
    }

    /** The first block is a verdict line, so a reload can be verified with diagnostics off. */
    private void report(String method, String target) {
        String line = "SCANNER-BLOCK " + method + " -> " + target
                + " (isolatedProcess) - not started";
        if (firstBlock.compareAndSet(false, true)) logAlways("BelfiusRoot: " + line);
        else log(line);
    }

    /** One requested entry point: name, resolved types, and what a blocked call returns. */
    private static final class Site {
        final String methodName;
        final Class<?>[] parameterTypes;
        final Object blockedResult;

        Site(String methodName, Class<?>[] parameterTypes, Object blockedResult) {
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.blockedResult = blockedResult;
        }
    }
}
