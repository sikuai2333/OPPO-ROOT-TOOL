/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.content.Intent;
import android.hardware.SensorPrivacyManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Privacy control centre.
 *
 * Exposes the per-app network firewall, camera and microphone sensor privacy,
 * and the clipboard access notification toggle.
 */
public class MainActivity extends Activity {

    private static final String TAG = "HimaPrivacy";

    private SensorPrivacyManager mSensorPrivacy;

    /** Set while we push system state into the switches, so it is not treated as input. */
    private boolean mSyncing;

    private int mTextColor;

    private Switch mCameraSwitch;
    private Switch mMicSwitch;
    private Switch mClipboardSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            mSensorPrivacy = getSystemService(SensorPrivacyManager.class);
        } catch (Throwable t) {
            Log.e(TAG, "sensor privacy service unavailable", t);
        }
        setContentView(buildLayout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSwitches();
    }

    private TextView text(CharSequence s, float size) {
        final TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        if (mTextColor != 0) {
            tv.setTextColor(mTextColor);
        }
        return tv;
    }

    private View buildLayout() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        // Resolve from the theme so the text stays readable in both light and dark
        // instead of relying on a fixed colour that disappears on dark backgrounds.
        final int textColor = resolveThemeColor(android.R.attr.textColorPrimary);
        mTextColor = textColor;

        final TextView title = text(getString(R.string.app_name), 26);
        title.setPadding(0, 0, 0, dp(20));
        root.addView(title);

        root.addView(sectionHeader(R.string.section_apps));

        root.addView(clickableRow(R.string.network_control, R.string.network_control_desc,
                v -> startActivity(new Intent(this, AppNetworkActivity.class))));

        root.addView(clickableRow(R.string.background_control,
                R.string.background_control_desc,
                v -> startActivity(new Intent(this, AppBackgroundActivity.class))));

        root.addView(clickableRow(R.string.app_lock,
                R.string.app_lock_service_desc,
                v -> startActivity(new Intent(this, AppLockActivity.class))));

        root.addView(clickableRow(R.string.identifier_control,
                R.string.identifier_control_desc,
                v -> startActivity(new Intent(this, IdentifierActivity.class))));

        root.addView(clickableRow(R.string.device_profile_entry,
                R.string.device_profile_entry_desc,
                v -> startActivity(new Intent(this, DeviceSpoofActivity.class))));

        root.addView(sectionHeader(R.string.section_device));

        mCameraSwitch = switchRow(root, R.string.camera_toggle, R.string.camera_toggle_desc,
                (btn, checked) -> setSensor(SensorPrivacyManager.Sensors.CAMERA, checked, btn));

        mMicSwitch = switchRow(root, R.string.mic_toggle, R.string.mic_toggle_desc,
                (btn, checked) -> setSensor(SensorPrivacyManager.Sensors.MICROPHONE, checked, btn));

        mClipboardSwitch = switchRow(root, R.string.clipboard_toggle,
                R.string.clipboard_toggle_desc, (btn, checked) ->
                        setClipboardAlerts(checked, btn));

        return root;
    }

    private TextView sectionHeader(int resId) {
        final TextView tv = text(getString(resId), 14);
        tv.setPadding(0, dp(24), 0, dp(8));
        tv.setAlpha(0.85f);
        return tv;
    }

    private View clickableRow(int titleRes, int descRes, View.OnClickListener onClick) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        row.addView(text(getString(titleRes), 17));

        final TextView desc = text(getString(descRes), 13);
        desc.setAlpha(0.85f);
        row.addView(desc);

        row.setOnClickListener(onClick);
        row.setClickable(true);
        return row;
    }

    private interface OnSwitchChanged {
        void onChanged(CompoundButton button, boolean checked);
    }

    private Switch switchRow(LinearLayout parent, int titleRes, int descRes,
                             OnSwitchChanged listener) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));

        final LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        labels.addView(text(getString(titleRes), 17));
        final TextView desc = text(getString(descRes), 13);
        desc.setAlpha(0.85f);
        labels.addView(desc);
        row.addView(labels);

        final Switch sw = new Switch(this);
        row.addView(sw);

        sw.setOnCheckedChangeListener((button, checked) -> {
            if (mSyncing) {
                return;
            }
            listener.onChanged(button, checked);
        });
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));

        parent.addView(row);
        return sw;
    }

    private void setSwitchSilently(Switch sw, boolean checked) {
        if (sw == null || sw.isChecked() == checked) {
            return;
        }
        mSyncing = true;
        try {
            sw.setChecked(checked);
        } finally {
            mSyncing = false;
        }
    }

    private void refreshSwitches() {
        try {
            if (mSensorPrivacy != null) {
                setSwitchSilently(mCameraSwitch, mSensorPrivacy.isSensorPrivacyEnabled(
                        SensorPrivacyManager.Sensors.CAMERA));
                setSwitchSilently(mMicSwitch, mSensorPrivacy.isSensorPrivacyEnabled(
                        SensorPrivacyManager.Sensors.MICROPHONE));
            }
        } catch (Throwable t) {
            // The trusted-package path may be unavailable; the UI must not crash.
            Log.w(TAG, "cannot read sensor privacy state", t);
        }
        try {
            setSwitchSilently(mClipboardSwitch, isClipboardAlertsEnabled());
        } catch (Throwable t) {
            Log.w(TAG, "cannot read clipboard preference", t);
        }
    }

    private void setSensor(int sensor, boolean blocked, CompoundButton btn) {
        try {
            if (mSensorPrivacy == null) {
                throw new IllegalStateException("sensor privacy service unavailable");
            }
            mSensorPrivacy.setSensorPrivacy(sensor, blocked);
        } catch (Throwable t) {
            Log.e(TAG, "setSensorPrivacy failed", t);
            // Restore the switch so the UI does not claim a change that did not happen.
            mSyncing = true;
            try {
                btn.setChecked(!blocked);
            } finally {
                mSyncing = false;
            }
            return;
        }
        refreshSwitches();
    }

    private boolean isClipboardAlertsEnabled() {
        return Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS, 1) != 0;
    }

    private void setClipboardAlerts(boolean enabled, CompoundButton btn) {
        try {
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.CLIPBOARD_SHOW_ACCESS_NOTIFICATIONS, enabled ? 1 : 0);
        } catch (Throwable t) {
            Log.e(TAG, "clipboard setting failed", t);
            mSyncing = true;
            try {
                btn.setChecked(!enabled);
            } finally {
                mSyncing = false;
            }
            return;
        }
        refreshSwitches();
    }

    private int resolveThemeColor(int attr) {
        final android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return 0xFFFFFFFF;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
