package be.belfius.directmobile.android.root;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * The module's settings screen: the app's own theme choice (local, see {@link UiTheme}) plus two
 * switches, both stored in the framework's remote preferences — see {@link ModuleSettings} for why:
 * {@code debug} gates the diagnostics, which are useful when
 * troubleshooting but add real per-call work on hot paths (they run inside the packer's audit, which
 * executes on the main thread in {@code dispatchDraw}); {@code scanner} <em>adds</em> the
 * isolated-scanner disarm on top of the per-method bypass, off by default so the shipped behaviour
 * survives an upgrade that never opens this screen.
 *
 * <p>The values are not stored locally: they are written to the framework's remote preferences
 * through the {@link io.github.libxposed.service.XposedService} we get when the framework notices
 * this app has launched. Both reading and writing therefore happen off the main thread (the service
 * binder arrives asynchronously), with the switches disabled until the current values are known.
 */
public class SettingsFragment extends Fragment {
    private MaterialSwitch debugSwitch;
    private MaterialSwitch scannerSwitch;
    /** Guards the listeners while the fragment writes a switch state itself. */
    private boolean updatingSwitch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Captured here, on the UI thread: the worker threads below must not touch fragment state.
        final FragmentActivity activity = getActivity();
        if (activity == null) return;

        debugSwitch = view.findViewById(R.id.switch_debug);
        scannerSwitch = view.findViewById(R.id.switch_scanner);
        debugSwitch.setEnabled(false);
        scannerSwitch.setEnabled(false);

        wireTheme(view);
        wire(activity, debugSwitch, ModuleSettings.KEY_DEBUG);
        wire(activity, scannerSwitch, ModuleSettings.KEY_SCANNER);

        // The footnote on screen stays to one line; the shell commands it replaced live behind the
        // info icon, where only someone who wants them has to look.
        view.findViewById(R.id.restart_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View icon) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_restart_dialog_title)
                        .setMessage(R.string.settings_restart_dialog_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });

        // Both values come from one binder round-trip sequence, so read them together: the service
        // bind is shared, and one UI update means the two switches cannot be half-populated.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Boolean debug = read(ModuleSettings.KEY_DEBUG);
                final Boolean scanner = read(ModuleSettings.KEY_SCANNER);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updatingSwitch = true;
                        debugSwitch.setEnabled(true);
                        scannerSwitch.setEnabled(true);
                        debugSwitch.setChecked(debug != null && debug);
                        scannerSwitch.setChecked(scanner != null && scanner);
                        updatingSwitch = false;
                        if (debug == null || scanner == null) {
                            Toast.makeText(activity,
                                    R.string.settings_service_unreachable, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "settings-read").start();
    }

    /**
     * The theme row: shows the stored choice and offers it as a single-choice dialog. Applies
     * immediately — unlike the switches this setting belongs to this app alone, so it needs no
     * restart and never touches the framework.
     */
    private void wireTheme(View view) {
        final TextView summary = view.findViewById(R.id.theme_summary);
        summary.setText(labelOf(UiTheme.get(requireContext())));
        view.findViewById(R.id.row_theme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View row) {
                final Context context = requireContext();
                final String[] themes = {UiTheme.SYSTEM, UiTheme.DARK, UiTheme.LIGHT};
                final String[] labels = {
                        context.getString(R.string.theme_system),
                        context.getString(R.string.theme_dark),
                        context.getString(R.string.theme_light)};
                int checked = 0;
                for (int i = 0; i < themes.length; i++) {
                    if (themes[i].equals(UiTheme.get(context))) checked = i;
                }
                final int selected = checked;
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.settings_theme_title)
                        .setSingleChoiceItems(labels, selected,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        UiTheme.set(context, themes[which]);
                                        summary.setText(labels[which]);
                                        dialog.dismiss();
                                    }
                                })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
    }

    /** The label of the given stored theme value. */
    private int labelOf(String theme) {
        if (UiTheme.DARK.equals(theme)) return R.string.theme_dark;
        if (UiTheme.LIGHT.equals(theme)) return R.string.theme_light;
        return R.string.theme_system;
    }

    private void wire(final FragmentActivity activity, final MaterialSwitch switchView,
                      final String key) {
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
                        if (written) return;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updatingSwitch = true;
                                switchView.setChecked(!wanted);
                                updatingSwitch = false;
                                Toast.makeText(activity,
                                        R.string.settings_service_unreachable,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
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
