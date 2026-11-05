package be.belfius.directmobile.android.root;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static be.belfius.directmobile.android.root.Diagnostics.fmtVal;
import static be.belfius.directmobile.android.root.Hooks.hookAllMethodsNamed;
import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.threadLabel;

/**
 * The packer's injected anti-tamper guards: their field identities, the rolling hash they compare,
 * and the diagnostics that established both.
 *
 * <p>Model: the producer writes {@code o.cwx.read = H(o.cwC.write, o.cwC.IconCompatParcelizer,
 * env)}, each guard recomputes H with its own environment term — all of those environment terms hash
 * to the same constant, i.e. they are decoys — and throws into the generic "Algemene fout" dialog
 * when the two differ. The fixes that keep the recomputation honest live in {@link PackerBypass};
 * everything here observes, measures and replays.
 */
final class RaspGuard {
    /**
     * Resolver ids the guard passes to {@code o.dispatchWindowInsetsAnimationProgress.read(int)}
     * to obtain its shared static int fields (1109753110 = expected, -1546951913 = actual,
     * 485759512 = rolling seed; 97097858 is the decoy get on the throw path).
     */
    private static final int[] GUARD_FIELD_IDS = { 1109753110, -1546951913, 485759512, 97097858 };

    /**
     * The same four members as the build key (4th arg) of the resolver's
     * {@code IconCompatParcelizer(char,int,int,int,boolean,String,Class[])} — the overload
     * {@code read(id)} falls back to on a cache miss, where the build key identifies the declaring
     * class.
     */
    private static final int[] GUARD_BUILD_KEYS = { -1414280701, 1247663106, -178082547, -329306217 };

    static final int GUARD_ID_EXPECTED = 1109753110;      // o.cwx.read                  (guard's i)
    static final int GUARD_ID_ACTUAL = -1546951913;       // o.cwC.IconCompatParcelizer  (guard's i2)
    static final int GUARD_ID_SEED = 485759512;           // o.cwC.write                 (guard's i3)
    private static final int GUARD_ID_DECOY = 97097858;   // o.cwx.IconCompatParcelizer  (Object, throw path)

    /** Resolved guard fields, kept so diagnostics can dump their live values later. */
    private volatile Field guardFieldExpected;
    private volatile Field guardFieldActual;
    private volatile Field guardFieldSeed;

    /** Handles / build keys already reported, so each is logged exactly once. */
    private final Set<Integer> guardFieldSeen = newSet();
    private final Set<Integer> guardBuildSeen = newSet();
    /** GUARDTRIPLE samples written so far (the guards run often — keep the log readable). */
    private final AtomicInteger guardTripleLogs = new AtomicInteger();
    /** Throw-site classes already instrumented (once per class). */
    private final Set<Class<?>> guardThrowSiteHooked = newSet();

    /** Set that tolerates concurrent {@code add} from hooked app threads. */
    private static <T> Set<T> newSet() {
        return Collections.newSetFromMap(new ConcurrentHashMap<T, Boolean>());
    }

    // -----------------------------------------------------------------------------------------
    // Observation — log-only, nothing here mutates a result.
    // -----------------------------------------------------------------------------------------

    /**
     * GUARD-SITE: observe the guard's entry/exit so its inputs are captured at the exact moment of
     * a failure — including throws that never reach {@code o.getInstance}. Replicating the safe
     * branch (as {@link PackerBypass} does for {@code o.col$write}) is a separate, deliberate step
     * taken once a value is known to be wrong.
     */
    void hookGuardThrowSites(ClassLoader classLoader, String className, String methodName) {
        if (!DEBUG) return;
        Class<?> cls;
        try {
            cls = Hooks.findClass(className, classLoader);
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

    /**
     * GUARDFIELD, from the resolver: report the handles the guard uses, so we know which class owns
     * the shared static ints it validates. Nothing is mutated.
     *
     * <p>The seed is read last inside every guard invocation, so at that point expected/actual/seed
     * are all known — that is when the triple is sampled.
     */
    void observeResolverRead(int handle, Object member) {
        if (!isGuardFieldId(handle)) return;
        noteGuardField(handle, member);
        if (DEBUG && guardFieldSeen.add(handle)) {
            logGuardField("read(" + handle + ")", member);
        }
        if (DEBUG && handle == GUARD_ID_SEED && guardTripleLogs.getAndIncrement() < 10) {
            log(guardTriple("resolver-seed-read"));
        }
    }

    /**
     * GUARDFIELD, from the resolver's builder: {@code read(id)} returns null until the member is
     * cached, and on that miss the builder resolves the declaring class from (c,i,i2) and the member
     * from its name. Only the four keys the guard uses are observed, so the app's hot reflection
     * traffic is unaffected.
     */
    void observeBuilder(int key, Object name, Object member) {
        if (!isGuardBuildKey(key)) return;
        noteGuardField(guardIdForKey(key), member);
        if (DEBUG && guardBuildSeen.add(key)) {
            logGuardField("build(key=" + key + ", name=" + name + ")", member);
        }
    }

    // -----------------------------------------------------------------------------------------
    // The hash model
    // -----------------------------------------------------------------------------------------

    /**
     * Replays the {@code o.col$write.RemoteActionCompatParcelizer(cov.read)} verdict before letting
     * it run: {@code H(o.cwC.write, o.cwC.IconCompatParcelizer, colGateEnv(uptime))} against
     * {@code o.cwx.read}.
     *
     * @param out 0 = recomputed H, 1 = stored o.cwx.read
     * @return 1 when the gate compares equal (natural pass), -1 when it would enter the kill block,
     *         0 when the guard fields are not resolved yet
     */
    int colGateVerdict(int[] out) {
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
     * The packer's rolling hash pass, byte for byte as emitted by the dex:
     * {@code for (k = 0; k != 8; k++) i = (((((int)(j >> k)) & 255) + (i << 6)) + (i << 16)) - i;}.
     * Only bits 0..14 of {@code jval} ever reach the accumulator.
     */
    static int hashPass(int seed, long jval) {
        int i = seed;
        for (int k = 0; k < 8; k++) {
            int b = (int) ((jval >> k) & 0xFF);
            i = b + ((i << 6) + (i << 16) - i);
        }
        return i;
    }

    /** {@code o.col$write}'s environment term, its copy of the uptime decoy mix. Long math wraps like DEX. */
    static long colGateEnv(int uptimeMillis) {
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
     * GUARDTRIPLE: the live values the guard compares (expected / actual / seed) plus every clock
     * that feeds the rolling hash. Debug-only — this is what established the model above.
     */
    String guardTriple(String why) {
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

    // -----------------------------------------------------------------------------------------
    // Field bookkeeping
    // -----------------------------------------------------------------------------------------

    /** The already-resolved expected field, for dumps that must not trigger class loading. */
    Field expectedField() {
        return guardFieldExpected;
    }

    /** True if the resolver handle is one of the four ids the guard reads. */
    static boolean isGuardFieldId(int id) {
        for (int v : GUARD_FIELD_IDS) if (v == id) return true;
        return false;
    }

    /** True if the build key identifies a member the guard reads. */
    static boolean isGuardBuildKey(int key) {
        for (int v : GUARD_BUILD_KEYS) if (v == key) return true;
        return false;
    }

    /** Which class owns a guard field: name, static/type modifier, and the value it currently holds. */
    static void logGuardField(String via, Object member) {
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

    /** Remembers the resolved Field for each guard slot so the triple can be dumped later. */
    private void noteGuardField(int id, Object member) {
        if (!(member instanceof Field)) return;
        if (id == GUARD_ID_EXPECTED) guardFieldExpected = (Field) member;
        else if (id == GUARD_ID_ACTUAL) guardFieldActual = (Field) member;
        else if (id == GUARD_ID_SEED) guardFieldSeed = (Field) member;
    }

    /** Reverse of the builder's 4th arg (build key) → the equivalent resolver id. */
    private static int guardIdForKey(int key) {
        switch (key) {
            case -1414280701: return GUARD_ID_EXPECTED;
            case 1247663106: return GUARD_ID_ACTUAL;
            case -178082547: return GUARD_ID_SEED;
            case -329306217: return GUARD_ID_DECOY;
            default: return 0;
        }
    }

    /** Reads a guard's current int, or {@code Integer.MIN_VALUE} when it is not resolved. */
    static int guardInt(Field f) {
        if (f == null) return Integer.MIN_VALUE;
        try {
            return f.getInt(null);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }
}
