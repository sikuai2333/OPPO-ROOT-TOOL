/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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

/**
 * Background-run control.
 *
 * Backs onto AppOps {@code OP_RUN_IN_BACKGROUND}: when the mode is IGNORED the
 * activity manager refuses to let the app start or stay running in the
 * background, which is what stops the mutual wake-up chains common in domestic
 * Android apps.
 */
public class AppBackgroundActivity extends Activity {

    private static final String TAG = "HimaPrivacy";

    private AppOpsManager mAppOps;
    private PackageManager mPm;

    private static final class AppEntry {
        int uid;
        String label;
        String pkg;
        boolean system;
        Drawable icon;
        boolean allowed;
    }

    private final List<AppEntry> mAll = new ArrayList<>();
    private final List<AppEntry> mShown = new ArrayList<>();
    private AppAdapter mAdapter;

    private String mQuery = "";
    private boolean mShowSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAppOps = getSystemService(AppOpsManager.class);
        mPm = getPackageManager();

        loadApps();
        if (!hasUserApps()) {
            mShowSystem = true;
        }
        setContentView(buildLayout());
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

    private View buildLayout() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

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
        final int pad = dp(16);
        sysSwitch.setPadding(pad, dp(8), pad, dp(8));
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
            if (info.uid < 0) {
                continue;
            }
            final AppEntry e = new AppEntry();
            e.uid = info.uid;
            e.pkg = info.packageName;
            e.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            final CharSequence label = mPm.getApplicationLabel(info);
            e.label = TextUtils.isEmpty(label) ? info.packageName : label.toString();
            try {
                e.icon = mPm.getApplicationIcon(info);
            } catch (Throwable t) {
                e.icon = null;
            }
            e.allowed = readAllowed(e);
            mAll.add(e);
        }
        Collections.sort(mAll, Comparator.comparing(
                x -> x.label, String.CASE_INSENSITIVE_ORDER));
    }

    private boolean readAllowed(AppEntry e) {
        try {
            final int mode = mAppOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_RUN_IN_BACKGROUND, e.uid, e.pkg);
            // AMS treats only IGNORED/ERRORED as "no background"; DEFAULT is a
            // real allow, so mirror that instead of inverting the comparison.
            return mode != AppOpsManager.MODE_IGNORED
                    && mode != AppOpsManager.MODE_ERRORED;
        } catch (Throwable t) {
            return true;
        }
    }

    private void writeAllowed(AppEntry e, boolean allowed) {
        try {
            mAppOps.setMode(AppOpsManager.OP_RUN_IN_BACKGROUND, e.uid, e.pkg,
                    allowed ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED);
            Log.i(TAG, "background uid=" + e.uid + " allowed=" + allowed);
        } catch (Throwable t) {
            Log.e(TAG, "cannot set background mode for " + e.pkg, t);
        }
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
        @Override public long getItemId(int i) { return mShown.get(i).uid; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppEntry e = mShown.get(position);

            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(AppBackgroundActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final int pad = dp(16);
                row.setPadding(pad, dp(10), pad, dp(10));

                final ImageView icon = new ImageView(AppBackgroundActivity.this);
                final int iconSize = dp(40);
                icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
                row.addView(icon);

                final TextView label = new TextView(AppBackgroundActivity.this);
                label.setTextSize(16);
                final LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                textLp.setMarginStart(dp(14));
                label.setLayoutParams(textLp);
                row.addView(label);

                final Switch sw = new Switch(AppBackgroundActivity.this);
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
            sw.setChecked(e.allowed);
            sw.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
                writeAllowed(e, checked);
                e.allowed = checked;
            });

            return row;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
