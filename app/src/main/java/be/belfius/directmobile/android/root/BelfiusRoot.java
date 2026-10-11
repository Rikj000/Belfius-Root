package be.belfius.directmobile.android.root;

import static de.robv.android.xposed.XposedBridge.log;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** @noinspection unused*/
public class BelfiusRoot implements IXposedHookLoadPackage {
    private static final String BELFIUS_PKG = "be.belfius.directmobile.android";
    private static final Boolean HAS_ROOT = false;

    private volatile boolean packerBypassInstalled;
    private final java.util.concurrent.atomic.AtomicBoolean uiIntegrityDiggerArmed = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean detectionTargetsHooked;
    private final AtomicInteger classLoadLogs = new AtomicInteger(200);
    private final Map<Integer, String> resolverSeen = new ConcurrentHashMap<>();

    private static final Class<?>[] NO_ARGS = new Class<?>[0];
    private static final Class<?>[] OBJECT_ARRAY_ARGS = new Class<?>[] { Object[].class };

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

    /**
     * Exact detection methods of the known obfuscated classes, verified against the .dex files.
     * The forced result is the value an honest, non-rooted, non-emulator, non-debugged device returns.
     */
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
    };

    /**
     * Belfius v26.3.0 runs an anti-Xposed audit (o.cBt / o.cBv / o.cCB) from a dex it injects into
     * the app mid-attachBaseContext. When any Xposed module is injected the audit perturbs its
     * token and o.cBv.write throws RuntimeException, killing the process.
     * These hooks neutralise the throw points; the statics cBv.write would have set are mirrored so
     * the audit looks byte-identical to a clean run. Many of the classes only exist after the dex
     * is injected, so this is (re)run from the classloader watcher.
     */
    private void hookPackerBypass(ClassLoader classLoader) {
        if (packerBypassInstalled) return;
        Class<?> cBt;
        Class<?> cBv;
        try {
            cBt = XposedHelpers.findClass("o.cBt", classLoader);
            cBv = XposedHelpers.findClass("o.cBv", classLoader);
        } catch (Throwable t) {
            log("Packer bypass deferred, class not loadable yet: " + t);
            return;
        }

        // o.cBv.write throws RuntimeException(String.valueOf(i2)) when the audit's token is
        // corrupted by a detected tamper flag, where objArr[1][0] (actual) differs from
        // objArr[0][0] (expected). Normalize the actual to the expected BEFORE the body runs so the
        // audit completes byte-identical to a clean run: no throw, no corrupted token, every static
        // the app relies on set naturally. write is void; suppressing the exception afterwards via
        // setResult is not reliable, so correct at the decision point instead.
        // Deep instrumentation: dump the whole token contract before AND after so we can see which
        // slot / thread carries the fingerprint that later becomes a byte[] size.
        XC_MethodHook writeHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object arg = param.args[0];
                log("TOKEN write(before) on " + threadLabel() + " " + tokenDump((Object[]) arg));
                if (arg instanceof Object[] && ((Object[]) arg).length > 1) {
                    Object[] arr = (Object[]) arg;
                    if (arr[0] instanceof int[] && arr[1] instanceof int[]) {
                        int[] expected = (int[]) arr[0];
                        int[] actual = (int[]) arr[1];
                        if (expected.length > 0 && actual.length > 0 && actual[0] != expected[0]) {
                            log("TOKEN write tamper flag i2=" + actual[0] + " normalized to i=" + expected[0] + " on " + threadLabel());
                            actual[0] = expected[0];
                        }
                    }
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[0] instanceof Object[]) {
                    log("TOKEN write(after) on " + threadLabel() + " " + tokenDump((Object[]) param.args[0])
                            + " (throwable=" + (param.getThrowable() != null) + ")");
                }
            }
        };
        hookAllMethodsNamed(cBv, "write", writeHook);

        // o.col$write.RemoteActionCompatParcelizer(cov$read) is a self-validating gate over the
        // persisted audit record. On mismatch it runs a DELIBERATE kill block (new int[v4] where v4
        // is the measured static "IconCompatParcelizer" int -> NegativeArraySizeException / OOM, then
        // an always-failing aget[-1]). The only safe exit is the compare v7==v2, which then just
        // stores the cov$read into this.AudioAttributesImplApi21Parcelizer and returns this.
        // The statics are written DURING the audit, i.e. before our write-token hook runs, so the
        // firebase/late replay of the record always faults. Replicate the safe branch in before:
        // set the field, return this, and let the kill block never execute on any thread.
        try {
            final Class<?> colWrite = XposedHelpers.findClass("o.col$write", classLoader);
            final Field field = XposedHelpers.findField(colWrite, "AudioAttributesImplApi21Parcelizer");
            hookAllMethodsNamed(colWrite, "RemoteActionCompatParcelizer", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    boolean covReadOverload = param.args != null && param.args.length == 1
                            && param.args[0] != null
                            && "o.cov$read".equals(param.args[0].getClass().getName());
                    StringBuilder sb = new StringBuilder("COLWRITE before on " + threadLabel()
                            + " m=" + param.method.getName()
                            + (isStatic((java.lang.reflect.Method) param.method) ? " (static)" : " (instance)")
                            + " this=" + shortVal(param.thisObject));
                    for (int i = 0; i < param.args.length; i++) {
                        sb.append(" arg").append(i).append('=').append(param.args[i] == null
                                ? "null" : param.args[i].getClass().getSimpleName() + "@"
                                + System.identityHashCode(param.args[i]));
                    }
                    log(sb.toString());
                    if (!covReadOverload) return;
                    Object thisObject = param.thisObject;
                    Object covRead = param.args[0];
                    field.set(thisObject, covRead);
                    log("COLWRITE NEUTRALIZED on " + threadLabel() + " set this="
                            + shortVal(thisObject) + " AudioAttributesImplApi21Parcelizer="
                            + covRead.getClass().getSimpleName() + "@" + System.identityHashCode(covRead)
                            + " (safe branch replicated, kill block skipped)");
                    param.setResult(thisObject);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    log("COLWRITE after on " + threadLabel() + " m="
                            + ((java.lang.reflect.Method) param.method).getName()
                            + " res=" + shortVal(param.getResult()) + " thr=" + param.getThrowable());
                }
            });
        } catch (Throwable t) {
            log("o.col$write neutralizer skipped: " + t);
        }

        // The audit may call write through the o.cBt$RemoteActionCompatParcelizer interface on an
        // implementation other than o.cBv. The interface method itself is abstract (Vector cannot
        // hook it); log its declaration for the record.
        String[] innerNames = { "o.cBt$RemoteActionCompatParcelizer", "o.cBt$AudioAttributesCompatParcelizer" };
        for (String innerName : innerNames) {
            try {
                Class<?> iface = XposedHelpers.findClass(innerName, classLoader);
                log("write interface candidate " + innerName + " isInterface=" + iface.isInterface());
            } catch (Throwable t) {
                log("Interface write hook skipped " + innerName + ": " + t);
            }
        }

        packerBypassInstalled = true;
        log("Packer anti-Xposed audit disabled (o.cBt / o.cBv)!");

        // The anomaly FINDER is o.cBt.read:1149 which returns Obj[2]{int[1]{measured}, String[]{signature}}.
        // The clean/no-anomaly encoding is String[0]{} (seen at cBt.write:760). The only divergence our
        // instrumentation causes is "private void android.app.ActivityThread.attach(boolean,long)"
        // (its metadata is a few bytes different due to backpatching). The measured int AND the signature
        // are captured into a persisted audit record BEFORE write runs; normalizing in write is too late.
        // The firebase worker later replays the record and uses the stored int as a byte[] length, which
        // dies as OOM / NegativeArraySizeException (o.col$write.RemoteActionCompatParcelizer(:224)).
        // Fix at the source: when a finding is flagged (non-empty signature list), wipe BOTH the signature
        // list AND the measured int so the stored record is indistinguishable from a clean run.
        hookAllMethodsNamed(cBt, "read", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object res = param.getResult();
                if (!(res instanceof Object[])) return;
                Object[] o = (Object[]) res;
                if (o.length < 2 || !(o[1] instanceof String[])) return;
                String[] strs = (String[]) o[1];
                if (strs.length == 0) return;
                log("TOKEN read finding stripped on " + threadLabel()
                        + " was=" + shortVal(o[1]) + " int=" + shortVal(o[0]));
                o[1] = new String[0];
                // NOTE: do NOT zero the measured int (o[0][0]). Retained its value causes the
                // col$write:224 kill block to be ENTERED, but that path is now neutralized by the
                // safe-branch replica above. Zeroing it instead breaks a later legit use: the app
                // divides by this token value during UI layout (o.bII.hashCode(:132)) and dies with
                // ArithmeticException: divide by zero. The finder int is also shared with the
                // write token's expected slot, so zeroing here corrupts both.
            }
        });

        // Which slot becomes the crash byte[] length? Log every int the stream (/cov$read, on-disk
        // classes5.dex) produces on firebase threads so we can pin the producer and confirm the fix.
        try {
            Class<?> covRead = XposedHelpers.findClass("o.cov$read", classLoader);
            hookAllMethodsNamed(covRead, "read", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.getResult() instanceof Number) {
                        log("COVREAD int on " + threadLabel() + " = " + param.getResult());
                    }
                }
            });
        } catch (Throwable t) {
            log("cov$read observer skipped: " + t);
        }

        // NOTE: the per-call before/after observer on cBt.AudioAttributesCompatParcelizer /
        // IconCompatParcelizer / RemoteActionCompatParcelizer / read (the scan loop at cBt.read:899
        // calls cBt.IconCompatParcelizer thousands of times) produced the exact token shapes we
        // needed to decode the audit (see session doc §14) but floods the daemon log and rotates the
        // startup lines out within seconds. Removed now that the pipeline is understood; the
        // bypass-proof logging above (TOKEN write / finding stripped) is kept.
    }

    // The divide-by-zero at o.bII.hashCode(:132) fires on the main thread during UI layout
    // (o.zbxt$RemoteActionCompatParcelizer.onGlobalLayout -> o.zbxt.setImageHeaderDefinition ->
    // o.bDV.AudioAttributesCompatParcelizer -> ... -> o.bFe.AudioAttributesCompatParcelizer:23
    // (HashMap.put) -> o.bII.hashCode). This is a SECOND integrity layer reached only now that the
    // firebase OOM is neutralized, and its classes are not present in any carved dex. Instrument
    // it: dump every field of the bII object (looking for the 0 divisor) and the HashMap builder.
    // CRITICAL: this installer is re-entered from the resolver hot loop thousands of times per
    // second during o.zbtp.attachBaseContext. Guard with an AtomicBoolean set BEFORE any work so
    // hooks are installed exactly once (each duplicate install leaks a JNI global ref, and a prior
    // flag-only guard aborted the app with "global reference table overflow (max=51200)").
    private void hookUiIntegrityDigger(ClassLoader classLoader) {
        // CRITICAL: re-entrancy guard. The resolver after-hook fires on EVERY native-bridge
        // resolution in the audit's hot loop; while the first install is still in flight, installing
        // more hooks triggers further resolutions which re-enter this installer -> duplicate hooks ->
        // JNI global-ref leak -> SIGABRT ("global reference table overflow (max=51200)").
        // CAS at entry so only a single install ever runs. If nothing could be hooked (classes not
        // loadable on this loader yet), release the guard so a later call from the injected loader
        // still installs exactly once.
        if (!uiIntegrityDiggerArmed.compareAndSet(false, true)) return;
        boolean hookedAny = false;

        // Login-path crash (NPE at o.bBF.newThread:80): inflating the login layout
        // (o.addFocusables view) builds an animator ThreadPoolExecutor (o.byu) whose
        // ThreadFactory o.bBF throws NPE on the first newThread call, killing main.
        try {
            Class<?> bbf = XposedHelpers.findClass("o.bBF", classLoader);
            int h = hookAllMethodsNamed(bbf, "newThread", new XC_MethodHook() {
                private final java.util.concurrent.atomic.AtomicBoolean once =
                        new java.util.concurrent.atomic.AtomicBoolean();
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.getThrowable() instanceof NullPointerException && param.args[0] instanceof Runnable) {
                        log("LOGIN-THREADFACTORY NEUTRALIZED on " + threadLabel()
                                + " -** substituting plain Thread");
                        param.setResult(new Thread((Runnable) param.args[0]));
                    }
                }
            });
            hookedAny |= h > 0;
            log("UI-integrity digger on o.bBF newThread (" + h + " hooks)");
        } catch (Throwable t) {
            log("UI-integrity digger skipped o.bBF: " + t);
        }

        // Root-cause arrived at (see session doc §17): the packer injected an audit into Lottie's
        // ThreadFactory o.bBF that deliberately throws NPE (bare `throw null`) on its own factory
        // statics check; the catch-and-substitute above is the permanent fix. No Thread-<init>
        // instrumentation is needed. (Removed in the 2026-10-08 A/B round: the Thread-ctor hooks
        // desynced the app's lifecycle counter machine and caused the divide-by-zero at
        // setShowDividerHorizontal.onCreate.)

        // only log the per-install summary once per distinct loader
        String[] targets = { "o.bII", "o.bFe", "o.bDV", "o.bEV", "o.bIT" };
        for (String className : targets) {
            try {
                Class<?> c = XposedHelpers.findClass(className, classLoader);
                int h = hookAllMethodsNamed(c, "hashCode", new XC_MethodHook() {
                    private final java.util.concurrent.atomic.AtomicBoolean once =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (once.compareAndSet(false, true)) {
                            log("UI-INTEGRITY hashCode(this) on " + threadLabel() + " -> "
                                    + dumpFields(param.thisObject));
                        }
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // o.bII.hashCode:132 divides by a zero field on this build and aborts the
                        // main thread during UI layout. The original throw is an intentional gate:
                        // consume it and supply a stable identity-based hash so layout survives.
                        if (param.getThrowable() instanceof ArithmeticException) {
                            log("UI-INTEGRITY hashCode NEUTRALIZED on " + threadLabel());
                            param.setResult(System.identityHashCode(param.thisObject) * 31);
                        }
                    }
                });
                hookedAny |= h > 0;
                log("UI-integrity digger on " + className + " hashCode (" + h + " hooks)");
                int hb = hookAllMethodsNamed(c, "AudioAttributesCompatParcelizer", new XC_MethodHook() {
                    private final java.util.concurrent.atomic.AtomicBoolean once =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (once.compareAndSet(false, true)) {
                            log("UI-INTEGRITY " + c.getSimpleName()
                                    + ".AudioAttributesCompatParcelizer on " + threadLabel()
                                    + " this=" + dumpFields(param.thisObject));
                        }
                    }
                });
                hookedAny |= hb > 0;
                log("UI-integrity digger on " + className + " AudioAttributesCompatParcelizer (" + hb + " hooks)");
            } catch (Throwable t) {
                log("UI-integrity digger skipped " + className + ": " + t);
            }
        }
        if (!hookedAny) {
            uiIntegrityDiggerArmed.set(false);
            log("UI-integrity digger: nothing hooked yet on " + classLoader + ", will retry");
        } else {
            log("UI-integrity digger ARMED (hooked something)");
        }
    }

    /** Reflectively dump all fields of an object (class + each inherited class). */
    private static String dumpFields(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getName()).append("@")
                .append(Integer.toHexString(System.identityHashCode(o)));
        try {
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        sb.append("\n  ").append(k.getSimpleName()).append('.').append(f.getName())
                                .append(" (").append(f.getType().getSimpleName()).append(") = ")
                                .append(fmtVal(v));
                    } catch (Throwable t) {
                        sb.append("\n  ").append(k.getSimpleName()).append('.').append(f.getName())
                                .append(" = <err ").append(t).append('>');
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("\n  <dump failed: ").append(t).append('>');
        }
        return sb.toString();
    }

    /** Superclass chain — kept minimal; the run-B superclassNameChain/dumpStatics/methodNames/
     *  stackTraceToString helpers were removed after the A/B test identified them as the
     *  desync trigger for setShowDividerHorizontal.onCreate's lifecycle counters. */

    private static String fmtVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return v.toString();
        if (v instanceof String) return "\"" + v + "\"";
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            StringBuilder sb = new StringBuilder().append(v.getClass().getSimpleName())
                    .append('[').append(n).append("]{");
            for (int i = 0; i < Math.min(n, 16); i++) {
                if (i > 0) sb.append(',');
                sb.append(fmtVal(java.lang.reflect.Array.get(v, i)));
            }
            return sb.append('}').toString();
        }
        return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }

    private static boolean isStatic(Method m) {
        return Modifier.isStatic(m.getModifiers());
    }
    private static String threadLabel() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder(Thread.currentThread().getName());
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("java.lang") || cn.startsWith("dalvik") || cn.startsWith("be.belfius.directmobile.android.root")
                    || cn.startsWith("de.robv") || cn.contains("XposedBridge") || cn.startsWith("Vector_")) {
                continue;
            }
            sb.append(" <- ").append(e.getClassName().replaceAll("^.*[./]", "")).append('.')
                    .append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++shown >= 12) break;
        }
        return sb.toString();
    }

    /** Compact readable forms for variadic args. */
    private static String shortVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return String.valueOf(v);
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            StringBuilder sb = new StringBuilder("int[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 40; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof long[]) {
            long[] a = (long[]) v;
            StringBuilder sb = new StringBuilder("long[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 20; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof byte[]) {
            byte[] a = (byte[]) v;
            StringBuilder sb = new StringBuilder("byte[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 24; i++) {
                sb.append(i == 0 ? "" : ",").append(a[i]);
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof String[]) {
            String[] a = (String[]) v;
            StringBuilder sb = new StringBuilder("String[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 12; i++) {
                String s = a[i];
                sb.append(i == 0 ? "" : " | ").append(s == null ? "null" : s.substring(0, Math.min(s.length(), 120)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder sb = new StringBuilder("Obj[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 12; i++) sb.append(i == 0 ? "" : ", ").append(shortVal(a[i]));
            sb.append('}');
            return sb.toString();
        }
        return v.getClass().getSimpleName() + "@" + System.identityHashCode(v);
    }

    /** Full dump of the token Object[]: identity + every slot with array contents. */
    private static String tokenDump(Object[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("objArr@" + System.identityHashCode(arr) + " len=" + arr.length);
        for (int i = 0; i < arr.length; i++) {
            sb.append(' ').append(i).append('=').append(shortVal(arr[i]));
        }
        return sb.toString();
    }

    /** Hooks every declared method of clazz whose name matches (signature-agnostic). */
    private static int hookAllMethodsNamed(Class<?> clazz, String name, XC_MethodHook callback) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                hookOne(clazz, m, callback);
                hooked++;
            }
        }
        log("hooked " + hooked + "x " + clazz.getSimpleName() + "." + name);
        return hooked;
    }

    private static void hookOne(Class<?> clazz, Method m, XC_MethodHook callback) {
        try {
            log("hooking " + clazz.getName() + "#" + m + (Modifier.isStatic(m.getModifiers()) ? " (static)" : ""));
            XposedBridge.hookMethod(m, callback);
        } catch (Throwable t) {
            log("hookMethod failed " + clazz.getName() + "#" + m + ": " + t);
        }
    }

    /**
     * Platform level hooks, these method names are fixed by Android itself and therefore survive
     * any renaming the Belfius app applies to its own detection code.
     */
    private static final Target[] PLATFORM_TARGETS = new Target[] {
            new Target("android.os.Debug", "isDebuggerConnected", NO_ARGS, HAS_ROOT),
            new Target("android.os.Debug", "waitingForDebugger", NO_ARGS, HAS_ROOT)
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!BELFIUS_PKG.equals(loadPackageParam.packageName)) return;

        ClassLoader classLoader = loadPackageParam.classLoader;
        log("handleLoadPackage for " + BELFIUS_PKG + " loader=" + classLoader);
        try {
            log("   appInfo=" + loadPackageParam.appInfo);
        } catch (Throwable ignored) {}

        for (Target target : PLATFORM_TARGETS) {
            hook(classLoader, target);
        }

        int detectionHooks = hookDetectionTargets(classLoader);

        hookPackerBypass(classLoader);

        // The whole real app (main code + anti-Xposed audit dex) is injected inside
        // attachBaseContext, after this callback runs. Watch the classloader: the moment a packer
        // class is first requested we install its hooks before any of its methods can execute.
        try {
XposedHelpers.findAndHookMethod("java.lang.ClassLoader", classLoader, "loadClass",
                            String.class, new XC_MethodHook() {
                                private boolean installing;

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    if (installing) return;
                                    String name = (String) param.args[0];
                                    if (name == null) return;
                                    if (name.startsWith("o.")) {
                                        if (classLoadLogs.getAndDecrement() > 0) {
                                            log("CLASSLOAD " + name + " via " + param.thisObject);
                                        } else {
                                            log("...further class loads suppressed");
                                        }
                                    }
                                    if (name.startsWith("o.cBt") || name.startsWith("o.cBv")
                                            || name.startsWith("o.cCB")) {
                                        installing = true;
                                        try {
                                            hookPackerBypass(classLoader);
                                        } finally {
                                            installing = false;
                                        }
                                    } else if (name.startsWith("o.ckg") && !detectionTargetsHooked) {
                                        installing = true;
                                        try {
                                            hookDetectionTargets(classLoader);
                                        } finally {
                                            installing = false;
                                        }
                                    } else if (!uiIntegrityDiggerArmed.get()
                                            && (name.startsWith("o.bII") || name.startsWith("o.zbxt")
                                                    || name.startsWith("o.bDV") || name.startsWith("o.bFe")
                                                    || name.startsWith("o.bEV") || name.startsWith("o.bIT"))) {
                                        installing = true;
                                        try {
                                            hookUiIntegrityDigger(classLoader);
                                        } finally {
                                            installing = false;
                                        }
                                    }
                                }
                            });
            log("Classloader watcher installed.");
        } catch (Throwable t) {
            log("Classloader watcher failed: " + t);
        }

        // The real app and the audit dex run out of an injected classloader that we cannot reach
        // at loadPackage time. Stop the crash without touching that dex: the audit runs inside
        // Application.attachBaseContext, so an after-hook can absorb the RuntimeException before it
        // escapes to ActivityThread and aborts Application instantiation.
        String[] attachChain = { "o.fP", "o.lS", "o.zbtp" };
        for (final String className : attachChain) {
            try {
                XposedHelpers.findAndHookMethod(className, classLoader, "attachBaseContext",
                        Context.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                log(">> attachBaseContext " + className + " on thread "
                                        + Thread.currentThread().getName());
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                if (param.getThrowable() != null) {
                                    log("FAIL " + className + ".attachBaseContext threw: "
                                            + param.getThrowable());
                                    log(param.getThrowable());
                                    param.setResult(null);
                                } else {
                                    log("OK  " + className + ".attachBaseContext completed");
                                    if (!packerBypassInstalled) {
                                        hookPackerBypass(classLoader);
                                    }
                                }
                            }
                        });
                log("Attach swallow installed on " + className);
            } catch (Throwable t) {
                log("Attach swallow skipped " + className + ": " + t);
            }
        }

        // If the injected loader surfaces at runtime, still grab it and neutralise the audit
        // classes directly (belt and braces on top of the attach swallow).
        try {
            XposedHelpers.findAndHookMethod("o.dispatchWindowInsetsAnimationProgress", classLoader,
                    "read", int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (result instanceof Member) {
                                Member member = (Member) result;
                                Class<?> dc = member.getDeclaringClass();
                                Integer handle = (Integer) param.args[0];
                                ClassLoader loader = dc.getClassLoader();
                                // The audit resolves the same native bridge members in a hot loop;
                                // log each handle once (with its caller) so the log stays readable.
                                if (resolverSeen.putIfAbsent(handle,
                                        dc.getName() + "." + member) == null) {
                                    log("RESOLVER read(" + handle + ") FIRST -> " + dc + "."
                                            + member + " via " + loader);
                                    log("RESOLVER caller -> " + new Exception("resolver-first"));
                                }
                                // o.cBt/o.cBv and the native bridge classes all live in the SAME
                                // injected InMemoryDexClassLoader. Now that we can reach it, install
                                // the bypass + detection hooks against it directly.
                                if (loader != null) {
                                    if (!packerBypassInstalled) {
                                        hookPackerBypass(loader);
                                    }
                                    if (!detectionTargetsHooked) {
                                        hookDetectionTargets(loader);
                                    }
                                    if (!uiIntegrityDiggerArmed.get()) {
                                        hookUiIntegrityDigger(loader);
                                    }
                                }
                            } else {
                                log("RESOLVER read(" + param.args[0] + ") -> " + result);
                            }
                        }
                    });
            log("Resolver watcher installed.");
        } catch (Throwable t) {
            log("Resolver watcher failed: " + t);
        }

        // The getDeclaredMethods / getDeclaredMethod / getMethod observers once revealed which o.*
        // classes the stub introspects, but hooking java.lang.Class reflection is extremely chatty
        // (every framework lookup passes through it) and rotated the activation lines out of the
        // daemon log. The packer/audit structure is fully mapped now, so they are removed.

        // The brute force is only a fallback for the app versions predating the exact targets above,
        // running it on a newer version risks hooking an unrelated method that happens to match
        if (detectionHooks > 0) {
            log("Hooked " + detectionHooks + " known detection method(s), skipping fallback scan!");
            return;
        }

        // Loop through all possible class + method names, to attempt to find the method to hook
        for (String className : CLASS_NAMES) {
            for (String methodName : METHOD_NAMES) {
                try {
                    // Initialize method name string helper for logging
                    String methodNameStr = className + "." + methodName + "()";

                    // Attempt to hook the method
                    XposedHelpers.findAndHookMethod(
                            className,
                            classLoader,
                            methodName,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    param.setResult(HAS_ROOT);
                                }
                            });

                    // Log success + abort loops
                    log("Successfully hooked " + methodNameStr + "!");
                    return;

                } catch (Throwable ignored) {}
            }
        }

        log("Failed to hook, no viable method found...");
    }

    /**
     * Forces the return value in beforeHookedMethod instead of afterHookedMethod on purpose,
     * the debugger checks of v26.x guard their own body and throw when they detect a hook,
     * afterHookedMethod would never run on such a method.
     */
    private int hookDetectionTargets(ClassLoader classLoader) {
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

    private boolean hook(ClassLoader classLoader, Target target) {
        try {
            Object[] args = new Object[target.parameterTypes.length + 1];
            for (int i = 0; i < target.parameterTypes.length; i++) {
                args[i] = target.parameterTypes[i];
            }
            args[target.parameterTypes.length] = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(target.result);
                }
            };

            XposedHelpers.findAndHookMethod(target.className, classLoader, target.methodName, args);

            log("Successfully hooked " + target + "!");
            return true;

        } catch (Throwable ex) {
            log("Skipped " + target + ": " + ex.getClass().getSimpleName()
                    + (ex.getMessage() != null ? " - " + ex.getMessage() : ""));
            return false;
        }
    }

    private static final class Target {
        private final String className;
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final Object result;

        private Target(String className, String methodName, Class<?>[] parameterTypes, Object result) {
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
