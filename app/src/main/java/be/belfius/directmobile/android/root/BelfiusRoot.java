package be.belfius.directmobile.android.root;

import static de.robv.android.xposed.XposedBridge.log;

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
        log("Hooking package " + BELFIUS_PKG);

        XposedHelpers.findAndHookMethod(
                "be.belfius.android.security.utils.RootUtils$isDeviceRooted$2",
                loadPackageParam.classLoader,
                "b",
                new XC_MethodHook() {

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(HAS_ROOT);
                    }
                });
    }
}
