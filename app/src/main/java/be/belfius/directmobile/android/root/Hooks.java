package be.belfius.directmobile.android.root;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedInterface;

import static be.belfius.directmobile.android.root.ModuleLog.log;

/**
 * Hook plumbing for the modern libxposed API (API 102) plus the reflection helpers the framework
 * no longer ships (there is no {@code XposedHelpers} in the modern API).
 *
 * <p>The one rule that matters when reading any interceptor below: <b>the original only runs if
 * {@code chain.proceed()} is called</b>. In the legacy API an after-hook always ran; here a body
 * that forgets to proceed silently becomes a replacement hook.
 *
 * <p>Static like {@link ModuleLog}: it wraps {@code XposedInterface.hook()}, which is bound to this
 * process's module instance and set once in {@code onModuleLoaded}.
 */
final class Hooks {
    /** This process's framework interface, set from {@code BelfiusRoot.onModuleLoaded}. */
    private static XposedInterface module;

    private Hooks() {
    }

    static void attach(XposedInterface moduleInstance) {
        module = moduleInstance;
    }

    /** Interceptor body; uses {@code chain.getArg(i)} / {@code chain.proceed()} directly. */
    interface Intercept {
        Object run(XposedInterface.Chain chain) throws Throwable;
    }

    /** After-hook body: the original already ran and returned {@code result}. */
    interface After {
        Object run(XposedInterface.Chain chain, Object result) throws Throwable;
    }

    /**
     * Around-hook body: the original already ran and either returned {@code result} or threw
     * {@code thrown}. The body MUST rethrow {@code thrown} when it does not substitute a result.
     */
    interface Around {
        Object run(XposedInterface.Chain chain, Object result, Throwable thrown) throws Throwable;
    }

    /** {@code Class.forName} with initialisation off, i.e. the legacy findClass helper. */
    static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    /** Declared field, or one inherited, made accessible — the legacy findField helper. */
    static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
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

    /** Reads a static int field, the legacy getStaticIntField helper. */
    static int getStaticIntField(Class<?> clazz, String name)
            throws NoSuchFieldException, IllegalAccessException {
        return findField(clazz, name).getInt(null);
    }

    static boolean isStatic(Method m) {
        return Modifier.isStatic(m.getModifiers());
    }

    /** Hooks every declared method of clazz whose name matches (signature-agnostic). */
    static int hookAllMethodsNamed(Class<?> clazz, String name, Intercept body) {
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

    private static XposedInterface.HookHandle hookOne(Class<?> clazz, Method m, Intercept body) {
        try {
            log("hooking " + clazz.getName() + "#" + m + (Modifier.isStatic(m.getModifiers()) ? " (static)" : ""));
            m.setAccessible(true);
            return module.hook(m).intercept(body::run);
        } catch (Throwable t) {
            log("hookMethod failed " + clazz.getName() + "#" + m + ": " + t);
            return null;
        }
    }

    /** Runs {@code body} after the original; an exception from the original still propagates. */
    static XposedInterface.HookHandle after(Method m, After body) {
        return hookOne(m.getDeclaringClass(), m, chain -> body.run(chain, chain.proceed()));
    }

    /** Runs {@code body} with the original's outcome; the body decides what to return / rethrow. */
    static XposedInterface.HookHandle around(Method m, Around body) {
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
     * Resolves {@code className#methodName} on the given loader and hooks it. Throws when the class
     * or the method is missing — several call sites rely on that to know their hooks were installed.
     */
    static XposedInterface.HookHandle hookMethod(ClassLoader classLoader, String className,
                                                 String methodName, Class<?>[] parameterTypes,
                                                 Intercept body) throws Throwable {
        Class<?> clazz = findClass(className, classLoader);
        Method m = clazz.getDeclaredMethod(methodName, parameterTypes);
        return hookOne(clazz, m, body);
    }
}
