package be.belfius.directmobile.android.root;

import java.lang.reflect.Method;

import static be.belfius.directmobile.android.root.Hooks.after;
import static be.belfius.directmobile.android.root.Hooks.findClass;
import static be.belfius.directmobile.android.root.Hooks.hookMethod;
import static be.belfius.directmobile.android.root.ModuleLog.log;

/**
 * Root-detection overrides: the target tables, the "About this app" verdict, and the legacy
 * fallback scan.
 *
 * <p>Every target forces the value an honest, non-rooted, non-emulator, non-debugged device returns.
 * The result is forced rather than observed because the v26.x debugger probes guard their own body
 * and throw when they detect a hook — an after-hook on such a method would never run.
 */
final class DetectionHooks {
    private static final Boolean HAS_ROOT = false;

    private static final Class<?>[] NO_ARGS = new Class<?>[0];
    private static final Class<?>[] OBJECT_ARRAY_ARGS = new Class<?>[] { Object[].class };

    /** Exact detection methods of the known obfuscated classes, verified against the .dex files. */
    private static final Target[] DETECTION_TARGETS = new Target[] {
            // Belfius v26.1.0
            new Target("o.bPE", "INotificationSideChannelStub", NO_ARGS, HAS_ROOT),
            new Target("o.bPE", "cancel", NO_ARGS, HAS_ROOT),
            new Target("o.bPE", "cancelAll", NO_ARGS, HAS_ROOT),
            new Target("o.bPE", "cancel", OBJECT_ARRAY_ARGS, 0),

            // Belfius v26.3.0
            new Target("o.ckg", "IconCompatParcelizer", NO_ARGS, HAS_ROOT),
            new Target("o.ckg", "RemoteActionCompatParcelizer", NO_ARGS, HAS_ROOT),
            new Target("o.ckg", "read", NO_ARGS, HAS_ROOT),
            new Target("o.ckg", "AudioAttributesCompatParcelizer", NO_ARGS, 0)

            // NOTE: "About this app" is hooked separately in hookResolveSizeAndState() so the app's
            // own computed value can be logged before it is overridden.
    };

    /** Method names fixed by Android itself, so they survive any renaming Belfius applies. */
    private static final Target[] PLATFORM_TARGETS = new Target[] {
            new Target("android.os.Debug", "isDebuggerConnected", NO_ARGS, HAS_ROOT),
            new Target("android.os.Debug", "waitingForDebugger", NO_ARGS, HAS_ROOT)
    };

    /** Classes of the legacy single-letter brute force, for versions predating the table above. */
    private static final String[] CLASS_NAMES = new String[] {
            "o.bsN",
            "o.bsS",
            "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
            "be.belfius.android.security.utils.RootUtils$isDeviceRooted$2"
    };

    private static final String[] METHOD_NAMES = new String[] {
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
    };

    private volatile boolean detectionTargetsHooked;
    private volatile boolean resolveSizeReadHooked;
    private volatile boolean resolveSizeIconHooked;

    boolean isTargetsHooked() {
        return detectionTargetsHooked;
    }

    /**
     * Forces the audit's rooted verdict to {@code false}, while still logging what the app computed.
     *
     * <p>This is <b>not</b> a display-only override, however much it looks like one: the same value
     * is read by real logic. Observed on v26.3.0 on 2026-10-29 — with these two hooks observing
     * instead of forcing, a login attempt was refused by the back office with "U kan niet aanmelden
     * omdat dit toestel rooted/jailbroken is" (error
     * {@code MOBILEBANKINGNATAPPFINGERUSAU2UM/1372/1372}). That message is <b>server-supplied</b>
     * (absent from every dex and resource of the APK), so the app had told the bank we are rooted.
     *
     * <p>Callers seen at runtime (temporary stack dump on 2026-10-29; re-add by logging
     * {@code new Throwable().getStackTrace()} inside these two hooks if a version regresses):
     * <ul>
     *   <li>the About text — {@code o.onKeyDown.read()} → {@code o.resolveSize.IconCompatParcelizer()}</li>
     *   <li>a Firebase/AppMeasurement background task → {@code o.clearPendingCredentialRequest.…}</li>
     *   <li>the main thread — {@code o.ModuleInstallStatusUpdate.…} → {@code o.FastJsonResponse.…} →
     *       {@code o.BarcodePersonName.IconCompatParcelizer(:218)} → here</li>
     * </ul>
     *
     * <p>The app's own value stays in the log ({@code app-computed=}), so the screen is not our only
     * signal of whether the audit still sees us.
     */
    void hookResolveSizeAndState(ClassLoader classLoader) {
        if (!resolveSizeReadHooked) {
            try {
                Class<?> cls = findClass("o.resolveSizeAndState", classLoader);
                if (cls != null) {
                    Method m = cls.getDeclaredMethod("read");
                    Hooks.after(m, (chain, result) -> {
                        log("ROOTCHECK o.resolveSizeAndState.read() app-computed="
                                + result + " -> forcing " + HAS_ROOT);
                        return HAS_ROOT;
                    });
                    resolveSizeReadHooked = true;
                    log("Successfully hooked o.resolveSizeAndState.read()!");
                }
            } catch (Throwable ignored) {
            }
        }
        if (!resolveSizeIconHooked) {
            try {
                Class<?> cls = findClass("o.resolveSize", classLoader);
                if (cls != null) {
                    Method m = cls.getDeclaredMethod("IconCompatParcelizer");
                    Hooks.after(m, (chain, result) -> {
                        log("ROOTCHECK o.resolveSize.IconCompatParcelizer() app-computed="
                                + result + " -> forcing " + HAS_ROOT);
                        return HAS_ROOT;
                    });
                    resolveSizeIconHooked = true;
                    log("Successfully hooked o.resolveSize.IconCompatParcelizer()!");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** Hooks every entry of {@link #DETECTION_TARGETS} that resolves on this loader. */
    int hookDetectionTargets(ClassLoader classLoader) {
        int detectionHooks = 0;
        for (Target target : DETECTION_TARGETS) {
            if (hook(classLoader, target)) detectionHooks++;
        }
        if (detectionHooks > 0) {
            detectionTargetsHooked = true;
            log("Hooked " + detectionHooks + " known detection method(s)!");
        }
        return detectionHooks;
    }

    /** Platform hooks first: their names cannot be renamed by the app. */
    void hookPlatformTargets(ClassLoader classLoader) {
        for (Target target : PLATFORM_TARGETS) {
            hook(classLoader, target);
        }
    }

    private boolean hook(ClassLoader classLoader, Target target) {
        try {
            hookMethod(classLoader, target.className, target.methodName, target.parameterTypes,
                    chain -> target.result);

            log("Successfully hooked " + target + "!");
            return true;

        } catch (Throwable ex) {
            log("Skipped " + target + ": " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : ""));
            return false;
        }
    }

    /**
     * Last resort for app versions that predate the exact targets above: hook the first zero-arg
     * method found in the legacy class/name list. Runs only when nothing matched, because on newer
     * versions it risks hooking an unrelated method that happens to be named the same.
     */
    void bruteForceFallback(ClassLoader classLoader) {
        for (String className : CLASS_NAMES) {
            for (String methodName : METHOD_NAMES) {
                try {
                    String methodNameStr = className + "." + methodName + "()";

                    hookMethod(classLoader, className, methodName, NO_ARGS, chain -> {
                        chain.proceed();
                        return HAS_ROOT;
                    });

                    log("Successfully hooked " + methodNameStr + "!");
                    return;

                } catch (Throwable ignored) {}
            }
        }

        log("Failed to hook, no viable method found...");
    }

    /** A single method to force, with its fully resolved parameter types. */
    static final class Target {
        final String className;
        final String methodName;
        final Class<?>[] parameterTypes;
        final Object result;

        Target(String className, String methodName, Class<?>[] parameterTypes, Object result) {
            this.className = className;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.result = result;
        }

        @Override
        public String toString() {
            StringBuilder args = new StringBuilder("(");
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) args.append(", ");
                args.append(parameterTypes[i].getSimpleName());
            }
            return className + "." + methodName + args.append(")").toString();
        }
    }
}
