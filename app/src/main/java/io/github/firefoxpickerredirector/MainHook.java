package io.github.firefoxpickerredirector;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "FirefoxPickerRedirector";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!Config.FIREFOX_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof Activity) || param.args == null || param.args.length < 2) {
                        return;
                    }
                    if (!(param.args[0] instanceof Intent) || !(param.args[1] instanceof Integer)) {
                        return;
                    }

                    Activity activity = (Activity) param.thisObject;
                    Intent original = (Intent) param.args[0];
                    int requestCode = (Integer) param.args[1];
                    Intent rewritten = IntentRewriter.rewrite(activity, original, requestCode);
                    if (rewritten != original) {
                        param.args[0] = rewritten;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": hook failed");
                    XposedBridge.log(t);
                }
            }
        };

        // Deprecated APIs are intentionally hooked because AndroidX ActivityResultRegistry eventually
        // reaches Activity.startActivityForResult() when it launches an external picker.
        XposedHelpers.findAndHookMethod(
                Activity.class,
                "startActivityForResult",
                Intent.class,
                int.class,
                hook
        );

        XposedHelpers.findAndHookMethod(
                Activity.class,
                "startActivityForResult",
                Intent.class,
                int.class,
                Bundle.class,
                hook
        );

        XposedBridge.log(TAG + ": active in " + lpparam.packageName + " / " + lpparam.processName);
    }
}
