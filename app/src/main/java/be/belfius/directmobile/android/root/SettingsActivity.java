package be.belfius.directmobile.android.root;

import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * The module's settings screen (also the module's launcher entry).
 *
 * <p>Currently one switch: {@code debug}. See {@link ModuleSettings} for why it exists — the
 * diagnostics are useful when troubleshooting but add real per-call work on hot paths (they run
 * inside the packer's audit, which executes on the main thread in {@code dispatchDraw}), so they
 * are off unless explicitly enabled.
 *
 * <p>The value is not stored locally: it is written to the framework's remote preferences through
 * the {@link io.github.libxposed.service.XposedService} we get when the framework notices this app
 * has launched. Both reading and writing therefore happen off the main thread (the service binder
 * arrives asynchronously), with the switch disabled until the current value is known.
 */
public class SettingsActivity extends AppCompatActivity {
    private SwitchMaterial debugSwitch;
    /** Guards the listener while the activity writes the switch state itself. */
    private boolean updatingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        debugSwitch = findViewById(R.id.switch_debug);
        debugSwitch.setEnabled(false);
        ModuleSettings.init();

        debugSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitch) return;
                final boolean wanted = isChecked;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean written = writeDebug(wanted);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (!written) {
                                    updatingSwitch = true;
                                    debugSwitch.setChecked(!wanted);
                                    updatingSwitch = false;
                                    Toast.makeText(SettingsActivity.this,
                                            R.string.settings_service_unreachable, Toast.LENGTH_LONG).show();
                                }
                            }
                        });
                    }
                }, "settings-write").start();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                Boolean value = null;
                try {
                    value = ModuleSettings.read();
                } catch (InterruptedException ignored) {
                }
                final Boolean stored = value;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updatingSwitch = true;
                        debugSwitch.setEnabled(true);
                        debugSwitch.setChecked(stored != null && stored);
                        updatingSwitch = false;
                        if (stored == null) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_service_unreachable, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "settings-read").start();
    }

    private static boolean writeDebug(boolean enabled) {
        try {
            return ModuleSettings.setDebug(enabled);
        } catch (InterruptedException e) {
            return false;
        }
    }
}
