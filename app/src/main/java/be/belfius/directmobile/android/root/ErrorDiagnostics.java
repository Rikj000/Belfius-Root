package be.belfius.directmobile.android.root;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static be.belfius.directmobile.android.root.Hooks.findClass;
import static be.belfius.directmobile.android.root.Hooks.hookAllMethodsNamed;
import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.threadLabel;

/**
 * Diagnoses the generic "Algemene fout / Er is een fout opgetreden" dialog.
 *
 * <p>Belfius maps every operation failure through {@code o.getInstance.IconCompatParcelizer(...)}:
 * known banking errors get a specific message, anything unmapped falls through to the generic pair —
 * and the app logs almost nothing itself. Observing the throwable at that mapping point is the only
 * way to see why a tap failed. Log-only: no result is ever changed here.
 *
 * <p>Debug builds only — these hooks are never installed while the toggle is off.
 */
final class ErrorDiagnostics {
    /** Classes already instrumented, once per loader. */
    private final Set<Class<?>> hooked =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private final RaspGuard guard;

    ErrorDiagnostics(RaspGuard guard) {
        this.guard = guard;
    }

    void hookErrorDiagnostics(ClassLoader classLoader) {
        if (!DEBUG) return;
        hookErrorClass(classLoader, "o.getInstance");
    }

    private void hookErrorClass(ClassLoader classLoader, String className) {
        Class<?> cls;
        try {
            cls = findClass(className, classLoader);
        } catch (Throwable t) {
            return; // not on this loader (yet)
        }
        if (!hooked.add(cls)) return;

        hookAllMethodsNamed(cls, "IconCompatParcelizer", chain -> {
            for (Object a : chain.getArgs()) {
                if (a instanceof Throwable) {
                    Throwable t = (Throwable) a;
                    log("ERRDIAG " + className + "#" + chain.getExecutable().getName()
                            + " exception=" + t.getClass().getName() + ": " + t.getMessage()
                            + " on " + threadLabel());
                    // Snapshot the guard's three statics plus the clocks the hash feeds, so a failing
                    // run's exact inputs can be replayed offline.
                    log(guard.guardTriple("via=" + className + "#" + chain.getExecutable().getName()));
                    log(t);
                }
            }
            return chain.proceed();
        });
        log("ERRDIAG installed on " + className + " via " + classLoader);
    }
}
