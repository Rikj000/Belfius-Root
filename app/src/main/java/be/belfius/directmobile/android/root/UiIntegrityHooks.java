package be.belfius.directmobile.android.root;

import java.util.concurrent.atomic.AtomicBoolean;

import static be.belfius.directmobile.android.root.Diagnostics.dumpFields;
import static be.belfius.directmobile.android.root.Hooks.findClass;
import static be.belfius.directmobile.android.root.Hooks.hookAllMethodsNamed;
import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.log;
import static be.belfius.directmobile.android.root.ModuleLog.threadLabel;

/**
 * The second integrity layer, reached only after {@link PackerBypass} neutralises the Firebase OOM:
 * a set of classes ({@code o.bII} and friends) that run during UI layout on the main thread.
 *
 * <p>Two real neutralisations live here — the login thread factory that throws NPE, and the
 * {@code o.bII.hashCode} divide-by-zero — plus a debug-only field dump of each object, which is how
 * the divisor was found in the first place. Their classes are not present in any carved dex, so
 * installation is driven by the class-load / resolver watchers.
 */
final class UiIntegrityHooks {
    /**
     * Installation gate. The resolver hook fires on every native-bridge resolution in the audit's
     * hot loop, so an installer that does its work before setting a flag re-enters itself: duplicate
     * hooks leak a JNI global ref each, which previously aborted the app with "global reference table
     * overflow (max=51200)". CAS at entry; released again if nothing was hookable yet, so a later
     * call from the injected loader still installs exactly once.
     */
    private final AtomicBoolean armed = new AtomicBoolean();

    boolean isArmed() {
        return armed.get();
    }

    void hookUiIntegrityDigger(ClassLoader classLoader) {
        if (!armed.compareAndSet(false, true)) return;
        boolean hookedAny = false;

        // Login path: inflating the login layout builds an animator ThreadPool executor whose
        // ThreadFactory (o.bBF) deliberately throws a bare NPE on its own statics check. Substitute
        // a plain Thread so main survives.
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

        String[] targets = { "o.bII", "o.bFe", "o.bDV", "o.bEV", "o.bIT" };
        for (String className : targets) {
            try {
                Class<?> c = findClass(className, classLoader);
                final AtomicBoolean once = new AtomicBoolean();
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
                    // o.bII.hashCode divides by a zero field on this build and aborts the main
                    // thread during layout. Consume the throw and supply a stable identity hash.
                    if (thrown instanceof ArithmeticException) {
                        log("UI-INTEGRITY hashCode NEUTRALIZED on " + threadLabel());
                        return System.identityHashCode(chain.getThisObject()) * 31;
                    }
                    if (thrown != null) throw thrown;
                    return result;
                });
                hookedAny |= h > 0;
                log("UI-integrity digger on " + className + " hashCode (" + h + " hooks)");

                // Purely diagnostic — the field dump that hunts the zero divisor; debug builds only.
                if (DEBUG) {
                    final AtomicBoolean onceAttributes = new AtomicBoolean();
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
            armed.set(false);
            log("UI-integrity digger: nothing hooked yet on " + classLoader + ", will retry");
        } else {
            log("UI-integrity digger ARMED (hooked something)");
        }
    }
}
