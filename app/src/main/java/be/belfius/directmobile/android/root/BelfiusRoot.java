package be.belfius.directmobile.android.root;

import static de.robv.android.xposed.XposedBridge.log;

import android.os.Build;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** @noinspection unused*/
public class BelfiusRoot implements IXposedHookLoadPackage {
    private static final String BELFIUS_PKG = "be.belfius.directmobile.android";
    private static final Boolean HAS_ROOT = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!BELFIUS_PKG.equals(loadPackageParam.packageName)) return;

        // Fetch Belfius version
        int belfiusVersion = getPackageVersion(loadPackageParam);
        log("Hooking package " + BELFIUS_PKG + " version: " + belfiusVersion);

        // Main Hook - v24.5.2.0 (251701137) - v99.9.9.9 (999999999)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                "e", belfiusVersion, 251701137, null);

        // Legacy Hook - v24.5.1.0 (250791650) - v24.5.2.0 (251701137, exclusive)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                "b", belfiusVersion, 250791650, 251701136);

        // Legacy Hook - v24.4.1.0 (250361401) - v24.5.1.0 (250791650, exclusive)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                "e", belfiusVersion, 250361401, 250791649);

        // Legacy Hook - v24.4.0.0 (243321017) - v24.4.1.0 (250361401, exclusive)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                "a", belfiusVersion, 243321017, 250361400);

        // Legacy Hook - v24.3.1.0 (242742122) - v24.4.0.0 (243321017, exclusive)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                "b", belfiusVersion, 242742122, 243321016);

        // Legacy Hook - v00.0.0.0 (000000000) - v24.3.1.0 (242742122, exclusive)
        findAndHookRootCheck(
                loadPackageParam,
                "be.belfius.android.security.utils.RootUtils$isDeviceRooted$2",
                "b", belfiusVersion, null, 242742121);
    }

    private void findAndHookRootCheck(
            XC_LoadPackage.LoadPackageParam loadPackageParam,
            String className,
            String methodName,
            int currentVersion,
            Integer minVersion,
            Integer maxVersion) {

        // Initialize method name string helper for logging
        String methodNameStr = className + "." + methodName + "()";

        // Validate if the currentVersion supports the provided hook, skip if not
        if (!validateHookSupport(methodNameStr, currentVersion, minVersion, maxVersion)) { return; }

        try {
            XposedHelpers.findAndHookMethod(
                    className,
                    loadPackageParam.classLoader,
                    methodName,
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(HAS_ROOT);
                        }
                    });

            log("Successfully hooked " + methodNameStr + "!");
        } catch (Throwable t) {
            log("Failed to hook " + methodNameStr + "...");
            log(t);
        }
    }

    private boolean validateHookSupport(
            String methodNameStr,
            int currentVersion,
            Integer minVersion,
            Integer maxVersion) {

        // Validate if the currentVersion supports the provided hook, skip if not
        if (minVersion == null) { minVersion = 0; }
        if (maxVersion == null) { maxVersion = 999999999; }
        if (currentVersion < minVersion || currentVersion > maxVersion) {
            log("Skipping hook " + methodNameStr + "...");
            log ("Current version " + currentVersion + " not in hook support window " +
                    "(min: " + minVersion + ", max: " + maxVersion + ")...");
            return false;
        }

        return true;
    }

    private static int getPackageVersion(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        int versionCode = 0;
        File apkPath = new File(loadPackageParam.appInfo.sourceDir);
        Class<?> pkgParserClass = XposedHelpers.findClass(
                "android.content.pm.PackageParser", loadPackageParam.classLoader);

        // Check Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Handle Android 11 and above
            Object pkgLite = XposedHelpers.callStaticMethod(
                    pkgParserClass, "parsePackageLite", apkPath, 0);
            versionCode = XposedHelpers.getIntField(pkgLite, "versionCode");
        } else {
            // Handle Android 10 and below
            try {
                Object parser = pkgParserClass.newInstance();
                Object pkg = XposedHelpers.callMethod(parser, "parsePackage", apkPath, 0);
                versionCode = XposedHelpers.getIntField(pkg, "mVersionCode");
            } catch (Throwable t) {
                log("Failed to get package version...");
                log(t);
            }
        }

        return versionCode;
    }
}
