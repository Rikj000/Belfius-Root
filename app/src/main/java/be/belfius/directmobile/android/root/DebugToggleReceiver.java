package be.belfius.directmobile.android.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Lets a script flip a module setting without touching the UI:
 *
 * <pre>
 * adb shell am broadcast -a be.belfius.directmobile.android.root.SET_DEBUG   --ez debug   true
 * adb shell am broadcast -a be.belfius.directmobile.android.root.SET_SCANNER --ez scanner true
 * </pre>
 *
 * <p>Used by {@code agentic_dev/scripts/reload-and-test.sh} so an A/B run (diagnostics on vs off,
 * scanner disarm vs the shipped bypass) needs no manual tapping. The Belfius app must be restarted
 * afterwards for the change to apply. Exported on purpose; it can only flip settings, it does not
 * change any bypass behaviour by itself.
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
        if (intent == null) return;
        String action = intent.getAction();
        final String key;
        if (ModuleSettings.ACTION_SET_DEBUG.equals(action)) key = ModuleSettings.KEY_DEBUG;
        else if (ModuleSettings.ACTION_SET_SCANNER.equals(action)) key = ModuleSettings.KEY_SCANNER;
        else return;
        if (!intent.hasExtra(key)) return;
        final boolean enabled = intent.getBooleanExtra(key, false);
        ModuleSettings.init();
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean written = false;
                try {
                    written = ModuleSettings.write(key, enabled);
                } catch (InterruptedException ignored) {
                }
                Log.i("BelfiusRoot", key + " toggle -> " + enabled + " (written=" + written + ")");
                pending.setResultCode(written ? 0 : 1);
                pending.setResultData(key + "=" + (enabled ? "on" : "off"));
                pending.finish();
            }
        }, "settings-toggle").start();
    }
}
