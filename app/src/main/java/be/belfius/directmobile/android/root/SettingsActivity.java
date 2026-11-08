package be.belfius.directmobile.android.root;

import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * The module's settings screen (also the module's launcher entry).
 *
 * <p>Two switches, both stored in the framework's remote preferences — see {@link ModuleSettings}
 * for why: {@code debug} gates the diagnostics, which are useful when troubleshooting but add real
 * per-call work on hot paths (they run inside the packer's audit, which executes on the main thread
 * in {@code dispatchDraw}); {@code scanner} <em>adds</em> the isolated-scanner disarm on top of the
 * per-method bypass, off by default so the shipped behaviour survives an upgrade that never opens
 * this screen.
 *
 * <p>The values are not stored locally: they are written to the framework's remote preferences
 * through the {@link io.github.libxposed.service.XposedService} we get when the framework notices
 * this app has launched. Both reading and writing therefore happen off the main thread (the service
 * binder arrives asynchronously), with the switches disabled until the current values are known.
 */
public class SettingsActivity extends AppCompatActivity {
    private SwitchMaterial debugSwitch;
    private SwitchMaterial scannerSwitch;
    /** Guards the listeners while the activity writes a switch state itself. */
    private boolean updatingSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        debugSwitch = findViewById(R.id.switch_debug);
        scannerSwitch = findViewById(R.id.switch_scanner);
        debugSwitch.setEnabled(false);
        scannerSwitch.setEnabled(false);
        ModuleSettings.init();

        wire(debugSwitch, ModuleSettings.KEY_DEBUG);
        wire(scannerSwitch, ModuleSettings.KEY_SCANNER);

        // Both values come from one binder round-trip sequence, so read them together: the service
        // bind is shared, and one UI update means the two switches cannot be half-populated.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Boolean debug = read(ModuleSettings.KEY_DEBUG);
                final Boolean scanner = read(ModuleSettings.KEY_SCANNER);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updatingSwitch = true;
                        debugSwitch.setEnabled(true);
                        scannerSwitch.setEnabled(true);
                        debugSwitch.setChecked(debug != null && debug);
                        scannerSwitch.setChecked(scanner != null && scanner);
                        updatingSwitch = false;
                        if (debug == null || scanner == null) {
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_service_unreachable, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "settings-read").start();
    }

    private void wire(final SwitchMaterial switchView, final String key) {
        switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitch) return;
                final boolean wanted = isChecked;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean written = false;
                        try {
                            written = ModuleSettings.write(key, wanted);
                        } catch (InterruptedException ignored) {
                        }
                        if (!written) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updatingSwitch = true;
                                    switchView.setChecked(!wanted);
                                    updatingSwitch = false;
                                    Toast.makeText(SettingsActivity.this,
                                            R.string.settings_service_unreachable,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                }, "settings-write").start();
            }
        });
    }

    private static Boolean read(String key) {
        try {
            return ModuleSettings.read(key);
        } catch (InterruptedException e) {
            return null;
        }
    }
}
