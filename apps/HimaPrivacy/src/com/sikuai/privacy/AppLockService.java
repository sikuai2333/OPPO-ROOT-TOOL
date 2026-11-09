/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Watches application switches and gates locked apps behind device credentials.
 *
 * <p>Implemented as an accessibility service rather than an AMS hook: it needs no
 * system_server changes and cannot destabilise the boot path, which matters more
 * than bulletproof enforcement for a personal build. The trade-off is that it is
 * a deterrent rather than a security boundary.
 */
public class AppLockService extends AccessibilityService {

    private static final String TAG = "HimaPrivacy";
    private static final String PREFS = "app_lock";
    private static final String KEY_LOCKED = "locked";
    private static final String KEY_GRACE_UNTIL = "grace_until";
    private static final String KEY_GRACE_PKG = "grace_pkg";

    /** How long a single successful challenge keeps an app reachable. */
    private static final long GRACE_MS = 60_000L;

    /**
     * Packages whose gate is currently on screen. Shared with the gate activity
     * so a prompt dismissal can re-arm it.
     */
    private static final Set<String> sSessionGated = new HashSet<>();

    private KeyguardManager mKeyguard;

    static Set<String> getLocked(Context context) {
        final SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new HashSet<>(sp.getStringSet(KEY_LOCKED, new HashSet<>()));
    }

    static void setLocked(Context context, String pkg, boolean locked) {
        final SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final Set<String> set = new HashSet<>(sp.getStringSet(KEY_LOCKED, new HashSet<>()));
        if (locked) {
            set.add(pkg);
        } else {
            set.remove(pkg);
        }
        sp.edit().putStringSet(KEY_LOCKED, set).apply();
    }

    /** Called by the gate activity once the user has authenticated. */
    static void grantGrace(Context context, String pkg) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_GRACE_PKG, pkg)
                .putLong(KEY_GRACE_UNTIL, System.currentTimeMillis() + GRACE_MS)
                .apply();
    }

    /** Lets the service re-arm the gate after the prompt was dismissed. */
    static void clearSessionGate(String pkg) {
        sSessionGated.remove(pkg);
    }

    /**
     * True while the user should still be let straight into {@code pkg}: either
     * they just authenticated for it, or the gate for it is already on screen.
     */
    private boolean isGated(String pkg) {
        final SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (pkg.equals(sp.getString(KEY_GRACE_PKG, ""))
                && System.currentTimeMillis() < sp.getLong(KEY_GRACE_UNTIL, 0)) {
            return true;
        }
        // The credential dialog is drawn by Settings, and its own window events
        // must not push a second gate over the top of it.
        return sSessionGated.contains(pkg);
    }

    @Override
    public void onServiceConnected() {
        // Without this, windows opened by the credential flow are never reported
        // and the gate cannot tell it has already been shown.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        mKeyguard = getSystemService(KeyguardManager.class);
        Log.i(TAG, "app lock service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        final CharSequence name = event.getPackageName();
        if (name == null) {
            return;
        }
        final String pkg = name.toString();

        // Nothing to protect while the keyguard covers the screen.
        if (mKeyguard != null && mKeyguard.isKeyguardLocked()) {
            return;
        }
        // com.android.settings draws the credential dialog; excluding systemui
        // avoids the shade and IME windows racing the gate.
        if (pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.settings")) {
            return;
        }
        if (!getLocked(this).contains(pkg)) {
            return;
        }
        if (isGated(pkg)) {
            return;
        }

        sSessionGated.add(pkg);

        final Intent gate = new Intent(this, AppLockGateActivity.class);
        gate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        gate.putExtra(AppLockGateActivity.EXTRA_PKG, pkg);
        try {
            startActivity(gate);
        } catch (Throwable t) {
            sSessionGated.remove(pkg);
            Log.e(TAG, "cannot start app lock gate", t);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
