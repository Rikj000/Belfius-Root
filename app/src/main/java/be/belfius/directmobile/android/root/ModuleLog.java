package be.belfius.directmobile.android.root;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Everything the module prints, plus the switch that gates it.
 *
 * <p>There are two kinds of output: {@link #logAlways} lines, which are always emitted (the module's
 * startup verdicts, so a reload can be verified with diagnostics off), and {@link #log} diagnostics,
 * which are a no-op unless {@link #DEBUG} is on.
 *
 * <p>Static on purpose: the hooks call these helpers from deep inside interceptor lambdas, where
 * threading a logger parameter through would drown out the actual hook logic.
 */
final class ModuleLog {
    static final String TAG = "BelfiusRoot";

    /**
     * Diagnostics switch, read once per process from the module's "debug" setting
     * (see {@link ModuleSettings} and {@link SettingsActivity}).
     *
     * <p>Off by default: the diagnostics run inside the packer's audit, which executes on the main
     * thread on some screens, so leaving them on makes navigation feel slow. Gating here only
     * skips the log write — callers that build expensive arguments (stack traces, token dumps) have
     * to guard themselves too, which is why {@link #threadLabel()} returns {@code ""} when off.
     *
     * <p>The functional fixes are never gated by this flag.
     */
    static volatile boolean DEBUG;

    /** This process's framework interface, set from {@code BelfiusRoot.onModuleLoaded}. */
    private static volatile XposedInterface module;

    private ModuleLog() {
    }

    /** Called once per process, before anything is logged or read. */
    static void attach(XposedInterface moduleInstance) {
        module = moduleInstance;
    }

    /**
     * Reads the module's "debug" setting from inside the hooked process. The framework's remote
     * preferences are read-only here, which is all we need — the module app does the writing.
     * Any failure degrades to OFF, the safe default.
     *
     * @return the stored value, or {@code false} when it cannot be read
     */
    static boolean readDebugPref() {
        try {
            SharedPreferences prefs = module.getRemotePreferences(ModuleSettings.PREFS);
            return prefs != null && prefs.getBoolean(ModuleSettings.KEY_DEBUG, false);
        } catch (Throwable t) {
            Log.e(TAG, "readDebugPref failed", t);
            return false;
        }
    }

    /** Always-on log: the startup verdicts and hard failures during installation. */
    static void logAlways(String msg) {
        try {
            XposedInterface m = module;
            if (m != null) m.log(Log.INFO, TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Diagnostic log — no-op unless the debug setting is on. Callers building expensive arguments
     * must check {@link #DEBUG} themselves before reaching this method.
     */
    static void log(String msg) {
        if (!DEBUG) return;
        try {
            XposedInterface m = module;
            if (m != null) m.log(Log.INFO, TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** Diagnostic log of a throwable — no-op unless the debug setting is on. */
    static void log(Throwable t) {
        if (!DEBUG) return;
        try {
            XposedInterface m = module;
            if (m != null) m.log(Log.ERROR, TAG, String.valueOf(t), t);
            else Log.e(TAG, String.valueOf(t), t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Caller label for a diagnostic line. Expensive (full stack capture), so it returns an empty
     * string as soon as diagnostics are off — call sites just concatenate the result unconditionally.
     */
    static String threadLabel() {
        if (!DEBUG) return "";
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder(Thread.currentThread().getName());
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("java.lang") || cn.startsWith("dalvik")
                    || cn.startsWith("be.belfius.directmobile.android.root")
                    || cn.startsWith("io.github.libxposed")
                    || cn.contains("XposedBridge") || cn.startsWith("Vector_")) {
                continue;
            }
            sb.append(" <- ").append(e.getClassName().replaceAll("^.*[./]", "")).append('.')
                    .append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++shown >= 12) break;
        }
        return sb.toString();
    }
}
