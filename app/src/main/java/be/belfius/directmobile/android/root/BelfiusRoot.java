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

        int belfiusVersion = getPackageVersion(loadPackageParam);
        log("Hooking package " + BELFIUS_PKG + " version: " + belfiusVersion);

        // Check Belfius version
        if (belfiusVersion >= 242742122) {
            // Hook into root check of newer Belfius versions, tested on v24.3.1.0 (242742122)
            findAndHookRootCheck(
                    loadPackageParam,
                    "be.belfius.android.widget.security.security.utils.RootUtils$isDeviceRooted$2",
                    "b");
        } else {
            // Hook into root check of older Belfius versions, tested on v24.1.0 (240801805)
            findAndHookRootCheck(
                    loadPackageParam,
                    "be.belfius.android.security.utils.RootUtils$isDeviceRooted$2",
                    "b");
        }
    }

    private void findAndHookRootCheck(
            XC_LoadPackage.LoadPackageParam loadPackageParam,
            String className,
            String methodName) {
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
