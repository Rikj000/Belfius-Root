package be.belfius.directmobile.android.root;

/**
 * Which Belfius build is running, and therefore which hook set it needs.
 *
 * <p>The obfuscated names this module hooks are only stable <em>within</em> a build: {@code o.ckg} is
 * the {@code IconCompat} parceler in v26.3.0 but an ordinary class with single-letter members in
 * v25.4.1, and {@code o.bII} has {@code hashCode} in v26.3.0 while its v25.4.1 namesake only has
 * {@code c}/{@code d}. Nothing collides today, which is why old builds still work — but only by luck,
 * and at the price of every legacy launch attempting the v26.x set and failing. Choosing the set from
 * the app's own versionCode instead makes that intentional.
 */
enum VersionProfile {
    /** v25.4.1 and older: only the original single-letter brute-force scan applies. */
    LEGACY("legacy"),
    /** v26.1.0 (and 26.1.x/26.2.x): modern detection targets plus the injected-audit plumbing. */
    V26_1("v26.1"),
    /** v26.3.0: current build. Same set as v26.1, plus the verdict-driven packer path. */
    V26_3("v26.3"),
    /** Newer than this module knows about: run every hook set and say so loudly. */
    UNKNOWN("unknown");

    /** v26.1.0 — the first build whose detection stack this module maps. */
    private static final long FIRST_MODERN = 260781255L;
    /** v26.3.0 — the newest build this module has been verified against. */
    private static final long CURRENT = 262601628L;

    private final String label;

    VersionProfile(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** True when the v26.x set applies: detection targets, packer/RASP, resolver, UI integrity. */
    boolean wantsModernHooks() {
        return this != LEGACY;
    }

    /** Maps a versionCode to a profile; anything newer than {@link #CURRENT} is UNKNOWN on purpose. */
    static VersionProfile of(long versionCode) {
        if (versionCode > CURRENT) return UNKNOWN;
        if (versionCode == CURRENT) return V26_3;
        if (versionCode >= FIRST_MODERN) return V26_1;
        return LEGACY;
    }
}
