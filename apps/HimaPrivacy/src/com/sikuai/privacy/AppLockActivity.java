/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * App lock list.
 *
 * <p>Two prerequisites are surfaced at the top: the device must have a screen
 * credential (otherwise there is nothing to challenge against) and the
 * accessibility service must be enabled (that is what observes app switches).
 */
public class AppLockActivity extends Activity {

    private static final String TAG = "HimaPrivacy";

    private PackageManager mPm;
    private Set<String> mLocked;

    private static final class AppEntry {
        String label;
        String pkg;
        boolean system;
        Drawable icon;
        boolean locked;
    }

    private final List<AppEntry> mAll = new ArrayList<>();
    private final List<AppEntry> mShown = new ArrayList<>();
    private AppAdapter mAdapter;

    private String mQuery = "";
    private boolean mShowSystem;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPm = getPackageManager();
        mLocked = AppLockService.getLocked(this);
        loadApps();
        if (!hasUserApps()) {
            mShowSystem = true;
        }
        setContentView(buildLayout());
        applyFilter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLocked = AppLockService.getLocked(this);
        for (AppEntry e : mAll) {
            e.locked = mLocked.contains(e.pkg);
        }
        updateStatus();
        applyFilter();
    }

    private boolean hasUserApps() {
        for (AppEntry e : mAll) {
            if (!e.system) {
                return true;
            }
        }
        return false;
    }

    private boolean isServiceEnabled() {
        final String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) {
            return false;
        }
        final ComponentName me = new ComponentName(this, AppLockService.class);
        return flat.contains(me.flattenToString())
                || flat.contains(me.flattenToShortString());
    }

    private void updateStatus() {
        if (mStatus == null) {
            return;
        }
        final boolean secure = getSystemService(android.app.KeyguardManager.class)
                .isDeviceSecure();
        if (!secure) {
            mStatus.setText(R.string.app_lock_no_credential);
        } else if (!isServiceEnabled()) {
            mStatus.setText(R.string.app_lock_service_off);
        } else {
            mStatus.setText(R.string.app_lock_ready);
        }
    }

    private View buildLayout() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        mStatus = new TextView(this);
        mStatus.setTextSize(13);
        final int pad = dp(16);
        mStatus.setPadding(pad, dp(12), pad, dp(4));
        root.addView(mStatus);

        final Button enable = new Button(this);
        enable.setText(R.string.app_lock_enable_service);
        enable.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                Log.e(TAG, "cannot open accessibility settings", t);
            }
        });
        root.addView(enable);

        final EditText search = new EditText(this);
        search.setHint(R.string.search_hint);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mQuery = s.toString();
                applyFilter();
            }
        });
        root.addView(search);

        final Switch sysSwitch = new Switch(this);
        sysSwitch.setText(R.string.show_system_apps);
        sysSwitch.setChecked(mShowSystem);
        sysSwitch.setOnCheckedChangeListener((button, checked) -> {
            mShowSystem = checked;
            applyFilter();
        });
        root.addView(sysSwitch);

        final ListView list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mAdapter = new AppAdapter();
        list.setAdapter(mAdapter);
        root.addView(list);

        return root;
    }

    private void loadApps() {
        mAll.clear();
        final List<ApplicationInfo> infos;
        try {
            infos = mPm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            Log.e(TAG, "cannot enumerate packages", t);
            return;
        }
        for (ApplicationInfo info : infos) {
            if (info.uid < 0 || info.packageName.equals(getPackageName())) {
                continue;
            }
            final AppEntry e = new AppEntry();
            e.pkg = info.packageName;
            e.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            final CharSequence label = mPm.getApplicationLabel(info);
            e.label = TextUtils.isEmpty(label) ? info.packageName : label.toString();
            try {
                e.icon = mPm.getApplicationIcon(info);
            } catch (Throwable t) {
                e.icon = null;
            }
            e.locked = mLocked.contains(e.pkg);
            mAll.add(e);
        }
        Collections.sort(mAll, Comparator.comparing(
                x -> x.label, String.CASE_INSENSITIVE_ORDER));
    }

    private void applyFilter() {
        if (mAdapter == null) {
            return;
        }
        final String q = mQuery.trim().toLowerCase();
        mShown.clear();
        for (AppEntry e : mAll) {
            if (!mShowSystem && e.system) {
                continue;
            }
            if (!q.isEmpty() && !e.label.toLowerCase().contains(q)) {
                continue;
            }
            mShown.add(e);
        }
        mAdapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return mShown.size(); }
        @Override public Object getItem(int i) { return mShown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppEntry e = mShown.get(position);

            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(AppLockActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final int pad = dp(16);
                row.setPadding(pad, dp(10), pad, dp(10));

                final ImageView icon = new ImageView(AppLockActivity.this);
                final int iconSize = dp(40);
                icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
                row.addView(icon);

                final TextView label = new TextView(AppLockActivity.this);
                label.setTextSize(16);
                final LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textLp.setMarginStart(dp(14));
                label.setLayoutParams(textLp);
                row.addView(label);

                final Switch sw = new Switch(AppLockActivity.this);
                row.addView(sw);
                row.setTag(new Object[]{icon, label, sw});
            }

            final Object[] views = (Object[]) row.getTag();
            final ImageView icon = (ImageView) views[0];
            final TextView label = (TextView) views[1];
            final Switch sw = (Switch) views[2];

            label.setText(e.label);
            if (e.icon != null) {
                icon.setImageDrawable(e.icon);
            } else {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(e.locked);
            sw.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
                AppLockService.setLocked(AppLockActivity.this, e.pkg, checked);
                e.locked = checked;
                mLocked = AppLockService.getLocked(AppLockActivity.this);
            });

            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
