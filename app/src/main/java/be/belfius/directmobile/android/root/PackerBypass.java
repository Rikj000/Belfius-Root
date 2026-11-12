package be.belfius.directmobile.android.root;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static be.belfius.directmobile.android.root.Diagnostics.shortVal;
import static be.belfius.directmobile.android.root.Diagnostics.tokenDump;
import static be.belfius.directmobile.android.root.Hooks.findClass;
import static be.belfius.directmobile.android.root.Hooks.findField;
import static be.belfius.directmobile.android.root.Hooks.getStaticIntField;
import static be.belfius.directmobile.android.root.Hooks.hookAllMethodsNamed;
import static be.belfius.directmobile.android.root.Hooks.isStatic;
import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.logAlways;
import static be.belfius.directmobile.android.root.ModuleLog.threadLabel;

/**
 * The anti-tamper audit bypass — the fixes that keep Belfius v26.3.0 from killing the process.
 *
 * <p>The packer runs its audit ({@code o.cBt} / {@code o.cBv} / {@code o.cCB}) from a dex it injects
 * mid-{@code attachBaseContext}. Injecting any Xposed module perturbs the audit token, which makes
 * {@code o.cBv.write} throw and the Firebase worker hit a deliberate OOM — both surfacing as the
 * generic "Algemene fout" dialog.
 *
 * <p>Three fixes, in increasing order of how deep they reach:
 * <ol>
 *   <li>{@code o.cBt.read} — the source: restore the clean measurement the caller asked for.</li>
 *   <li>{@code o.cBv.write} — belt and braces: normalise the token slots at the decision point.</li>
 *   <li>{@code o.col$write} — a fourth copy of the same guard, neutralised only when it cannot be
 *       proven to pass.</li>
 * </ol>
 *
 * <p>The classes live in the injected dex, so this installer is re-run from the class-load watcher
 * and the resolver watcher until it succeeds once ({@link #installed}).
 */
final class PackerBypass {
    private volatile boolean installed;

    private final RaspGuard guard;

    PackerBypass(RaspGuard guard) {
        this.guard = guard;
    }

    boolean isInstalled() {
        return installed;
    }

    void install(ClassLoader classLoader) {
        if (installed) return;
        Class<?> cBt;
        Class<?> cBv;
        try {
            cBt = findClass("o.cBt", classLoader);
            cBv = findClass("o.cBv", classLoader);
        } catch (Throwable t) {
            log("Packer bypass deferred, class not loadable yet: " + t);
            return;
        }

        // FIX 2 — token normalisation. o.cBv.write throws RuntimeException(String.valueOf(i2)) when
        // the audit's slots differ: slot0 carries the finder's measured int (dirty = baseline ^ tamper
        // code), slot1 the clean baseline the caller passed to cBt.read. The clean direction is
        // therefore slot0 := slot1 — folding the dirty value into slot1 is what used to reach
        // o.cwx.read. With fix 1 in place the slots are normally already equal and this never fires.
        // write is void, so suppressing the exception afterwards is unreliable: correct it here.
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

        // FIX 3 — o.col$write.RemoteActionCompatParcelizer(cov$read) is a fourth copy of the same
        // check: it rolls H(o.cwC.write, o.cwC.IconCompatParcelizer, colGateEnv(uptime)) and runs a
        // deliberate kill block (new int[i2] → OOM / NegativeArraySizeException) when H != o.cwx.read.
        // Replay the verdict first: neutralise only when the gate would still fault, otherwise let the
        // real body run so the log records a natural pass.
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
                    int verdict = guard.colGateVerdict(v);   // 1 = pass, -1 = would fault, 0 = unknown
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
                    // Natural pass: the original body compares equal and takes the safe branch itself.
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

        // The audit may call write through o.cBt$RemoteActionCompatParcelizer on another
        // implementation. That method is abstract (the framework cannot hook it): record its shape.
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

        installed = true;
        // Always-on: one line per Belfius process, so logcat alone proves the fix is live.
        logAlways("BelfiusRoot: anti-tamper audit bypass installed (debug=" + DEBUG + ")");

        // FIX 1 — the source. o.cBt.read is the anomaly FINDER: it returns
        // Obj[2]{ int[1]{ measured }, String[]{ signature } } where measured = computed ^ (int) args[1]
        // (computed is 0 clean, the tamper code when a finding is flagged). cwx.write folds that dirty
        // int into o.cwx.read, which then stops equaling every guard's recomputed H → all of them throw
        // → generic dialog. The record is captured before write runs, so normalising in write is too
        // late: restore args[1], the clean baseline, right here. Never zero it — the app divides by this
        // token during UI layout (o.bII.hashCode) and zeroing raises ArithmeticException.
        hookAllMethodsNamed(cBt, "read", chain -> {
            Object result = chain.proceed();
            Object[] args = chain.getArgs().toArray();
            Object res = result;
            if (!(res instanceof Object[])) return result;
            Object[] o = (Object[]) res;
            if (o.length < 2 || !(o[0] instanceof int[]) || !(o[1] instanceof String[])) return result;
            String[] strs = (String[]) o[1];
            // CAREAD: log both operands of the xor plus the three o.cwC statics, so which one carries
            // the tamper code is unambiguous. Debug-only.
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
                // Read o.cwx.read only through the already-resolved Field: no class loading / <clinit>
                // may be triggered from inside this hook.
                Field expected = guard.expectedField();
                if (expected != null) {
                    sb.append(" | o.cwx.read=").append(guard.guardInt(expected));
                }
                log(sb.toString());
            }
            if (strs.length == 0) return result;
            if (DEBUG) {
                log("TOKEN read finding stripped on " + threadLabel()
                        + " was=" + shortVal(o[1]) + " int=" + shortVal(o[0]));
            }
            o[1] = new String[0];
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

        // Debug-only: every int the persisted stream (/cov$read) produces, to pin down which slot
        // becomes the crash byte[] length.
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
    }
}
