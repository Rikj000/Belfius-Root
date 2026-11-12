package be.belfius.directmobile.android.root;

import android.content.Context;

import java.lang.reflect.Member;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static be.belfius.directmobile.android.root.Hooks.hookMethod;
import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.logAlways;

/**
 * Installs everything the Belfius package needs, in the order the app makes it possible, and keeps
 * it installed: the real app and its audit dex are injected into a classloader that does not exist
 * yet when {@code onPackageLoaded} runs, so most of this class is about waiting for those classes to
 * appear and hooking them before any of their methods can execute.
 *
 * <p>Order: the version-independent hooks (platform targets, the class-load watcher), then the
 * version probe — and once it knows which Belfius build is running, the version-specific set chosen
 * by {@link VersionProfile}: the detection targets, the packer/RASP bypass, and the runtime watchers
 * for {@code attachBaseContext} and the resolver that reveals the injected loader.
 */
final class BelfiusHooks {
    private final RaspGuard guard = new RaspGuard();
    private final PackerBypass packerBypass = new PackerBypass(guard);
    private final DetectionHooks detection = new DetectionHooks();
    private final UiIntegrityHooks uiIntegrity = new UiIntegrityHooks();
    private final ErrorDiagnostics errorDiagnostics = new ErrorDiagnostics(guard);
    private final IsolatedScannerBypass scanner = new IsolatedScannerBypass();

    /** Remaining CLASSLOAD lines to print before the watcher goes quiet (the app loads o.* en masse). */
    private final AtomicInteger classLoadLogs = new AtomicInteger(200);
    /** Resolver handles already logged — the audit resolves the same members in a hot loop. */
    private final Map<Integer, String> resolverSeen = new ConcurrentHashMap<>();

    void install(ClassLoader classLoader, String processName) {
        // Framework names survive every Belfius build, so these need no version.
        detection.hookPlatformTargets(classLoader);
        // It waits for classes that only exist after injection, and acts only when the
        // resolved profile wants them — so a legacy build merely pays for the hook, not the work.
        installClassLoadWatcher(classLoader);
        // Everything else is version-specific and is installed from the resolved profile, before the
        // app's attachBaseContext chain gives the packer a chance to run.
        AppVersion.installProbe(classLoader,
                profile -> installFor(classLoader, profile, processName));
    }

    /**
     * Installs the version-specific set, chosen by {@link VersionProfile}: a legacy build gets the
     * original brute-force scan and none of the v26.x hooks, while a modern build gets the detection
     * targets, the packer/RASP bypass and the diagnostics.
     *
     * <p>The module's "scanner" setting is <b>additive</b>: on a build that ships the isolated
     * scanner service, switching it on adds the service-start block <em>on top of</em> this set
     * instead of replacing it. The two defend different tripwires — the scanner reports the root
     * environment from a process this module is never loaded into, while the packer audit measures
     * the hooks this framework installs here — so one can never stand in for the other. The setting
     * is off by default, which is what keeps this method's behaviour unchanged.
     */
    private void installFor(ClassLoader classLoader, VersionProfile profile, String processName) {
        // Installed first: the app binds the scanner during startup, before any packer class is
        // requested, and this hook set is what keeps that bind from reaching the system.
        boolean scannerDisarmed = scanner.isActiveFor(profile);
        if (scannerDisarmed) {
            scanner.install(classLoader);
        }

        int detectionHooks = 0;
        if (profile.wantsModernHooks()) {
            detectionHooks = detection.hookDetectionTargets(classLoader);

            packerBypass.install(classLoader);
            errorDiagnostics.hookErrorDiagnostics(classLoader);
            // The generic-error throw site lives in classes3.dex (base loader): observe it directly so
            // the guard's inputs are logged even if the exception never reaches o.getInstance.
            guard.hookGuardThrowSites(classLoader, "o.aYE", "setVisibility");

            installAttachSwallow(classLoader);
            installResolverWatcher(classLoader);
            installGuardBuilderWatcher(classLoader);
        } else {
            log("Legacy build (" + AppVersion.describe() + "): v26.x hook set skipped,"
                    + " brute-force scan only");
        }

        // The one-off verdict: the module's only always-on output besides the audit line. Emitted
        // once, in the app's main process, so a multi-process app does not repeat it. "audit=pending"
        // is normal here — the injected audit loader appears later, which is when the second line is
        // printed. "scanner=" states what the additive switch added, so a reload can be checked
        // for it without a second line.
        if (processName == null || processName.equals(BelfiusRoot.BELFIUS_PKG)) {
            logAlways("BelfiusRoot: hooks armed for " + BelfiusRoot.BELFIUS_PKG
                    + " (detection=" + detectionHooks
                    + ", audit=" + (packerBypass.isInstalled() ? "installed" : "pending")
                    + ", debug=" + (DEBUG ? "on" : "off")
                    + ", scanner=" + (scannerDisarmed ? "disarmed" : "off") + ")");
        }

        // The setting was asked for on a build with nothing to disarm. Say so on the verdict line
        // rather than leaving the user wondering why SCANNER-DISARM never appears.
        if (IsolatedScannerBypass.enabled && !profile.hasIsolatedScanner()) {
            logAlways("BelfiusRoot: isolated-scanner bypass requested but profile="
                    + profile.label() + " ships no isolated scanner service - standard hook set"
                    + " applied unchanged");
        }

        if (detectionHooks > 0) {
            log("Hooked " + detectionHooks + " known detection method(s), skipping fallback scan!");
            return;
        }
        detection.bruteForceFallback(classLoader);
    }

    /**
     * The whole real app — main code plus the anti-Xposed audit dex — is injected inside
     * {@code attachBaseContext}, after this callback returns. Watch class loads: the moment a packer
     * class is requested, install its hooks before any of its methods can run.
     */
    private void installClassLoadWatcher(final ClassLoader classLoader) {
        try {
            final AtomicBoolean installing = new AtomicBoolean();
            hookMethod(classLoader, "java.lang.ClassLoader", "loadClass",
                    new Class<?>[] { String.class }, chain -> {
                        Object loadResult = chain.proceed();
                        if (installing.get()) return loadResult;
                        String name = (String) chain.getArg(0);
                        if (name == null) return loadResult;
                        if (name.startsWith("o.")) {
                            VersionProfile profile = AppVersion.current();
                            if (profile == null) {
                                // The app's own Application class is requested before the version
                                // probe resolves, so wait for it instead of guessing. Only a packer
                                // class can legitimately appear this early — and that means the probe
                                // never fired, in which case the union is the only safe answer.
                                if (!isPackerClass(name)) return loadResult;
                                profile = AppVersion.currentOrUnknown();
                            }
                            // A legacy build has nothing injected to wait for, and its own o.* classes
                            // collide with the names dispatched below (o.ckg, o.bII and o.bBF are all
                            // ordinary classes there) — so it keeps the hook but neither logs nor acts.
                            if (!profile.wantsModernHooks()) return loadResult;
                            if (classLoadLogs.getAndDecrement() > 0) {
                                log("CLASSLOAD " + name + " via " + chain.getThisObject());
                            } else {
                                log("...further class loads suppressed");
                            }
                        }
                        if (isPackerClass(name)) {
                            installing.set(true);
                            try {
                                packerBypass.install(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.ckg") && !detection.isTargetsHooked()) {
                            installing.set(true);
                            try {
                                detection.hookDetectionTargets(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.getInstance")) {
                            installing.set(true);
                            try {
                                errorDiagnostics.hookErrorDiagnostics(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.aYE")) {
                            installing.set(true);
                            try {
                                guard.hookGuardThrowSites(classLoader, "o.aYE", "setVisibility");
                            } finally {
                                installing.set(false);
                            }
                        } else if (!uiIntegrity.isArmed()
                                && (name.startsWith("o.bII") || name.startsWith("o.zbxt")
                                        || name.startsWith("o.bDV") || name.startsWith("o.bFe")
                                        || name.startsWith("o.bEV") || name.startsWith("o.bIT"))) {
                            installing.set(true);
                            try {
                                uiIntegrity.hookUiIntegrityDigger(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        }
                        return loadResult;
                    });
            log("Classloader watcher installed.");
        } catch (Throwable t) {
            log("Classloader watcher failed: " + t);
        }
    }

    /** Classes only the injected audit dex provides: seeing one means the packer is live. */
    private static boolean isPackerClass(String name) {
        return name.startsWith("o.cBt") || name.startsWith("o.cBv") || name.startsWith("o.cCB");
    }

    /**
     * The audit runs inside {@code Application.attachBaseContext}, so absorbing its RuntimeException
     * here stops it reaching ActivityThread and aborting Application instantiation — no need to
     * touch the injected dex to prevent the crash.
     */
    private void installAttachSwallow(final ClassLoader classLoader) {
        String[] attachChain = { "o.fP", "o.lS", "o.zbtp" };
        for (final String className : attachChain) {
            try {
                hookMethod(classLoader, className, "attachBaseContext",
                        new Class<?>[] { Context.class }, chain -> {
                            log(">> attachBaseContext " + className + " on thread "
                                    + Thread.currentThread().getName());
                            Object result = null;
                            Throwable thrown = null;
                            try {
                                result = chain.proceed();
                            } catch (Throwable e) {
                                thrown = e;
                            }
                            if (thrown != null) {
                                log("FAIL " + className + ".attachBaseContext threw: " + thrown);
                                log(thrown);
                                return null;
                            } else {
                                log("OK  " + className + ".attachBaseContext completed");
                                if (!packerBypass.isInstalled()) {
                                    packerBypass.install(classLoader);
                                }
                            }
                            return result;
                        });
                log("Attach swallow installed on " + className);
            } catch (Throwable t) {
                log("Attach swallow skipped " + className + ": " + t);
            }
        }
    }

    /**
     * The resolver is how the injected loader finally becomes reachable: it hands back the members
     * the audit reflects over, and those members' declaring class lives in that loader. Grab it and
     * install the rest of the hooks against it directly (belt and braces on top of the attach
     * swallow).
     */
    private void installResolverWatcher(final ClassLoader classLoader) {
        try {
            hookMethod(classLoader, "o.dispatchWindowInsetsAnimationProgress", "read",
                    new Class<?>[] { int.class }, chain -> {
                        Object result = chain.proceed();
                        int guardHandle = ((Integer) chain.getArg(0)).intValue();
                        guard.observeResolverRead(guardHandle, result);

                        if (result instanceof Member) {
                            Member member = (Member) result;
                            Class<?> dc = member.getDeclaringClass();
                            Integer handle = (Integer) chain.getArg(0);
                            ClassLoader loader = dc.getClassLoader();
                            if (DEBUG && resolverSeen.putIfAbsent(handle,
                                    dc.getName() + "." + member) == null) {
                                log("RESOLVER read(" + handle + ") FIRST -> " + dc + "."
                                        + member + " via " + loader);
                                log("RESOLVER caller -> " + new Exception("resolver-first"));
                            }
                            if (loader != null) {
                                if (!packerBypass.isInstalled()) {
                                    packerBypass.install(loader);
                                }
                                detection.hookResolveSizeAndState(loader);
                                errorDiagnostics.hookErrorDiagnostics(loader);
                                if (!detection.isTargetsHooked()) {
                                    detection.hookDetectionTargets(loader);
                                }
                                if (!uiIntegrity.isArmed()) {
                                    uiIntegrity.hookUiIntegrityDigger(loader);
                                }
                            }
                        } else {
                            log("RESOLVER read(" + chain.getArg(0) + ") -> " + result);
                        }
                        return result;
                    });
            log("Resolver watcher installed.");
        } catch (Throwable t) {
            log("Resolver watcher failed: " + t);
        }
    }

    /** The resolver's builder path: {@code read(id)} misses, then resolves class + member from a key. */
    private void installGuardBuilderWatcher(final ClassLoader classLoader) {
        try {
            hookMethod(classLoader, "o.dispatchWindowInsetsAnimationProgress", "IconCompatParcelizer",
                    new Class<?>[] { char.class, int.class, int.class, int.class, boolean.class,
                            String.class, Class[].class },
                    chain -> {
                        Object result = chain.proceed();
                        Object k = chain.getArg(3);
                        if (!(k instanceof Integer)) return result;
                        int key = ((Integer) k).intValue();
                        if (!RaspGuard.isGuardBuildKey(key)) return result;
                        guard.observeBuilder(key, chain.getArg(5), result);
                        return result;
                    });
            log("Guard-field builder watcher installed.");
        } catch (Throwable t) {
            log("Guard-field builder watcher failed: " + t);
        }
    }
}
