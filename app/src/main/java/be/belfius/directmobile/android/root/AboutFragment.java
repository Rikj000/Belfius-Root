package be.belfius.directmobile.android.root;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default destination: who the module is, which build is installed, where to find the project,
 * and whether the framework service behind {@link ModuleSettings} is reachable.
 *
 * <p>The framework identity is the only value on this screen that is not static: the service binder
 * arrives asynchronously after the app is launched, so it is read on a background thread and the
 * card stays at "Checking…" until it answers.
 */
public class AboutFragment extends Fragment {
    private static final String URL_GITHUB = "https://github.com/Rikj000/Belfius-Root";
    private static final String URL_XDA =
            "https://xdaforums.com/t/release-v1-0-0-belfius-root.4680025/";

    /**
     * Vector reports {@code "2.2 (3080) 88f8e1fa-JingMatrix-Vector"} — version, build number, commit
     * hash, author. The card shows the readable parts of that: version and author.
     */
    private static final Pattern VERSION_WITH_AUTHOR =
            Pattern.compile("^(\\S+)(?:\\s+\\([^)]*\\))?\\s+[0-9a-fA-F]{6,}-(.+)$");

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_about, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Captured here, on the UI thread: the worker thread below must not touch fragment state.
        final FragmentActivity activity = getActivity();
        if (activity == null) return;

        TextView version = view.findViewById(R.id.about_version);
        version.setText(readVersion());

        view.findViewById(R.id.link_github).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View link) {
                open(URL_GITHUB);
            }
        });
        view.findViewById(R.id.link_xda).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View link) {
                open(URL_XDA);
            }
        });

        final TextView identityView = view.findViewById(R.id.about_framework_identity);
        final TextView apiView = view.findViewById(R.id.about_framework_api);
        identityView.setText(R.string.about_status_checking);
        apiView.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                ModuleSettings.Framework framework = null;
                try {
                    framework = ModuleSettings.framework();
                } catch (InterruptedException ignored) {
                }
                final ModuleSettings.Framework found = framework;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (found == null) {
                            identityView.setText(R.string.about_status_unreachable);
                            apiView.setVisibility(View.GONE);
                            return;
                        }
                        identityView.setText(identity(found.name, found.version));
                        apiView.setText(getString(R.string.about_api, found.apiVersion));
                        apiView.setVisibility(View.VISIBLE);
                    }
                });
            }
        }, "about-status").start();
    }

    /** {@code "Vector"} + {@code "2.2 (3080) 88f8e1fa-JingMatrix-Vector"} → {@code "Vector 2.2 (JingMatrix-Vector)"}. */
    private static String identity(String name, String version) {
        Matcher matcher = VERSION_WITH_AUTHOR.matcher(version);
        if (matcher.matches()) {
            return name + " " + matcher.group(1) + " (" + matcher.group(2) + ")";
        }
        return name + " " + version;
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            // No browser installed: nothing sensible to show, and the link is a convenience.
        }
    }

    private String readVersion() {
        try {
            PackageManager manager = requireContext().getPackageManager();
            PackageInfo info = manager.getPackageInfo(requireContext().getPackageName(), 0);
            return getString(R.string.about_version, info.versionName);
        } catch (PackageManager.NameNotFoundException e) {
            return getString(R.string.about_version, "");
        }
    }
}
