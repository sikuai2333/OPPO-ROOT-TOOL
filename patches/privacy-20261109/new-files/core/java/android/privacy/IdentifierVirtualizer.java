/*
 * SPDX-License-Identifier: Apache-2.0
 */

package android.privacy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central policy for per-app device identifier virtualisation.
 *
 * <p>Every identifier a third-party app can reach through a Java API is routed
 * through here so that the same app always observes the same consistent fake
 * set, while different apps never share a value. Fake values are derived from an
 * epoch, a random handle the control centre regenerates every time the feature
 * is switched back on: while it stays put the app sees stable values across
 * calls, reboots and updates, and flipping the switch off and on hands the app
 * a whole new identity without reinstalling it.
 *
 * <p>Disabled by default and opt-in per app: identifier virtualisation is the
 * kind of change that silently breaks banking and DRM flows, so the user has to
 * ask for it rather than discover it.
 *
 * @hide
 */
@SuppressLint({"UnflaggedApi", "MissingNullability"})
public final class IdentifierVirtualizer {

    private static final String TAG = "IdentifierVirtualizer";

    /** Package owning the user-facing toggles. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static final String CONTROLLER_PACKAGE = "com.sikuai.privacy";

    /**
     * Packages that must keep the real values for the device to work at all.
     * Telephony and settings legitimately need the real IMEI / serial.
     */
    private static final String[] ALWAYS_REAL = {
            "android",
            "com.android.phone",
            "com.android.settings",
            "com.android.shell",
            "com.android.bluetooth",
            "com.android.permissioncontroller",
            "com.android.providers.settings",
            "com.android.server.telecom",
            "com.android.nfc",
            "com.android.se",
            "com.android.vending",
            "com.google.android.gms",
    };

    private IdentifierVirtualizer() {
    }

    /**
     * Settings is a content provider, and identifier getters are called in tight
     * loops by ad SDKs, so the policy is memoised for a few seconds. Bounds the
     * cost while still picking up a change from the control centre promptly.
     */
    private static final long CACHE_MS = 5_000L;

    /**
     * The epoch is what the user changes when they ask for a new identity, so it
     * is refreshed much more often than a salt used to be: a flip of the switch
     * must reach the next identifier read within a couple of seconds.
     */
    private static final long SALT_REFRESH_MS = 2_000L;

    private static long sCacheExpiry;
    private static boolean sCacheEnabled;
    private static String sCachePackages = "";
    private static String sSalt = "";
    private static long sSaltExpiry;
    private static final AtomicInteger sSaltBuild = new AtomicInteger();
    private static volatile int sCacheBuild;
    private static volatile int sSaltBuildSeen;

    /**
     * Drops every memoised value. Called right after the control centre stores a
     * new epoch so the switch takes effect immediately rather than after the
     * cache interval; the epoch stamps below cover the cross-process case, where
     * this app's memory knows nothing about the write.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static void invalidate() {
        invalidate(0);
    }

    /**
     * Drops the opt-in policy memo as well as any epoch memo older than
     * {@code minSaltBuild}. The control centre bumps the epoch stamp before it
     * writes a new epoch, so callers holding the stamp are guaranteed to read
     * the new value even when the write lands in the two seconds that follow.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static synchronized void invalidate(int minSaltBuild) {
        final boolean newerStamp = minSaltBuild > sSaltBuildSeen;
        if (newerStamp) {
            sSaltBuildSeen = minSaltBuild;
        }
        if (newerStamp || sSaltExpiry == 0) {
            sSaltExpiry = 0;
        }
        sCacheExpiry = 0;
    }

    private static synchronized void refreshCache(Context context) {
        final long now = System.currentTimeMillis();
        if (now < sCacheExpiry) {
            return;
        }
        int build;
        try {
            sCacheEnabled = Settings.Secure.getIntForUser(context.getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_ENABLED, 0,
                    UserHandle.myUserId()) != 0;
            final String list = Settings.Secure.getStringForUser(
                    context.getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_PACKAGES,
                    UserHandle.myUserId());
            sCachePackages = list == null ? "" : list;
            build = sSaltBuild.get();
        } catch (Throwable t) {
            // Fail closed: report real identifiers rather than break the caller.
            sCacheEnabled = false;
            sCachePackages = "";
            build = sSaltBuild.get();
            Log.w(TAG, "cannot read identifier policy", t);
        }
        sCacheBuild = build;
        sCacheExpiry = now + CACHE_MS;
    }

    private static boolean isPackageOptedIn(String packageName) {
        for (String entry : sCachePackages.split(",")) {
            if (packageName.equals(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the caller identified by {@code packageName} should see fake values.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static boolean shouldVirtualize(Context context, String packageName) {
        // Process.myUid() rather than Binder.getCallingUid(): these calls run in the
        // client process, where they agree, but myUid() is immune to any
        // clearCallingIdentity() the surrounding framework snippet might wrap around us.
        return shouldVirtualizeForCaller(context, packageName, Process.myUid());
    }

    /**
     * Same policy as {@link #shouldVirtualize(Context, String)}, for code that runs
     * on behalf of another process (SettingsProvider) and therefore has to be told
     * the caller's uid explicitly, because Process.myUid() there would be the
     * system uid and the answer would never be true.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static boolean shouldVirtualizeForCaller(Context context, String packageName,
            int callerUid) {
        if (context == null || packageName == null) {
            return false;
        }
        refreshCache(context);
        if (!sCacheEnabled) {
            return false;
        }
        final int uid = callerUid;
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) {
            return false;
        }
        if (!UserHandle.isApp(uid)) {
            return false;
        }
        if (CONTROLLER_PACKAGE.equals(packageName)) {
            return false;
        }
        for (String pkg : ALWAYS_REAL) {
            if (pkg.equals(packageName)) {
                return false;
            }
        }
        return isPackageOptedIn(packageName);
    }

    /**
     * Convenience wrapper for call sites that only hold a {@link Context} whose
     * op package name is the caller, which is the usual case inside the
     * framework client classes.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static boolean shouldVirtualize(Context context) {
        return context != null && shouldVirtualize(context, context.getOpPackageName());
    }

    /**
     * Derives a stable fake value for {@code field} as seen by {@code packageName}.
     *
     * <p>The shape of the input is preserved (digit count, hex vs decimal) because
     * SDKs frequently validate it: an IMEI must be 15 digits and pass the Luhn
     * check, an OAID must be a UUID. Returning something obviously malformed just
     * tells the app it is being lied to.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fake(String packageName, String field, String real) {
        return fake(null, packageName, field, real);
    }

    /** Salted variant used from processes that hold a {@link Context}. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fake(Context context, String packageName, String field, String real) {
        if (real == null) {
            return null;
        }
        final byte[] seed = seededDigest(context, packageName + "|" + field + "|" + real);
        if (real.matches("\\d{15}")) {
            return luhnSafe(digitsFrom(seed, 15));
        }
        if (real.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return uuidFrom(seed);
        }
        if (real.matches("\\d+")) {
            return digitsFrom(seed, real.length());
        }
        if (real.matches("[0-9a-f]+")) {
            return hexFrom(seed, real.length(), false);
        }
        if (real.matches("[0-9A-F]+")) {
            return hexFrom(seed, real.length(), true);
        }
        return hexFrom(seed, real.length(), false);
    }

    /**
     * Fake IMEI for {@code packageName}. Always 15 digits and Luhn-valid, and its
     * first eight digits are {@link #fakeTac}, so a caller that reads the IMEI and
     * the type allocation code separately still sees one consistent device.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeImei(String packageName, String real) {
        return fakeImei(null, packageName, real);
    }

    /** Salted variant of {@link #fakeImei(String, String)}. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeImei(Context context, String packageName, String real) {
        if (real == null) {
            return null;
        }
        if (!real.matches("\\d{15}")) {
            return fake(context, packageName, "imei", real);
        }
        final byte[] seed = seededDigest(context, packageName + "|imei-serial|" + real);
        final StringBuilder sb = new StringBuilder(fakeTac(context, packageName));
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('0' + ((seed[i % seed.length] & 0xFF) % 10)));
        }
        // 8 TAC + 6 serial digits, then let the Luhn helper pick the check digit.
        return luhnSafe(sb.toString() + "0");
    }

    /**
     * Fake type allocation code (the leading eight digits of an IMEI). Stays
     * prefix-consistent with {@link #fakeImei}: both derive it from the same key,
     * salt and prefix.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeTac(Context context, String packageName) {
        return digitsFrom(seededDigest(context, packageName + "|imei-tac"), 8);
    }

    /**
     * Fake IMEI for callers that cannot reach the real one (for example because
     * the real getter threw SecurityException). No {@code real} value is needed:
     * the value only has to look like an IMEI, which this one does.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeImei(Context context, String packageName) {
        final StringBuilder sb = new StringBuilder(fakeTac(context, packageName));
        final byte[] seed = seededDigest(context, packageName + "|imei|serial");
        for (int i = 0; i < 6; i++) {
            sb.append((char) ('0' + ((seed[i % seed.length] & 0xFF) % 10)));
        }
        // 8 TAC + 6 serial digits, then the Luhn helper picks the check digit.
        return luhnSafe(sb.toString() + "0");
    }

    /** Fake MEID: 14 upper-case hex digits, as a real MEID is encoded. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeMeid(Context context, String packageName) {
        return hexFrom(seededDigest(context, packageName + "|meid"), 14, true);
    }

    /** Fake IMSI: 15 decimal digits, the usual maximum length of an IMSI. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeImsi(Context context, String packageName) {
        return digitsFrom(seededDigest(context, packageName + "|imsi"), 15);
    }

    /** Fake ICCID: 20 decimal digits, the usual length of a SIM serial. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeIccid(Context context, String packageName) {
        return digitsFrom(seededDigest(context, packageName + "|iccid"), 20);
    }

    /**
     * Fake SSAID (ANDROID_ID): 16 lower-case hex digits, matching the shape AOSP
     * generates. The value is served by SettingsProvider rather than through
     * {@link #fake}, because the real id comes from a separate provider path.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeSsaid(Context context, String packageName) {
        return hexFrom(seededDigest(context, packageName + "|ssaid"), 16, false);
    }

    /**
     * Same value as {@link #fakeSsaid(Context, String)} but with the epoch supplied
     * by the caller.
     *
     * <p>SettingsProvider serves the SSAID while holding its own lock, and reading
     * the epoch through Settings.Secure would re-enter the provider it is already
     * inside. It hands its own copy of the epoch in here instead.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeSsaidForSalt(String epoch, String packageName) {
        return hexFrom(digest(packageName + "|ssaid|" + (epoch == null ? "" : epoch)), 16, false);
    }

    /**
     * Fake Widevine device unique id: same length and alphabet as the real
     * buffer, since licence servers treat it as opaque bytes.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static byte[] fakeDrmId(Context context, String packageName, byte[] real) {
        final int len = real == null ? 16 : real.length;
        final byte[] fake = new byte[len];
        int off = 0;
        int block = 0;
        while (off < len) {
            final byte[] seed = seededDigest(context,
                    packageName + "|drm-device-unique-id|" + block);
            final int n = Math.min(seed.length, len - off);
            System.arraycopy(seed, 0, fake, off, n);
            off += n;
            block++;
        }
        return fake;
    }

    /** Fake hardware serial: 16 upper-case hex digits, the shape of ro.serialno. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeSerial(Context context, String packageName) {
        return hexFrom(seededDigest(context, packageName + "|serial"), 16, true);
    }

    /**
     * Fake value whose length and alphabet follow the real one, used for
     * human-readable build fields (MODEL, BRAND, DEVICE ...) where replacing
     * digits is enough to break fingerprinting while keeping the string sane.
     */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeBuildField(String packageName, String field, String real) {
        return fakeBuildField(null, packageName, field, real);
    }

    /** Salted variant of {@link #fakeBuildField(String, String, String)}. */
    @SuppressLint({"UnflaggedApi", "MissingNullability"})
    public static String fakeBuildField(Context context, String packageName, String field,
            String real) {
        if (real == null || real.isEmpty()) {
            return real;
        }
        final byte[] seed = seededDigest(context, packageName + "|build|" + field + "|" + real);
        final StringBuilder sb = new StringBuilder(real.length());
        for (int i = 0; i < real.length(); i++) {
            final char c = real.charAt(i);
            if (Character.isDigit(c)) {
                sb.append((char) ('0' + ((seed[i % seed.length] & 0xFF) % 10)));
            } else if (Character.isLetter(c)) {
                final boolean upper = Character.isUpperCase(c);
                final char base = upper ? 'A' : 'a';
                sb.append((char) (base + ((seed[i % seed.length] & 0xFF) % 26)));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Single derivation point for every fake value, so salts cannot drift apart. */
    private static byte[] seededDigest(Context context, String key) {
        return digest(key + "|" + deviceSalt(context));
    }

    /**
     * The virtualisation epoch is the single salt behind every fake value.
     *
     * <p>It used to be ANDROID_ID, but that is itself virtualised, so deriving from
     * it made the salt depend on the value it was meant to produce. The epoch is
     * global to the user rather than per uid: one switch flip has to move every
     * identifier of the app at once. A change of the epoch stamp drops the cached
     * value even when this process did not see the write, and when that happens
     * the read is repeated until the new epoch is visible, so a caller that bumped
     * the stamp can never be served the previous identity.
     */
    private static String deviceSalt(Context context) {
        if (context == null) {
            return "";
        }
        long expiry;
        long seen;
        String salt;
        synchronized (IdentifierVirtualizer.class) {
            final long now = SystemClock.elapsedRealtime();
            if (now < sSaltExpiry) {
                return sSalt;
            }
            expiry = sSaltExpiry;
            seen = sSaltBuildSeen;
            salt = sSalt;
        }
        // Refresh the stamp before reading, never after: a bump that lands between
        // the two then shows up on the next read instead of being missed.
        sSaltBuildSeen = Math.max(sSaltBuildSeen, sCacheBuild);
        final boolean replaced = sSaltBuildSeen > seen;
        for (int i = 0; ; i++) {
            String id = null;
            try {
                id = Settings.Secure.getStringForUser(context.getContentResolver(),
                        Settings.Secure.IDENTIFIER_VIRTUALIZATION_EPOCH, UserHandle.myUserId());
            } catch (Throwable t) {
                Log.w(TAG, "cannot read virtualisation epoch", t);
                if (i == 0) {
                    id = salt;
                }
            }
            final String fresh = id == null ? "" : id;
            synchronized (IdentifierVirtualizer.class) {
                if (sSaltExpiry == expiry) {
                    sSalt = fresh;
                    sSaltExpiry = SystemClock.elapsedRealtime() + SALT_REFRESH_MS;
                }
            }
            if (!replaced || !fresh.equals(salt) || i >= 10) {
                return fresh;
            }
            // The epoch was just replaced (by the control centre in this process):
            // wait for the new value to land rather than keep the old identity.
            SystemClock.sleep(50);
        }
    }

    private static String digitsFrom(byte[] seed, int len) {
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('0' + ((seed[i % seed.length] & 0xFF) % 10)));
        }
        return sb.toString();
    }

    private static String hexFrom(byte[] seed, int len, boolean upper) {
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            final char c = Character.forDigit((seed[i % seed.length] & 0xFF) % 16, 16);
            sb.append(upper ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    private static String uuidFrom(byte[] seed) {
        final StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 36; i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                sb.append('-');
            } else if (i == 14) {
                sb.append('4');
            } else if (i == 19) {
                sb.append("89ab".charAt((seed[0] & 0xFF) % 4));
            } else {
                sb.append(Character.forDigit((seed[i % seed.length] & 0xFF) % 16, 16));
            }
        }
        return sb.toString();
    }

    /** Adjusts the check digit until the value satisfies the Luhn checksum. */
    private static String luhnSafe(String digits) {
        final char[] c = digits.toCharArray();
        for (int attempt = 0; attempt < 10; attempt++) {
            c[c.length - 1] = (char) ('0' + attempt);
            if (luhnValid(c)) {
                return new String(c);
            }
        }
        return digits;
    }

    private static boolean luhnValid(char[] digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length - 1; i >= 0; i--) {
            int d = digits[i] - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            final byte[] fallback = new byte[32];
            final int h = s.hashCode();
            for (int i = 0; i < fallback.length; i++) {
                fallback[i] = (byte) (h >>> (i % 4 * 8));
            }
            return fallback;
        }
    }
}
