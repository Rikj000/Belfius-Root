package be.belfius.directmobile.android.root;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * The module app's own light/dark choice: {@code system} (the default), {@code dark} or {@code light}.
 *
 * <p>Stored in local {@link SharedPreferences} rather than in the framework's remote preferences —
 * unlike the two switches in {@link ModuleSettings} this value is a property of this app's UI only
 * (the hooks never read it), it has to be applied before the first view is inflated, and it has to
 * work while the framework service is unreachable. All three rules rule out remote preferences,
 * which only ever arrive asynchronously over a binder.
 */
final class UiTheme {
    /** Follow the system's dark/light setting. */
    static final String SYSTEM = "system";
    /** Always dark. */
    static final String DARK = "dark";
    /** Always light. */
    static final String LIGHT = "light";

    private static final String PREFS = "ui";
    private static final String KEY_THEME = "theme";

    private UiTheme() {
    }

    /** The stored choice; {@link #SYSTEM} when none was ever made. */
    static String get(Context context) {
        return prefs(context).getString(KEY_THEME, SYSTEM);
    }

    /** Stores the choice and applies it process-wide. */
    static void set(Context context, String theme) {
        prefs(context).edit().putString(KEY_THEME, theme).apply();
        applyMode(theme);
    }

    /**
     * Pushes the stored choice into AppCompat. Must run before {@code super.onCreate()} of the
     * activity, so that the configuration it installs is the one the theme and the dynamic colour
     * overlay are resolved against.
     */
    static void apply(Context context) {
        applyMode(get(context));
    }

    private static void applyMode(String theme) {
        if (DARK.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if (LIGHT.equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
