/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Whole-device model spoofing: pick one of a handful of real SM8250 phones and,
 * after a reboot, every process - the launcher, the settings app, third-party
 * apps and the system itself - reports that phone instead of this one.
 *
 * <p>Why the reboot: {@link android.os.Build} is initialised once inside zygote
 * and inherited by every app process, so the values cannot change while the
 * system is up. The selection is stored here and copied into persistent
 * properties by system_server at the next boot, before zygote forks anything.
 */
public class DeviceSpoofActivity extends Activity {

    private static final String TAG = "HimaPrivacy";

    /** Property Build reads for {@code Build.MODEL}; see SystemServer's sync. */
    private static final String SPOOF_MODEL_PROP = "ro.hima.spoof.model";

    /** Persistent notice pointing back here while a change awaits a reboot. */
    private static final int NOTIFICATION_ID = 0x48494d41;

    private static final String NOTIFICATION_CHANNEL = "device_profile";

    /** Settings.Global keys, spelled out to stay independent of lite Settings.java. */
    private static final String KEY_ENABLED = "device_profile_spoof_enabled";
    private static final String KEY_GLOBAL_PREFIX = "device_profile_";
    /** Profile id, stored unprefixed so readProfileId() and putString() agree. */
    private static final String KEY_PROFILE_ID = "device_profile";
    /** Settings.Config namespace carrying the profile, read by SystemServer. */
    private static final String NAMESPACE = "hima_spoof";

    private final List<DeviceProfiles.Profile> mShown = new ArrayList<>();
    private ProfileAdapter mAdapter;
    private Switch mMasterSwitch;
    private TextView mStatusBody;
    /** Set while we push state into the switch, so it is not taken as input. */
    private boolean mSyncing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        refresh();
        if (getIntent().getBooleanExtra("show_reboot", false)) {
            promptReboot();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildLayout() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        final TextView header = new TextView(this);
        header.setText(R.string.device_profile_desc);
        header.setTextSize(13);
        header.setTextColor(textColor());
        header.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.addView(header);

        root.addView(buildStatusCard());
        root.addView(buildMasterRow());

        final ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mAdapter = new ProfileAdapter();
        list.setAdapter(mAdapter);
        list.setOnItemClickListener(this::onProfileClicked);
        root.addView(list);

        return root;
    }

    /** Shows what the system reports now, i.e. what the last boot applied. */
    private View buildStatusCard() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(8), dp(16), dp(8));

        final TextView title = new TextView(this);
        title.setText(R.string.device_profile_current);
        title.setTextSize(16);
        title.setTextColor(textColor());
        card.addView(title);

        mStatusBody = new TextView(this);
        mStatusBody.setTextSize(13);
        mStatusBody.setTextColor(textColor());
        mStatusBody.setAlpha(0.85f);
        card.addView(mStatusBody);
        return card;
    }

    private View buildMasterRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));

        final LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView title = new TextView(this);
        title.setText(R.string.device_profile_master);
        title.setTextSize(16);
        title.setTextColor(textColor());
        labels.addView(title);

        final TextView desc = new TextView(this);
        desc.setText(R.string.device_profile_master_desc);
        desc.setTextSize(12);
        desc.setTextColor(textColor());
        desc.setAlpha(0.85f);
        labels.addView(desc);
        row.addView(labels);

        mMasterSwitch = new Switch(this);
        mMasterSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) {
                return;
            }
            writeEnabled(checked);
            // Switching off drops the stored profile too, so the next boot
            // starts from a clean slate. Switching on needs a profile first.
            if (!checked) {
                clearProfile();
            }
            refresh();
            promptReboot();
        });
        row.addView(mMasterSwitch);
        return row;
    }

    private void refresh() {
        final String current = readProp(SPOOF_MODEL_PROP);
        mStatusBody.setText(current == null
                ? getString(R.string.device_profile_current_real)
                : getString(R.string.device_profile_current_desc, current));

        mShown.clear();
        // "Do not spoof" pseudo-entry first, then the offered phones.
        mShown.add(null);
        mShown.addAll(DeviceProfiles.ALL);
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }

        mSyncing = true;
        try {
            mMasterSwitch.setChecked(readEnabled());
        } finally {
            mSyncing = false;
        }
    }

    private void onProfileClicked(AdapterView<?> parent, View view, int position, long id) {
        final DeviceProfiles.Profile p = mShown.get(position);
        if (p == null) {
            clearProfile();
            writeEnabled(false);
        } else {
            writeProfile(p);
            writeEnabled(true);
        }
        refresh();
        promptReboot();
    }

    /** Confirms a change, reminds the user it lands on reboot, offers to do it now. */
    private void promptReboot() {
        final boolean on = readEnabled();
        final DeviceProfiles.Profile p = on ? DeviceProfiles.byId(readProfileId()) : null;
        final String msg;
        if (!on) {
            msg = getString(R.string.device_profile_reboot_off);
        } else if (p != null) {
            msg = getString(R.string.device_profile_reboot_needed, p.displayName);
        } else {
            msg = getString(R.string.device_profile_pick_first);
        }
        if (on && p != null) {
            notifyRebootRequired();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.device_profile_title)
                .setMessage(msg)
                .setPositiveButton(on ? R.string.device_profile_reboot_now : android.R.string.ok,
                        (d, w) -> {
                            if (on) {
                                reboot();
                            }
                        })
                .setNegativeButton(on ? android.R.string.cancel : android.R.string.ok, null)
                .show();
    }

    private void reboot() {
        try {
            getSystemService(PowerManager.class).reboot(null);
        } catch (Throwable t) {
            Log.e(TAG, "cannot reboot", t);
        }
    }

    /** Posts an ongoing notice so the pending reboot is not forgotten. */
    private void notifyRebootRequired() {
        try {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,
                    getString(R.string.device_profile_title),
                    NotificationManager.IMPORTANCE_LOW));
            final PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, DeviceSpoofActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE);
            final Notification n = new Notification.Builder(this, NOTIFICATION_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setContentTitle(getString(R.string.device_profile_title))
                    .setContentText(getString(R.string.device_profile_notification))
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
            nm.notify(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "cannot post reboot notice", t);
        }
    }

    // ---- storage --------------------------------------------------------
    // The selection lives in Settings.Global: the privacy centre cannot write
    // ro.hima.spoof.* (SELinux forbids set on ro.* by apps), so SystemServer
    // copies these Global values into those properties at boot, before zygote
    // forks any app. Global rather than Secure because the value describes the
    // whole device, not one user. Keys mirror Settings.Global.DEVICE_PROFILE_*
    // and are spelled out so this file does not depend on that revision.

    private boolean readEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), KEY_ENABLED, 0) != 0;
        } catch (Throwable t) {
            Log.e(TAG, "cannot read spoof switch", t);
            return false;
        }
    }

    private void writeEnabled(boolean enabled) {
        try {
            Settings.Global.putInt(getContentResolver(), KEY_ENABLED, enabled ? 1 : 0);
            Log.i(TAG, "device profile spoof enabled=" + enabled);
        } catch (Throwable t) {
            Log.e(TAG, "cannot write spoof switch", t);
            // Put the switch back so the UI does not claim a change that failed.
            mSyncing = true;
            try {
                mMasterSwitch.setChecked(!enabled);
            } finally {
                mSyncing = false;
            }
        }
    }

    private String readProfileId() {
        try {
            return Settings.Global.getString(getContentResolver(), KEY_PROFILE_ID);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Stores the chosen profile, so SystemServer can publish it at boot. */
    private void writeProfile(DeviceProfiles.Profile p) {
        // Stored without the prefix: readProfileId() reads "device_profile"
        // directly, and putString() would otherwise yield
        // "device_profile_device_profile".
        try {
            Settings.Global.putString(getContentResolver(), KEY_PROFILE_ID, p.id);
            Settings.Config.putString(NAMESPACE, KEY_PROFILE_ID, p.id, false);
        } catch (Throwable t) {
            Log.w(TAG, "cannot write profile id", t);
        }
        putString("device_profile_brand", p.brand);
        putString("product", p.product);
        putString("device", p.device);
        putString("model", p.model);
        putString("manufacturer", p.manufacturer);
        // Stored for the picker's description only; never published to ro.hardware.
        putString("hardware", p.hardware);
        putString("fingerprint", p.fingerprint());
        Log.i(TAG, "device profile selected: " + p.id);
    }

    private void clearProfile() {
        try {
            Settings.Global.putString(getContentResolver(), KEY_PROFILE_ID, "");
            Settings.Config.putString(NAMESPACE, KEY_PROFILE_ID, "", false);
        } catch (Throwable t) {
            Log.w(TAG, "cannot clear profile id", t);
        }
        for (String k : new String[]{"brand",
                "product", "device", "model",
                "manufacturer", "hardware",
                "fingerprint"}) {
            putString(k, "");
        }
    }

    private void putString(String key, String value) {
        // Namespace first: SystemServer reads it before the Global fallback.
        try {
            Settings.Config.putString(NAMESPACE, key, value, false);
        } catch (Throwable t) {
            Log.w(TAG, "cannot write namespace " + key, t);
        }
        try {
            Settings.Global.putString(getContentResolver(), KEY_GLOBAL_PREFIX + key, value);
        } catch (Throwable t) {
            Log.w(TAG, "cannot write global " + key, t);
        }
    }

    /** What the running system reports right now, i.e. the profile of the last boot. */
    private static String readProp(String key) {
        try {
            final String v = android.os.SystemProperties.get(key);
            return v == null || v.isEmpty() ? null : v;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The given profile id is what the user picked, as opposed to what is live now. */
    private boolean isSelected(String id) {
        final String stored = readProfileId();
        return readEnabled() && id != null && id.equals(stored);
    }

    private final class ProfileAdapter extends BaseAdapter {
        @Override public int getCount() { return mShown.size(); }
        @Override public Object getItem(int i) { return mShown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final DeviceProfiles.Profile p = mShown.get(position);
            final boolean selected = p == null ? !readEnabled() : isSelected(p.id);

            final LinearLayout row = new LinearLayout(DeviceSpoofActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setBackgroundColor(selected ? 0x1A2196F3 : 0x00000000);

            final TextView title = new TextView(DeviceSpoofActivity.this);
            title.setText(p == null ? getString(R.string.device_profile_none) : p.displayName);
            title.setTextSize(16);
            title.setTextColor(textColor());
            row.addView(title);

            final TextView sub = new TextView(DeviceSpoofActivity.this);
            sub.setTextSize(12);
            sub.setTextColor(textColor());
            sub.setAlpha(0.75f);
            sub.setText(p == null
                    ? getString(R.string.device_profile_none_desc)
                    : p.fingerprint() + "\n" + p.model + " / " + p.manufacturer
                            + " / Android " + p.release + " / patch " + p.securityPatch);
            row.addView(sub);
            // Rows are plain LinearLayouts, which swallow taps before the
            // ListView can turn them into an item click, so bind the row itself.
            row.setOnClickListener(v -> onProfileClicked(null, v, position, position));
            return row;
        }
    }

    private int textColor() {
        final android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true)
                && tv.resourceId != 0) {
            return getColor(tv.resourceId);
        }
        return getColor(android.R.color.primary_text_dark);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
