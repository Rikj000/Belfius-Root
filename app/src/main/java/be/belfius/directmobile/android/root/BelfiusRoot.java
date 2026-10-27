package be.belfius.directmobile.android.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * The module's entry point, written against the modern libxposed API (API 102).
 *
 * <p>The framework instantiates this class once per loaded module generation in a process and then
 * calls {@link #onModuleLoaded} / {@link #onPackageLoaded}. Hooks are installed through
 * {@code hook(Executable).intercept(Hooker)}: the interceptor receives a {@link XposedInterface.Chain}
 * instead of a mutable parameter bag, and skips the original by simply not calling
 * {@code chain.proceed()}.
 */
/** @noinspection unused*/
public class BelfiusRoot extends XposedModule {
    private static final String TAG = "BelfiusRoot";
    private static final String BELFIUS_PKG = "be.belfius.directmobile.android";
    private static final Boolean HAS_ROOT = false;

    /** The instance the framework created for this process; used by the static log helpers. */
    private static volatile BelfiusRoot instance;

    /**
     * The process name captured in {@link #onModuleLoaded}. The legacy
     * {@code LoadPackageParam.processName} drove the one-off main-process verdict, but the modern
     * {@link XposedModuleInterface.PackageLoadedParam} no longer exposes it.
     */
    private static volatile String processName;

    /**
     * Diagnostics switch, read once per process from the module's "debug" setting
     * (see {@link ModuleSettings} / {@link SettingsActivity}).
     *
     * <p>When OFF (the default) every diagnostic hook is skipped at install time, {@link #log} is a
     * no-op and the cheap-early-return helpers ({@link #threadLabel()}, {@link #guardTriple}) do no
     * work at all, so the module adds nothing measurable to hot paths. The functional fixes
     * (o.cBt.read restore, o.cBv.write slot normalisation, the hash-check neutralisers, the root
     * detection overrides) are never gated.
     *
     * <p>This matters because the packer's audit runs on the MAIN THREAD from
     * {@code BelfiusRecyclerView.dispatchDraw} on some screens; with diagnostics on, every audit call
     * emitted six log lines, each capturing a full stack trace via {@link #threadLabel()} before
     * being handed to the framework log.
     */
    private static volatile boolean DEBUG;

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
                Class<?> cls = findClass("o.resolveSizeAndState", classLoader);
                if (cls != null) {
                    Method m = cls.getDeclaredMethod("read");
                    after(m, (chain, result) -> {
                        // Log what the app *itself* computed (this is the value the
                        // "About this app" screen renders) and then override it.
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
                    after(m, (chain, result) -> {
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
        if (!DEBUG) return; // pure diagnostics: never installed when the toggle is off
        hookErrorClass(classLoader, "o.getInstance");
    }

    /**
     * GUARD-SITE diagnostic (log-only): observe the injected anti-tamper guard entry/exit so the
     * guard's inputs are captured at the exact moment of a failure — including the case where the
     * throw never reaches o.getInstance. Nothing here changes the result; the safe-branch
     * replication (à la COLWRITE) is a separate, deliberate step once the values are known.
     */
    private void hookGuardThrowSites(ClassLoader classLoader, String className, String methodName) {
        if (!DEBUG) return; // log-only diagnostic
        Class<?> cls;
        try {
            cls = findClass(className, classLoader);
        } catch (Throwable t) {
            return; // not on this loader (yet)
        }
        if (!guardThrowSiteHooked.add(cls)) return;

        try {
            hookAllMethodsNamed(cls, methodName, chain -> {
                log("GUARD-SITE " + className + "#" + methodName + " ENTER " + guardTriple(className));
                Object r = null;
                Throwable t = null;
                try {
                    r = chain.proceed();
                } catch (Throwable e) {
                    t = e;
                }
                log("GUARD-SITE " + className + "#" + methodName + " EXIT throwable="
                        + (t == null ? "none" : t.getClass().getName() + ": " + t.getMessage())
                        + " result=" + (t == null ? fmtVal(r) : "-")
                        + " on " + threadLabel());
                if (t != null) log(guardTriple(className + "#" + methodName + " threw"));
                if (t != null) throw t;
                return r;
            });
            log("GUARD-SITE installed on " + className + "#" + methodName + " via " + classLoader);
        } catch (Throwable t) {
            log("GUARD-SITE failed on " + className + "#" + methodName + ": " + t);
        }
    }

    private void hookErrorClass(ClassLoader classLoader, String className) {
        Class<?> cls;
        try {
            cls = findClass(className, classLoader);
        } catch (Throwable t) {
            return; // not on this loader (yet)
        }
        if (!errorDiagnosticsHooked.add(cls)) return; // already instrumented this exact class

        hookAllMethodsNamed(cls, "IconCompatParcelizer", chain -> {
            for (Object a : chain.getArgs()) {
                if (a instanceof Throwable) {
                    Throwable t = (Throwable) a;
                    log("ERRDIAG " + className + "#" + chain.getExecutable().getName()
                            + " exception=" + t.getClass().getName() + ": " + t.getMessage()
                            + " on " + threadLabel());
                    // Snapshot the guard's three static ints + every clock the hash feeds on,
                    // so the failing run's exact inputs can be replayed offline (log-only).
                    log(guardTriple("via=" + className + "#" + chain.getExecutable().getName()));
                    log(t);
                }
            }
            return chain.proceed();
        });
        log("ERRDIAG installed on " + className + " via " + classLoader);
    }

    private void hookPackerBypass(ClassLoader classLoader) {
        if (packerBypassInstalled) return;
        Class<?> cBt;
        Class<?> cBv;
        try {
            cBt = findClass("o.cBt", classLoader);
            cBv = findClass("o.cBv", classLoader);
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
        hookAllMethodsNamed(cBv, "write", chain -> {
            Object arg = chain.getArg(0);
            if (DEBUG) {
                log("TOKEN write(before) on " + threadLabel() + " " + tokenDump((Object[]) arg));
            }
            if (arg instanceof Object[] && ((Object[]) arg).length > 1) {
                Object[] arr = (Object[]) arg;
                if (arr[0] instanceof int[] && arr[1] instanceof int[]) {
                    int[] measured = (int[]) arr[0];
                    int[] baseline = (int[]) arr[1];
                    if (measured.length > 0 && baseline.length > 0 && measured[0] != baseline[0]) {
                        if (DEBUG) {
                            log("TOKEN write slot0=" + measured[0] + " normalized to clean slot1="
                                    + baseline[0] + " on " + threadLabel());
                        }
                        measured[0] = baseline[0];
                    }
                }
            }
            Object r = null;
            Throwable t = null;
            try {
                r = chain.proceed();
            } catch (Throwable e) {
                t = e;
            }
            if (DEBUG && chain.getArg(0) instanceof Object[]) {
                log("TOKEN write(after) on " + threadLabel() + " " + tokenDump((Object[]) chain.getArg(0))
                        + " (throwable=" + (t != null) + ")");
            }
            if (t != null) throw t;
            return r;
        });

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
            final Class<?> colWrite = findClass("o.col$write", classLoader);
            final Field field = findField(colWrite, "AudioAttributesImplApi21Parcelizer");
            hookAllMethodsNamed(colWrite, "RemoteActionCompatParcelizer", chain -> {
                Object[] args = chain.getArgs().toArray();
                boolean covReadOverload = args.length == 1
                        && args[0] != null
                        && "o.cov$read".equals(args[0].getClass().getName());
                if (DEBUG) {
                    StringBuilder sb = new StringBuilder("COLWRITE before on " + threadLabel()
                            + " m=" + ((Method) chain.getExecutable()).getName()
                            + (isStatic((Method) chain.getExecutable()) ? " (static)" : " (instance)")
                            + " this=" + shortVal(chain.getThisObject()));
                    for (int i = 0; i < args.length; i++) {
                        sb.append(" arg").append(i).append('=').append(args[i] == null
                                ? "null" : args[i].getClass().getSimpleName() + "@"
                                + System.identityHashCode(args[i]));
                    }
                    log(sb.toString());
                }
                if (covReadOverload) {
                    int[] v = new int[2];
                    int verdict = colGateVerdict(v);   // 1 = pass, -1 = would fault, 0 = unknown
                    if (DEBUG) {
                        log("COLWRITE VERDICT " + (verdict == 1 ? "pass" : verdict == -1 ? "FAIL" : "unknown")
                                + " recomputedH=" + v[0] + " o.cwx.read=" + v[1] + " on " + threadLabel());
                    }
                    if (verdict != 1) {
                        Object thisObject = chain.getThisObject();
                        Object covRead = args[0];
                        field.set(thisObject, covRead);
                        if (DEBUG) {
                            log("COLWRITE NEUTRALIZED on " + threadLabel() + " set this="
                                    + shortVal(thisObject) + " AudioAttributesImplApi21Parcelizer="
                                    + covRead.getClass().getSimpleName() + "@" + System.identityHashCode(covRead)
                                    + " (safe branch replicated, kill block skipped)");
                        }
                        return thisObject;
                    }
                    // Natural path: the original body will compare equal and take the safe
                    // branch itself. Do not interfere; the after-part records the result.
                }
                Object result = null;
                Throwable thrown = null;
                try {
                    result = chain.proceed();
                } catch (Throwable e) {
                    thrown = e;
                }
                if (DEBUG) {
                    log("COLWRITE after on " + threadLabel() + " m="
                            + ((Method) chain.getExecutable()).getName()
                            + " res=" + shortVal(result) + " thr=" + thrown);
                }
                if (thrown != null) throw thrown;
                return result;
            });
        } catch (Throwable t) {
            log("o.col$write neutralizer skipped: " + t);
        }

        // The audit may call write through the o.cBt$RemoteActionCompatParcelizer interface on an
        // implementation other than o.cBv. The interface method itself is abstract (Vector cannot
        // hook it); log its declaration for the record.
        if (DEBUG) {
            String[] innerNames = { "o.cBt$RemoteActionCompatParcelizer", "o.cBt$AudioAttributesCompatParcelizer" };
            for (String innerName : innerNames) {
                try {
                    Class<?> iface = findClass(innerName, classLoader);
                    log("write interface candidate " + innerName + " isInterface=" + iface.isInterface());
                } catch (Throwable t) {
                    log("Interface write hook skipped " + innerName + ": " + t);
                }
            }
        }

        packerBypassInstalled = true;
        // The only line the module emits when the debug toggle is off: one per Belfius process,
        // so it is always possible to confirm from logcat that the fix is actually live.
        logAlways("BelfiusRoot: anti-tamper audit bypass installed (debug=" + DEBUG + ")");

        // The anomaly FINDER is o.cBt.read:1149 which returns Obj[2]{int[1]{measured}, String[]{signature}}.
        // The clean/no-anomaly encoding is String[0]{} (seen at cBt.write:760). The only divergence our
        // instrumentation causes is "private void android.app.ActivityThread.attach(boolean,long)"
        // (its metadata is a few bytes different due to backpatching). The measured int AND the signature
        // are captured into a persisted audit record BEFORE write runs; normalizing in write is too late.
        // The firebase worker later replays the record and uses the stored int as a byte[] length, which
        // dies as OOM / NegativeArraySizeException (o.col$write.RemoteActionCompatParcelizer(:224)).
        // Fix at the source: when a finding is flagged (non-empty signature list), wipe BOTH the signature
        // list AND the measured int so the stored record is indistinguishable from a clean run.
        hookAllMethodsNamed(cBt, "read", chain -> {
            // NOTE: this was a hookAllMethods (all "read" overloads) after-hook in the legacy API,
            // so the interceptor calls proceed() first and then applies the same after-the-call
            // body; the returned Object[] is mutated in place, exactly as it was before.
            Object result = chain.proceed();
            Object[] args = chain.getArgs().toArray();
            Object res = result;
            if (!(res instanceof Object[])) return result;
            Object[] o = (Object[]) res;
            if (o.length < 2 || !(o[0] instanceof int[]) || !(o[1] instanceof String[])) return result;
            String[] strs = (String[]) o[1];
            // CAREAD: o.cBt.read(JI)[Object] ends with `xor-int/2addr v0, v2; aput v0, int[1], 0`
            // i.e. returned int = computed ^ (int) args[1]. Exactly one of those two operands is
            // the CLEAN baseline a clean device returns (839523105 in this run, since clinit's
            // hash used it); the other carries the tamper code (7). Log both + the three cwC
            // statics so the surviving operand can be identified unambiguously. Log only.
            if (DEBUG) {
                int measured = ((int[]) o[0]).length > 0 ? ((int[]) o[0])[0] : Integer.MIN_VALUE;
                StringBuilder sb = new StringBuilder("CAREAD measured=" + measured);
                if (args.length > 0 && args[0] instanceof Long) {
                    sb.append(" flags=0x").append(Long.toHexString((Long) args[0]));
                }
                if (args.length > 1 && args[1] instanceof Integer) {
                    int a1 = ((Integer) args[1]).intValue();
                    sb.append(" arg1=").append(a1)
                      .append(" measured^arg1=").append(measured ^ a1);
                }
                sb.append(" sigs=").append(strs.length).append(" on ").append(threadLabel());
                try {
                    Class<?> cwC = findClass("o.cwC", classLoader);
                    sb.append(" | cwC.IconCompatParcelizer=")
                      .append(getStaticIntField(cwC, "IconCompatParcelizer"))
                      .append(" cwC.read=").append(getStaticIntField(cwC, "read"))
                      .append(" cwC.write=").append(getStaticIntField(cwC, "write"));
                } catch (Throwable ignored) {}
                // o.cwx.read through the already-resolved Field only (no class loading /
                // <clinit> triggered from inside this hook).
                if (guardFieldExpected != null) {
                    sb.append(" | o.cwx.read=").append(guardInt(guardFieldExpected));
                }
                log(sb.toString());
            }
            if (strs.length == 0) return result;
            if (DEBUG) {
                log("TOKEN read finding stripped on " + threadLabel()
                        + " was=" + shortVal(o[1]) + " int=" + shortVal(o[0]));
            }
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
            if (args.length > 1 && args[1] instanceof Integer) {
                int clean = ((Integer) args[1]).intValue();
                int[] m = (int[]) o[0];
                if (m.length > 0 && m[0] != clean) {
                    if (DEBUG) {
                        log("FIX cBt.read measured=" + m[0] + " -> clean baseline " + clean
                                + " (tamper code " + (m[0] ^ clean) + ") on " + threadLabel());
                    }
                    m[0] = clean;
                }
            }
            return result;
        });

        // Which slot becomes the crash byte[] length? Log every int the stream (/cov$read, on-disk
        // classes5.dex) produces on firebase threads so we can pin the producer and confirm the fix.
        if (DEBUG) {
            try {
                Class<?> covRead = findClass("o.cov$read", classLoader);
                hookAllMethodsNamed(covRead, "read", chain -> {
                    Object result = chain.proceed();
                    if (result instanceof Number) {
                        log("COVREAD int on " + threadLabel() + " = " + result);
                    }
                    return result;
                });
            } catch (Throwable t) {
                log("cov$read observer skipped: " + t);
            }
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
            Class<?> bbf = findClass("o.bBF", classLoader);
            int h = hookAllMethodsNamed(bbf, "newThread", chain -> {
                Object result = null;
                Throwable thrown = null;
                try {
                    result = chain.proceed();
                } catch (Throwable e) {
                    thrown = e;
                }
                if (thrown instanceof NullPointerException && chain.getArg(0) instanceof Runnable) {
                    log("LOGIN-THREADFACTORY NEUTRALIZED on " + threadLabel()
                            + " -** substituting plain Thread");
                    return new Thread((Runnable) chain.getArg(0));
                }
                if (thrown != null) throw thrown;
                return result;
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
                Class<?> c = findClass(className, classLoader);
                final java.util.concurrent.atomic.AtomicBoolean once =
                        new java.util.concurrent.atomic.AtomicBoolean();
                int h = hookAllMethodsNamed(c, "hashCode", chain -> {
                    if (DEBUG && once.compareAndSet(false, true)) {
                        log("UI-INTEGRITY hashCode(this) on " + threadLabel() + " -> "
                                + dumpFields(chain.getThisObject()));
                    }
                    Object result = null;
                    Throwable thrown = null;
                    try {
                        result = chain.proceed();
                    } catch (Throwable e) {
                        thrown = e;
                    }
                    // o.bII.hashCode:132 divides by a zero field on this build and aborts the
                    // main thread during UI layout. The original throw is an intentional gate:
                    // consume it and supply a stable identity-based hash so layout survives.
                    if (thrown instanceof ArithmeticException) {
                        log("UI-INTEGRITY hashCode NEUTRALIZED on " + threadLabel());
                        return System.identityHashCode(chain.getThisObject()) * 31;
                    }
                    if (thrown != null) throw thrown;
                    return result;
                });
                hookedAny |= h > 0;
                log("UI-integrity digger on " + className + " hashCode (" + h + " hooks)");
                // Purely diagnostic (it only dumps fields looking for the divisor) — not installed
                // when the debug toggle is off.
                if (DEBUG) {
                    final java.util.concurrent.atomic.AtomicBoolean onceAttributes =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    int hb = hookAllMethodsNamed(c, "AudioAttributesCompatParcelizer", chain -> {
                        if (onceAttributes.compareAndSet(false, true)) {
                            log("UI-INTEGRITY " + c.getSimpleName()
                                    + ".AudioAttributesCompatParcelizer on " + threadLabel()
                                    + " this=" + dumpFields(chain.getThisObject()));
                        }
                        return chain.proceed();
                    });
                    hookedAny |= hb > 0;
                    log("UI-integrity digger on " + className + " AudioAttributesCompatParcelizer (" + hb + " hooks)");
                }
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
        if (!DEBUG) return "";
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

    /**
     * Read the module's "debug" setting from inside the hooked process.
     *
     * <p>Modern API: the framework's remote preferences are read-only here, which is all we need —
     * the module app writes them through the Xposed service. Any failure degrades to OFF, the safe
     * default (so a framework that has no service, or no stored value yet, simply runs diagnostics
     * off).
     */
    private boolean readDebugPref() {
        try {
            SharedPreferences prefs = getRemotePreferences(ModuleSettings.PREFS);
            return prefs != null && prefs.getBoolean(ModuleSettings.KEY_DEBUG, false);
        } catch (Throwable t) {
            Log.e(TAG, "readDebugPref failed", t);
            return false;
        }
    }

    /** Always-on log: reserved for the one startup line and for hard failures during install. */
    private static void logAlways(String msg) {
        try {
            BelfiusRoot m = instance;
            if (m != null) m.log(Log.INFO, TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Diagnostic log — no-op unless the module's "debug" setting is on.
     *
     * <p>Note that gating here only removes the logd write; callers that build expensive arguments
     * (token dumps, {@code findClass}, stack traces) must still guard themselves with {@code DEBUG}.
     */
    private static void log(String msg) {
        if (!DEBUG) return;
        try {
            BelfiusRoot m = instance;
            if (m != null) m.log(Log.INFO, TAG, msg);
            else Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** Diagnostic log of a throwable — no-op unless the module's "debug" setting is on. */
    private static void log(Throwable t) {
        if (!DEBUG) return;
        try {
            BelfiusRoot m = instance;
            if (m != null) m.log(Log.ERROR, TAG, String.valueOf(t), t);
            else Log.e(TAG, String.valueOf(t), t);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Caller label for a diagnostic line. Expensive (full stack capture), so it returns an empty
     * string as soon as diagnostics are off — every call site just concatenates the result.
     */
    private static String threadLabel() {
        if (!DEBUG) return "";
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder(Thread.currentThread().getName());
        int shown = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            if (cn.startsWith("java.lang") || cn.startsWith("dalvik") || cn.startsWith("be.belfius.directmobile.android.root")
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

    // ------------------------------------------------------------------------------------------
    // Modern (libxposed API 102) hook plumbing + the reflection helpers the framework no longer
    // provides (there is no XposedHelpers in the modern API).
    // ------------------------------------------------------------------------------------------

    /** Interceptor body; uses {@code chain.getArg(i)} / {@code chain.proceed()} directly. */
    private interface Intercept {
        Object run(XposedInterface.Chain chain) throws Throwable;
    }

    /** After-hook body: the original already ran and returned {@code result}. */
    private interface After {
        Object run(XposedInterface.Chain chain, Object result) throws Throwable;
    }

    /**
     * Around-hook body: the original already ran and either returned {@code result} or threw
     * {@code thrown}. The body MUST rethrow {@code thrown} when it does not substitute a result.
     */
    private interface Around {
        Object run(XposedInterface.Chain chain, Object result, Throwable thrown) throws Throwable;
    }

    /** {@code Class.forName} with initialisation off, i.e. the legacy findClass helper. */
    private static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    /** The legacy findField helper: declared field, or one inherited, made accessible. */
    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(clazz.getName() + "." + name);
    }

    /** The legacy getStaticIntField helper. */
    private static int getStaticIntField(Class<?> clazz, String name)
            throws NoSuchFieldException, IllegalAccessException {
        return findField(clazz, name).getInt(null);
    }

    /** Hooks every declared method of clazz whose name matches (signature-agnostic). */
    private int hookAllMethodsNamed(Class<?> clazz, String name, Intercept body) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                hookOne(clazz, m, body);
                hooked++;
            }
        }
        log("hooked " + hooked + "x " + clazz.getSimpleName() + "." + name);
        return hooked;
    }

    private XposedInterface.HookHandle hookOne(Class<?> clazz, Method m, Intercept body) {
        try {
            log("hooking " + clazz.getName() + "#" + m + (Modifier.isStatic(m.getModifiers()) ? " (static)" : ""));
            m.setAccessible(true);
            return hook(m).intercept(body::run);
        } catch (Throwable t) {
            log("hookMethod failed " + clazz.getName() + "#" + m + ": " + t);
            return null;
        }
    }

    /** Runs {@code body} after the original; an exception from the original still propagates. */
    private XposedInterface.HookHandle after(Method m, After body) {
        return hookOne(m.getDeclaringClass(), m, chain -> body.run(chain, chain.proceed()));
    }

    /** Runs {@code body} with the original's outcome; the body decides what to return / rethrow. */
    private XposedInterface.HookHandle around(Method m, Around body) {
        return hookOne(m.getDeclaringClass(), m, chain -> {
            Object result = null;
            Throwable thrown = null;
            try {
                result = chain.proceed();
            } catch (Throwable t) {
                thrown = t;
            }
            return body.run(chain, result, thrown);
        });
    }

    /**
     * The legacy findAndHookMethod helper: resolves {@code className#methodName} on the given
     * loader and hooks it. Throws when the class or the method is missing, exactly like the legacy
     * helper did (several call sites rely on that to decide whether they are installed).
     */
    private XposedInterface.HookHandle hookMethod(ClassLoader classLoader, String className,
                                                  String methodName, Class<?>[] parameterTypes,
                                                  Intercept body) throws Throwable {
        Class<?> clazz = findClass(className, classLoader);
        Method m = clazz.getDeclaredMethod(methodName, parameterTypes);
        return hookOne(clazz, m, body);
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
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        instance = this;
        processName = param.getProcessName();
        logAlways("modern module loaded: process=" + param.getProcessName()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion());
        DEBUG = readDebugPref();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!BELFIUS_PKG.equals(param.getPackageName())) return;

        ClassLoader classLoader = param.getDefaultClassLoader();
        DEBUG = readDebugPref();
        log("handleLoadPackage for " + BELFIUS_PKG + " loader=" + classLoader);
        try {
            log("   appInfo=" + param.getApplicationInfo());
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
            final java.util.concurrent.atomic.AtomicBoolean installing = new java.util.concurrent.atomic.AtomicBoolean();
            hookMethod(classLoader, "java.lang.ClassLoader", "loadClass",
                    new Class<?>[] { String.class }, chain -> {
                        Object loadResult = chain.proceed();
                        if (installing.get()) return loadResult;
                        String name = (String) chain.getArg(0);
                        if (name == null) return loadResult;
                        if (name.startsWith("o.")) {
                            if (classLoadLogs.getAndDecrement() > 0) {
                                log("CLASSLOAD " + name + " via " + chain.getThisObject());
                            } else {
                                log("...further class loads suppressed");
                            }
                        }
                        if (name.startsWith("o.cBt") || name.startsWith("o.cBv")
                                || name.startsWith("o.cCB")) {
                            installing.set(true);
                            try {
                                hookPackerBypass(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.ckg") && !detectionTargetsHooked) {
                            installing.set(true);
                            try {
                                hookDetectionTargets(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.getInstance")) {
                            installing.set(true);
                            try {
                                hookErrorDiagnostics(classLoader);
                            } finally {
                                installing.set(false);
                            }
                        } else if (name.startsWith("o.aYE")) {
                            installing.set(true);
                            try {
                                hookGuardThrowSites(classLoader, "o.aYE", "setVisibility");
                            } finally {
                                installing.set(false);
                            }
                        } else if (!uiIntegrityDiggerArmed.get()
                                && (name.startsWith("o.bII") || name.startsWith("o.zbxt")
                                        || name.startsWith("o.bDV") || name.startsWith("o.bFe")
                                        || name.startsWith("o.bEV") || name.startsWith("o.bIT"))) {
                            installing.set(true);
                            try {
                                hookUiIntegrityDigger(classLoader);
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

        // The real app and the audit dex run out of an injected classloader that we cannot reach
        // at loadPackage time. Stop the crash without touching that dex: the audit runs inside
        // Application.attachBaseContext, so an after-hook can absorb the RuntimeException before it
        // escapes to ActivityThread and aborts Application instantiation.
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
                                if (!packerBypassInstalled) {
                                    hookPackerBypass(classLoader);
                                }
                            }
                            return result;
                        });
                log("Attach swallow installed on " + className);
            } catch (Throwable t) {
                log("Attach swallow skipped " + className + ": " + t);
            }
        }

        // If the injected loader surfaces at runtime, still grab it and neutralise the audit
        // classes directly (belt and braces on top of the attach swallow).
        try {
            hookMethod(classLoader, "o.dispatchWindowInsetsAnimationProgress", "read",
                    new Class<?>[] { int.class }, chain -> {
                        Object result = chain.proceed();
                        // GUARDFIELD diagnostic: report the four handles the injected
                        // anti-tamper guard uses, so we learn which class owns the three shared
                        // static int fields it validates (log-only, no result is modified).
                        int guardHandle = ((Integer) chain.getArg(0)).intValue();
                        if (isGuardFieldId(guardHandle)) {
                            noteGuardField(guardHandle, result);
                            if (DEBUG && guardFieldSeen.add(guardHandle)) {
                                logGuardField("read(" + guardHandle + ")", result);
                            }
                            // The seed (i3) is read LAST inside every guard invocation, so at
                            // that point expected/actual/seed are all known: sample the triple
                            // plus every clock the hash feeds (capped — guards run often).
                            if (DEBUG && guardHandle == GUARD_ID_SEED
                                    && guardTripleLogs.getAndIncrement() < 10) {
                                log(guardTriple("resolver-seed-read"));
                            }
                        }
                        if (result instanceof Member) {
                            Member member = (Member) result;
                            Class<?> dc = member.getDeclaringClass();
                            Integer handle = (Integer) chain.getArg(0);
                            ClassLoader loader = dc.getClassLoader();
                            // The audit resolves the same native bridge members in a hot loop;
                            // log each handle once (with its caller) so the log stays readable.
                            if (DEBUG && resolverSeen.putIfAbsent(handle,
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
                            log("RESOLVER read(" + chain.getArg(0) + ") -> " + result);
                        }
                        return result;
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
            hookMethod(classLoader, "o.dispatchWindowInsetsAnimationProgress", "IconCompatParcelizer",
                    new Class<?>[] { char.class, int.class, int.class, int.class, boolean.class,
                            String.class, Class[].class },
                    chain -> {
                        Object result = chain.proceed();
                        Object k = chain.getArg(3);
                        if (!(k instanceof Integer)) return result;
                        int key = ((Integer) k).intValue();
                        if (!isGuardBuildKey(key)) return result;
                        noteGuardField(guardIdForKey(key), result);
                        if (DEBUG && guardBuildSeen.add(key)) {
                            logGuardField("build(key=" + key + ", name=" + chain.getArg(5) + ")",
                                    result);
                        }
                        return result;
                    });
            log("Guard-field builder watcher installed.");
        } catch (Throwable t) {
            log("Guard-field builder watcher failed: " + t);
        }

        // The getDeclaredMethods / getDeclaredMethod / getMethod observers once revealed which o.*
        // classes the stub introspects, but hooking java.lang.Class reflection is extremely chatty
        // (every framework lookup passes through it) and rotated the activation lines out of the
        // daemon log. The packer/audit structure is fully mapped now, so they are removed.

        // Deliberate one-off verdict (the only always-on output; everything else is behind the
        // debug toggle). Emitted once in the app's MAIN process so a multi-process app does not
        // repeat it. "audit=pending" is normal here — the injected audit loader appears later,
        // which is when the second line (audit bypass installed) is printed.
        if (processName == null
                || processName.equals(BELFIUS_PKG)) {
            logAlways("BelfiusRoot: hooks armed for " + BELFIUS_PKG
                    + " (detection=" + detectionHooks
                    + ", audit=" + (packerBypassInstalled ? "installed" : "pending")
                    + ", debug=" + (DEBUG ? "on" : "off") + ")");
        }

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
                    hookMethod(classLoader, className, methodName, NO_ARGS, chain -> {
                        chain.proceed();
                        return HAS_ROOT;
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
