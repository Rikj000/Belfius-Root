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
    private volatile boolean resolveSizeReadHooked;
    private volatile boolean resolveSizeIconHooked;
    private final AtomicInteger classLoadLogs = new AtomicInteger(200);
    private final Map<Integer, String> resolverSeen = new ConcurrentHashMap<>();

    /**
     * Resolver ids the injected anti-tamper guard passes to
     * o.dispatchWindowInsetsAnimationProgress.read(int) to obtain its three shared static int fields
     * (1109753110="read" expected, -1546951913="IconCompatParcelizer" actual, 485759512="write"
     * rolling state; 97097858 is the decoy get on the throw path). See session doc §3.
     */
    private static final int[] GUARD_FIELD_IDS = { 1109753110, -1546951913, 485759512, 97097858 };
    /**
     * The same four guard members as the build key (4th arg) of the resolver's
     * IconCompatParcelizer(char,int,int,int,boolean,String,Class[]): that overload is what
     * read(id) falls back to on a cache miss, and the build key is what identifies the declaring
     * class (key -1414280701/-329306217 -> class 429, key 1247663106/-178082547 -> class 434).
     */
    private static final int[] GUARD_BUILD_KEYS = { -1414280701, 1247663106, -178082547, -329306217 };
    /** Guard-field diagnostic: which handles/keys already reported (report each exactly once). */
    private final java.util.Set<Integer> guardFieldSeen =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private final java.util.Set<Integer> guardBuildSeen =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    /** The four guard resolver ids, named (see session doc §3). */
    private static final int GUARD_ID_EXPECTED = 1109753110;   // o.cwx.read        (guard's i)
    private static final int GUARD_ID_ACTUAL = -1546951913;     // o.cwC.IconCompatParcelizer (guard's i2)
    private static final int GUARD_ID_SEED = 485759512;         // o.cwC.write       (guard's i3)
    private static final int GUARD_ID_DECOY = 97097858;         // o.cwx.IconCompatParcelizer (Object, throw path)

    /** Resolved guard fields, kept so ERRDIAG / the throw-site hook can dump their live values. */
    private volatile Field guardFieldExpected;
    private volatile Field guardFieldActual;
    private volatile Field guardFieldSeed;
    /** Throw-site classes already instrumented (once per class). */
    private final java.util.Set<Class<?>> guardThrowSiteHooked =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    /** How many GUARDTRIPLE samples were written (guards run often — keep the log readable). */
    private final AtomicInteger guardTripleLogs = new AtomicInteger();

    /** Class objects already instrumented by the error-diagnostics hooks (once per loader). */
    private final java.util.Set<Class<?>> errorDiagnosticsHooked =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

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

            // NOTE: "About this app" (o.resolveSize.o.resolveSizeAndState) is hooked
            // separately in hookResolveSizeAndState() so we can log the app's own
            // computed value before overriding it.
    };

    /**
     * Belfius v26.3.0 runs an anti-Xposed audit (o.cBt / o.cBv / o.cCB) from a dex it injects into
     * the app mid-attachBaseContext. When any Xposed module is injected the audit perturbs its
     * token and o.cBv.write throws RuntimeException, killing the process.
     * These hooks neutralise the throw points; the statics cBv.write would have set are mirrored so
     * the audit looks byte-identical to a clean run. Many of the classes only exist after the dex
     * is injected, so this is (re)run from the classloader watcher.
     */
    private void hookResolveSizeAndState(ClassLoader classLoader) {
        if (!resolveSizeReadHooked) {
            try {
                Class<?> cls = XposedHelpers.findClass("o.resolveSizeAndState", classLoader);
                if (cls != null) {
                    XposedHelpers.findAndHookMethod(cls, "read", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Log what the app *itself* computed (this is the value the
                            // "About this app" screen renders) and then override it.
                            log("ROOTCHECK o.resolveSizeAndState.read() app-computed="
                                    + param.getResult() + " -> forcing " + HAS_ROOT);
                            param.setResult(HAS_ROOT);
                        }
                    });
                    resolveSizeReadHooked = true;
                    log("Successfully hooked o.resolveSizeAndState.read()!");
                }
            } catch (Throwable ignored) {
            }
        }
        if (!resolveSizeIconHooked) {
            try {
                Class<?> cls = XposedHelpers.findClass("o.resolveSize", classLoader);
                if (cls != null) {
                    XposedHelpers.findAndHookMethod(cls, "IconCompatParcelizer", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            log("ROOTCHECK o.resolveSize.IconCompatParcelizer() app-computed="
                                    + param.getResult() + " -> forcing " + HAS_ROOT);
                            param.setResult(HAS_ROOT);
                        }
                    });
                    resolveSizeIconHooked = true;
                    log("Successfully hooked o.resolveSize.IconCompatParcelizer()!");
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Diagnoses the generic "Algemene fout / Er is een fout opgetreden" dialog.
     *
     * The app maps every operation failure through o.getInstance.IconCompatParcelizer(...):
     * known banking errors get a specific message, but ANY unmapped exception falls through to
     * the generic pair (R.string "Algemene fout" + "Er is een fout opgetreden"). Because the app
     * logs almost nothing to logcat, the only way to see WHY an operation failed is to observe the
     * exception at that mapping point. These hooks only LOG (they never change the result), so they
     * are side-effect free; they reveal the exact throwable class + stack for the failed tap.
     */
    private void hookErrorDiagnostics(ClassLoader classLoader) {
        hookErrorClass(classLoader, "o.getInstance");
    }

    /**
     * GUARD-SITE diagnostic (log-only): observe the injected anti-tamper guard entry/exit so the
     * guard's inputs are captured at the exact moment of a failure — including the case where the
     * throw never reaches o.getInstance. Nothing here changes the result; the safe-branch
     * replication (à la COLWRITE) is a separate, deliberate step once the values are known.
     */
    private void hookGuardThrowSites(ClassLoader classLoader, String className, String methodName) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(className, classLoader);
        } catch (Throwable t) {
            return; // not on this loader (yet)
        }
        if (!guardThrowSiteHooked.add(cls)) return;

        try {
            XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("GUARD-SITE " + className + "#" + methodName + " ENTER " + guardTriple(className));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Throwable t = param.getThrowable();
                    log("GUARD-SITE " + className + "#" + methodName + " EXIT throwable="
                            + (t == null ? "none" : t.getClass().getName() + ": " + t.getMessage())
                            + " result=" + (t == null ? fmtVal(param.getResult()) : "-")
                            + " on " + threadLabel());
                    if (t != null) log(guardTriple(className + "#" + methodName + " threw"));
                }
            });
            log("GUARD-SITE installed on " + className + "#" + methodName + " via " + classLoader);
        } catch (Throwable t) {
            log("GUARD-SITE failed on " + className + "#" + methodName + ": " + t);
        }
    }

    private void hookErrorClass(ClassLoader classLoader, String className) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(className, classLoader);
        } catch (Throwable t) {
            return; // not on this loader (yet)
        }
        if (!errorDiagnosticsHooked.add(cls)) return; // already instrumented this exact class

        XC_MethodHook errLogger = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                for (Object a : param.args) {
                    if (a instanceof Throwable) {
                        Throwable t = (Throwable) a;
                        log("ERRDIAG " + className + "#" + param.method.getName()
                                + " exception=" + t.getClass().getName() + ": " + t.getMessage()
                                + " on " + threadLabel());
                        // Snapshot the guard's three static ints + every clock the hash feeds on,
                        // so the failing run's exact inputs can be replayed offline (log-only).
                        log(guardTriple("via=" + className + "#" + param.method.getName()));
                        log(t);
                    }
                }
            }
        };
        hookAllMethodsNamed(cls, "IconCompatParcelizer", errLogger);
        log("ERRDIAG installed on " + className + " via " + classLoader);
    }

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

        // o.cBv.write throws RuntimeException(String.valueOf(i2)) when the audit's token slots differ.
        // Slot semantics (verified live): objArr[0] carries the finder's measured int (dirty, =
        // baseline ^ tamper code) and objArr[1] carries the CLEAN baseline the caller passed to
        // cBt.read. So the clean direction is slot0 := slot1, NOT the other way round — folding the
        // dirty value into slot1 is what used to reach o.cwx.read. With the cBt.read fix above the
        // two slots are normally already equal and this never fires; it is the belt-and-braces
        // fallback for any call path that bypasses that hook. write is void; suppressing the
        // exception afterwards via setResult is not reliable, so correct at the decision point.
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
                        int[] measured = (int[]) arr[0];
                        int[] baseline = (int[]) arr[1];
                        if (measured.length > 0 && baseline.length > 0 && measured[0] != baseline[0]) {
                            log("TOKEN write slot0=" + measured[0] + " normalized to clean slot1="
                                    + baseline[0] + " on " + threadLabel());
                            measured[0] = baseline[0];
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

        // o.col$write.RemoteActionCompatParcelizer(cov$read) is a FOURTH copy of the same RASP
        // check as aYE/col$write-guard: it resolves o.cwx.read (i), o.cwC.IconCompatParcelizer (i2),
        // o.cwC.write (i3), rolls H(i3, i2, col_env((int) uptimeMillis())) and runs a DELIBERATE kill
        // block when H != o.cwx.read (new int[i2] with i2 = o.cwC.IconCompatParcelizer -> OOM /
        // NegativeArraySizeException, then an always-failing aget[-1]). The pass path just stores the
        // cov$read into this.AudioAttributesImplApi21Parcelizer and returns this.
        // It used to fire on every process because o.cwx.read was corrupt (session doc §9); since the
        // cBt.read fix the stored value equals the recomputed H again. Verified offline against live
        // values from pid 24859: col_env's decoy term hashes to the SAME constant 3825025452 as the
        // producer and aYE, and H(cwC.write, cwC.IconCompatParcelizer, col_env) = -828208786 =
        // the live o.cwx.read. So the gate now passes on its own.
        // Neutralize ONLY if the recomputed verdict says the gate would still fault (belt and
        // braces: an OOM here would kill the Firebase worker), otherwise let the real body run so
        // the log records a natural pass.
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
                    int[] v = new int[2];
                    int verdict = colGateVerdict(v);   // 1 = pass, -1 = would fault, 0 = unknown
                    log("COLWRITE VERDICT " + (verdict == 1 ? "pass" : verdict == -1 ? "FAIL" : "unknown")
                            + " recomputedH=" + v[0] + " o.cwx.read=" + v[1] + " on " + threadLabel());
                    if (verdict == 1) {
                        // Natural path: the original body will compare equal and take the safe
                        // branch itself. Do not interfere; afterHookedMethod records the result.
                        return;
                    }
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
                if (o.length < 2 || !(o[0] instanceof int[]) || !(o[1] instanceof String[])) return;
                String[] strs = (String[]) o[1];
                // CAREAD: o.cBt.read(JI)[Object] ends with `xor-int/2addr v0, v2; aput v0, int[1], 0`
                // i.e. returned int = computed ^ (int) args[1]. Exactly one of those two operands is
                // the CLEAN baseline a clean device returns (839523105 in this run, since clinit's
                // hash used it); the other carries the tamper code (7). Log both + the three cwC
                // statics so the surviving operand can be identified unambiguously. Log only.
                int measured = ((int[]) o[0]).length > 0 ? ((int[]) o[0])[0] : Integer.MIN_VALUE;
                StringBuilder sb = new StringBuilder("CAREAD measured=" + measured);
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof Long) {
                    sb.append(" flags=0x").append(Long.toHexString((Long) param.args[0]));
                }
                if (param.args != null && param.args.length > 1 && param.args[1] instanceof Integer) {
                    int a1 = ((Integer) param.args[1]).intValue();
                    sb.append(" arg1=").append(a1)
                      .append(" measured^arg1=").append(measured ^ a1);
                }
                sb.append(" sigs=").append(strs.length).append(" on ").append(threadLabel());
                try {
                    Class<?> cwC = XposedHelpers.findClass("o.cwC", classLoader);
                    sb.append(" | cwC.IconCompatParcelizer=")
                      .append(XposedHelpers.getStaticIntField(cwC, "IconCompatParcelizer"))
                      .append(" cwC.read=").append(XposedHelpers.getStaticIntField(cwC, "read"))
                      .append(" cwC.write=").append(XposedHelpers.getStaticIntField(cwC, "write"));
                } catch (Throwable ignored) {}
                // o.cwx.read through the already-resolved Field only (no class loading / <clinit>
                // triggered from inside this hook).
                if (guardFieldExpected != null) {
                    sb.append(" | o.cwx.read=").append(guardInt(guardFieldExpected));
                }
                log(sb.toString());
                if (strs.length == 0) return;
                log("TOKEN read finding stripped on " + threadLabel()
                        + " was=" + shortVal(o[1]) + " int=" + shortVal(o[0]));
                o[1] = new String[0];
                // FIX AT THE SOURCE. o.cBt.read(JI)[Object] ends with `xor-int/2addr v0, v2` /
                // `aput v0, int[1], 0`, i.e. it returns int[1]{ computed ^ (int) args[1] } where
                // args[1] is the CLEAN baseline the caller passed in and `computed` is 0 on a clean
                // run and the tamper code (7 here) when the scan flags a finding. Verified live:
                //   measured=221443188 arg1=221443187 measured^arg1=7 sigs=1   (dirty)
                //   measured=221443187 arg1=221443187 measured^arg1=0 sigs=0   (clean)
                // cwx.write folds that dirty int into o.cwx.read (read = cwuVar.read), so
                // o.cwx.read stops equalling the guard's recomputed H(seed, cwC.IconCompatParcelizer)
                // and EVERY injected anti-tamper guard throws -> generic "Algemene fout" dialog.
                // Proven numerically on pids 17373 and 23116: H(seed, clean) == the value at
                // <clinit>, H(seed, dirty) == the value after cwx.write.
                // Restore exactly what a clean device returns: args[1]. NOT zeroing it — the app
                // divides by this token during UI layout (o.bII.hashCode:132) and zeroing would
                // raise ArithmeticException; the clean baseline is non-zero like a clean run.
                if (param.args != null && param.args.length > 1 && param.args[1] instanceof Integer) {
                    int clean = ((Integer) param.args[1]).intValue();
                    int[] m = (int[]) o[0];
                    if (m.length > 0 && m[0] != clean) {
                        log("FIX cBt.read measured=" + m[0] + " -> clean baseline " + clean
                                + " (tamper code " + (m[0] ^ clean) + ") on " + threadLabel());
                        m[0] = clean;
                    }
                }
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

    /** True if the resolver handle is one of the four ids the injected anti-tamper guard reads. */
    private static boolean isGuardFieldId(int id) {
        for (int v : GUARD_FIELD_IDS) if (v == id) return true;
        return false;
    }

    /** True if the build key identifies a member the injected anti-tamper guard reads. */
    private static boolean isGuardBuildKey(int key) {
        for (int v : GUARD_BUILD_KEYS) if (v == key) return true;
        return false;
    }

    /**
     * GUARDFIELD diagnostic: identify the owner of a guard field — declaring class, member name,
     * static/type and the value it currently holds. Log only; nothing is mutated, so this cannot
     * change the app's behaviour. It turned out the "normalize the three statics" theory was wrong
     * (o.cwx.read is a DERIVED hash), but this is what proved the ownerships: o.cwx.read /
     * o.cwC.IconCompatParcelizer / o.cwC.write. See session doc §9.5.
     */
    private static void logGuardField(String via, Object member) {
        if (!(member instanceof Field)) {
            log("GUARDFIELD " + via + " -> " + member
                    + (member == null ? " (not resolved yet)" : " [" + member.getClass().getName() + "]"));
            return;
        }
        Field f = (Field) member;
        String who;
        try {
            who = f.getDeclaringClass().getName() + "." + f.getName() + " ["
                    + (Modifier.isStatic(f.getModifiers()) ? "static " : "") + f.getType().getName() + "]";
        } catch (Throwable t) {
            who = "<?> [" + t + "]";
        }
        String value;
        try {
            value = String.valueOf(f.getInt(null));
        } catch (Throwable t) {
            value = "<" + t + ">";
        }
        log("GUARDFIELD " + via + " -> " + who + " value=" + value);
    }

    /** Remember the resolved Field for each guard slot so the triple can be dumped later. */
    private void noteGuardField(int id, Object member) {
        if (!(member instanceof Field)) return;
        if (id == GUARD_ID_EXPECTED) guardFieldExpected = (Field) member;
        else if (id == GUARD_ID_ACTUAL) guardFieldActual = (Field) member;
        else if (id == GUARD_ID_SEED) guardFieldSeed = (Field) member;
    }

    /** Reverse of the builder's 4th arg (build key) -> the equivalent resolver id. */
    private static int guardIdForKey(int key) {
        switch (key) {
            case -1414280701: return GUARD_ID_EXPECTED;
            case 1247663106: return GUARD_ID_ACTUAL;
            case -178082547: return GUARD_ID_SEED;
            case -329306217: return GUARD_ID_DECOY;
            default: return 0;
        }
    }

    private static int guardInt(Field f) {
        if (f == null) return Integer.MIN_VALUE;
        try {
            return f.getInt(null);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * The packer's rolling hash pass, byte for byte as emitted by the dex:
     * {@code for (k = 0; k != 8; k++) i = (((((int)(j >> k)) & 255) + (i << 6)) + (i << 16)) - i;}.
     * Only bits 0..14 of {@code jval} ever reach the accumulator.
     */
    private static int hashPass(int seed, long jval) {
        int i = seed;
        for (int k = 0; k < 8; k++) {
            int b = (int) ((jval >> k) & 0xFF);
            i = b + ((i << 6) + (i << 16) - i);
        }
        return i;
    }

    /** o.col$write's environment term (its copy of the uptime decoy mix). Long math wraps like DEX. */
    private static long colGateEnv(int uptimeMillis) {
        long A = 2462369367754759110L, B = 1352544697600341417L, C = 3669650830335639535L;
        long j2 = -1L;
        long ju = uptimeMillis;
        long j3 = A ^ j2;
        long j4 = ju ^ j2;
        long j5 = 904L;
        long j6 = B ^ j2;
        return (905L * A)
                + ((-903L) * B)
                + ((-1808L) * (((j3 | ju) ^ j2) | ((j4 | B) ^ j2)))
                + (((((j3 | j6) | ju) ^ j2) | ((j4 | C) ^ j2)) * j5)
                + (j5 * (((j4 | A) ^ j2) | ((j6 | ju) ^ j2) | ((j3 | B) ^ j2)));
    }

    /**
     * Replay o.col$write.RemoteActionCompatParcelizer(cov.read)'s verdict before letting it run:
     * {@code H(o.cwC.write, o.cwC.IconCompatParcelizer, colGateEnv((int) uptimeMillis()))} vs
     * {@code o.cwx.read}. Returns 1 when the gate compares equal (natural pass), -1 when it would
     * enter the kill block, 0 when the guard Fields are not resolved yet. out[0]=recomputed H,
     * out[1]=stored o.cwx.read.
     */
    private int colGateVerdict(int[] out) {
        out[0] = Integer.MIN_VALUE;
        out[1] = guardInt(guardFieldExpected);
        if (guardFieldExpected == null || guardFieldActual == null || guardFieldSeed == null) return 0;
        int actual = guardInt(guardFieldActual);
        int seed = guardInt(guardFieldSeed);
        try {
            long env = colGateEnv((int) android.os.SystemClock.uptimeMillis());
            out[0] = hashPass(hashPass(seed, (long) actual), env);
            return out[0] == out[1] ? 1 : -1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * GUARDTRIPLE: the live values the injected anti-tamper guard compares (expected i / actual i2 /
     * seed i3) together with every clock the guards and their producer feed into the rolling hash.
     * Log only — this is what established the hash model (session doc §9.2).
     *
     * Producer (cwx &lt;clinit&gt;/write): o.cwx.read = H(o.cwC.write, o.cwC.IconCompatParcelizer,
     * (int) SystemClock.uptimeMillis()). Guards recompute H with their own environment term
     * (aYE: Process.getStartElapsedRealtime(); col$write: SystemClock.uptimeMillis()); all of those
     * environment terms hash to the SAME constant 3825025452, i.e. they are decoys.
     */
    private String guardTriple(String why) {
        return "GUARDTRIPLE[" + why
                + " expected(i)=" + guardInt(guardFieldExpected)
                + " actual(i2)=" + guardInt(guardFieldActual)
                + " seed(i3)=" + guardInt(guardFieldSeed)
                + " uptimeMillis=" + android.os.SystemClock.uptimeMillis()
                + " elapsedRealtime=" + android.os.SystemClock.elapsedRealtime()
                + " startElapsedRealtime=" + android.os.Process.getStartElapsedRealtime()
                + " wallClock=" + System.currentTimeMillis()
                + " thread=" + Thread.currentThread().getName() + "]";
    }

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
        hookErrorDiagnostics(classLoader);
        // The generic-error throw site lives in classes3.dex (base loader): observe it directly so
        // the guard's inputs are logged even if the exception never reaches o.getInstance.
        hookGuardThrowSites(classLoader, "o.aYE", "setVisibility");

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
                                    } else if (name.startsWith("o.getInstance")) {
                                        installing = true;
                                        try {
                                            hookErrorDiagnostics(classLoader);
                                        } finally {
                                            installing = false;
                                        }
                                    } else if (name.startsWith("o.aYE")) {
                                        installing = true;
                                        try {
                                            hookGuardThrowSites(classLoader, "o.aYE", "setVisibility");
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
                            // GUARDFIELD diagnostic: report the four handles the injected
                            // anti-tamper guard uses, so we learn which class owns the three shared
                            // static int fields it validates (log-only, no result is modified).
                            int guardHandle = ((Integer) param.args[0]).intValue();
                            if (isGuardFieldId(guardHandle)) {
                                noteGuardField(guardHandle, result);
                                if (guardFieldSeen.add(guardHandle)) {
                                    logGuardField("read(" + guardHandle + ")", result);
                                }
                                // The seed (i3) is read LAST inside every guard invocation, so at
                                // that point expected/actual/seed are all known: sample the triple
                                // plus every clock the hash feeds (capped — guards run often).
                                if (guardHandle == GUARD_ID_SEED
                                        && guardTripleLogs.getAndIncrement() < 10) {
                                    log(guardTriple("resolver-seed-read"));
                                }
                            }
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
                                    hookResolveSizeAndState(loader);
                                    hookErrorDiagnostics(loader);
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

        // GUARDFIELD diagnostic (log-only, temporary): read(id) returns null until the member is
        // cached, and on that miss the guard calls the builder below, which resolves the DECLARING
        // CLASS from (c,i,i2) and the member from its name. Observe only the four keys the guard
        // uses, so the app's (very hot) reflection traffic is unaffected.
        try {
            XposedHelpers.findAndHookMethod("o.dispatchWindowInsetsAnimationProgress", classLoader,
                    "IconCompatParcelizer",
                    char.class, int.class, int.class, int.class, boolean.class, String.class,
                    Class[].class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object k = param.args[3];
                            if (!(k instanceof Integer)) return;
                            int key = ((Integer) k).intValue();
                            if (!isGuardBuildKey(key)) return;
                            noteGuardField(guardIdForKey(key), param.getResult());
                            if (!guardBuildSeen.add(key)) return;
                            logGuardField("build(key=" + key + ", name=" + param.args[5] + ")",
                                    param.getResult());
                        }
                    });
            log("Guard-field builder watcher installed.");
        } catch (Throwable t) {
            log("Guard-field builder watcher failed: " + t);
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
