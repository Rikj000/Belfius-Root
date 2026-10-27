package be.belfius.directmobile.android.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lets a script flip the debug toggle without touching the UI:
 *
 * <pre>
 * adb shell am broadcast -a be.belfius.directmobile.android.root.SET_DEBUG --ez debug true
 * adb shell am broadcast -a be.belfius.directmobile.android.root.SET_DEBUG --ez debug false
 * </pre>
 *
 * <p>Used by {@code agentic_dev/scripts/reload-and-test.sh} so an A/B run (diagnostics on vs off)
 * needs no manual tapping. The Belfius app must be restarted afterwards for the change to apply.
 * Exported on purpose; it can only toggle logging, it does not change any bypass behaviour.
 *
 * <p>The write goes to the framework's remote preferences through the Xposed service, which the
 * framework sends asynchronously after this app is launched; the receiver therefore waits (in a
 * background thread obtained through {@link #goAsync()}) for that binder and reports the outcome in
 * its broadcast result, so a script can tell whether the toggle actually landed.
 */
public class DebugToggleReceiver extends BroadcastReceiver {
    public static final String ACTION = ModuleSettings.ACTION_SET_DEBUG;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!intent.hasExtra(ModuleSettings.KEY_DEBUG)) return;
        final boolean enabled = intent.getBooleanExtra(ModuleSettings.KEY_DEBUG, false);
        ModuleSettings.init();
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean written = false;
                try {
                    written = ModuleSettings.setDebug(enabled);
                } catch (InterruptedException ignored) {
                }
                Log.i("BelfiusRoot", "debug toggle -> " + enabled + " (written=" + written + ")");
                pending.setResultCode(written ? 0 : 1);
                pending.setResultData(enabled ? "debug=on" : "debug=off");
                pending.finish();
            }
        }, "debug-toggle").start();
    }
}
