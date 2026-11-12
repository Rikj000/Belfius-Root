package be.belfius.directmobile.android.root;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.appbar.MaterialToolbar;

/**
 * The module app's single activity: an icon-only {@link BottomNavigationView} with two destinations,
 * About (the default) and Settings.
 *
 * <p>Both destinations are kept alive in the same {@link FragmentContainerView} and switched with
 * {@code hide()} / {@code show()} rather than {@code replace()}, so the settings screen does not
 * re-read the framework preferences every time you come back to it.
 *
 * <p>The values shown on the Settings tab live in the framework's remote preferences — see
 * {@link ModuleSettings} for why. Both reading and writing happen off the main thread (the service
 * binder arrives asynchronously).
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG_ABOUT = "about";
    private static final String TAG_SETTINGS = "settings";
    private static final String STATE_SELECTED_TAB = "selected-tab";

    private MaterialToolbar toolbar;
    /** The tab to show; restored across a configuration change / process death. */
    private int selectedTab = R.id.nav_about;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate(): the night mode this installs is the configuration the theme and
        // the dynamic colour overlay below are resolved against.
        UiTheme.apply(this);
        super.onCreate(savedInstanceState);
        // After super.onCreate() and before the views: wallpaper colours on Android 12+, a no-op
        // elsewhere, and now already reading the night mode chosen above.
        DynamicColors.applyToActivityIfAvailable(this);
        setContentView(R.layout.activity_main);

        // Every entry point of the module app registers the framework service listener before
        // anything may read or write the settings.
        ModuleSettings.init();

        toolbar = findViewById(R.id.toolbar);
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);

        FragmentManager fragments = getSupportFragmentManager();
        if (savedInstanceState == null) {
            Fragment settings = new SettingsFragment();
            // commitNow(): the fragments have to exist before select() looks them up by tag.
            fragments.beginTransaction()
                    .add(R.id.container, new AboutFragment(), TAG_ABOUT)
                    .add(R.id.container, settings, TAG_SETTINGS)
                    .hide(settings)
                    .commitNow();
        } else {
            selectedTab = savedInstanceState.getInt(STATE_SELECTED_TAB, R.id.nav_about);
        }

        bottomNav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                return select(item.getItemId());
            }
        });
        // select() first: the default tab does not fire the listener, and on a restored tab it
        // would fire it a second time, which is harmless because select() is idempotent.
        select(selectedTab);
        bottomNav.setSelectedItemId(selectedTab);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_TAB, selectedTab);
    }

    /** Shows the given destination and titles the toolbar after it. */
    private boolean select(int tabId) {
        if (tabId != R.id.nav_about && tabId != R.id.nav_settings) return false;
        FragmentManager fragments = getSupportFragmentManager();
        Fragment about = fragments.findFragmentByTag(TAG_ABOUT);
        Fragment settings = fragments.findFragmentByTag(TAG_SETTINGS);
        if (about == null || settings == null) return false;

        selectedTab = tabId;
        FragmentTransaction transaction = fragments.beginTransaction().setReorderingAllowed(true);
        if (tabId == R.id.nav_settings) {
            transaction.hide(about).show(settings);
            toolbar.setTitle(R.string.nav_settings);
        } else {
            transaction.hide(settings).show(about);
            toolbar.setTitle(R.string.app_name);
        }
        transaction.commit();
        return true;
    }
}
