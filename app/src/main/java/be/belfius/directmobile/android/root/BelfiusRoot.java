package be.belfius.directmobile.android.root;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import static be.belfius.directmobile.android.root.ModuleLog.DEBUG;
import static be.belfius.directmobile.android.root.ModuleLog.logAlways;
import static be.belfius.directmobile.android.root.ModuleLog.readDebugPref;

/**
 * The module's entry point, written against the modern libxposed API (API 102).
 *
 * <p>The framework instantiates this class once per process, calls {@link #onModuleLoaded}, and
 * then {@link #onPackageLoaded} for each package it loads. Everything else lives in the hook
 * classes: {@link BelfiusHooks} installs and maintains the hooks, {@link Hooks} wraps the
 * framework's hook plumbing, {@link ModuleLog} owns the diagnostics switch.
 */
/** @noinspection unused*/
public class BelfiusRoot extends XposedModule {
    static final String BELFIUS_PKG = "be.belfius.directmobile.android";

    /**
     * Captured at module load: the one-off verdict is emitted only in the app's main process, and
     * {@link XposedModuleInterface.PackageLoadedParam} no longer carries the process name.
     */
    private String processName;

    private final BelfiusHooks hooks = new BelfiusHooks();

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        ModuleLog.attach(this);
        Hooks.attach(this);
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
        IsolatedScannerBypass.readSetting();
        ModuleLog.log("handleLoadPackage for " + BELFIUS_PKG + " loader=" + classLoader);
        try {
            ModuleLog.log("   appInfo=" + param.getApplicationInfo());
        } catch (Throwable ignored) {}

        hooks.install(classLoader, processName);
    }
}
