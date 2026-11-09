/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks which apps get virtualised device identifiers.
 *
 * <p>Writes the opt-in list that the framework reads through
 * {@code Settings.Secure.IDENTIFIER_VIRTUALIZATION_PACKAGES}; the actual
 * substitution happens below the app, so anything the app does to re-read
 * {@code getImei()} / {@code Build.getSerial()} still sees the fake values.
 */
public class IdentifierActivity extends Activity {

    private static final String TAG = "HimaPrivacy";

    /**
     * Mirrors {@code Settings.Secure.IDENTIFIER_VIRTUALIZATION_EPOCH}. The literal
     * is spelled out so the app still builds against a framework that predates the
     * key; it is only ever read and written as a plain string setting.
     */
    private static final String KEY_EPOCH = "identifier_virtualization_epoch";

    private static final SecureRandom sRandom = new SecureRandom();

    private PackageManager mPm;

    private static final class AppEntry {
        String label;
        String pkg;
        boolean system;
        boolean virtualized;
    }

    private final List<AppEntry> mAll = new ArrayList<>();
    private final List<AppEntry> mShown = new ArrayList<>();
    private AppAdapter mAdapter;

    private String mQuery = "";
    private boolean mShowSystem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        final TextView header = new TextView(this);
        header.setText(R.string.identifier_control_desc);
        header.setTextSize(13);
        header.setTextColor(textColor());
        header.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.addView(header);

        root.addView(buildShuffleRow());

        // Regenerating here is what lets the user rotate identities without
        // reinstalling anything: every fake value derives from this epoch.
        final LinearLayout change = new LinearLayout(this);
        change.setOrientation(LinearLayout.VERTICAL);
        change.setPadding(dp(16), dp(12), dp(16), dp(12));

        final TextView changeTitle = new TextView(this);
        changeTitle.setText(R.string.identifier_change_identity);
        changeTitle.setTextSize(16);
        changeTitle.setTextColor(textColor());
        change.addView(changeTitle);

        final TextView changeDesc = new TextView(this);
        changeDesc.setText(R.string.identifier_change_identity_desc);
        changeDesc.setTextSize(13);
        changeDesc.setTextColor(textColor());
        changeDesc.setAlpha(0.85f);
        change.addView(changeDesc);

        change.setOnClickListener(v -> {
            try {
                final byte[] raw = new byte[16];
                new java.security.SecureRandom().nextBytes(raw);
                final StringBuilder hex = new StringBuilder(32);
                for (byte b : raw) {
                    hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(b & 0xF, 16));
                }
                Settings.Secure.putString(getContentResolver(),
                        "identifier_virtualization_epoch", hex.toString());
                Toast.makeText(this, R.string.identifier_changed, Toast.LENGTH_SHORT).show();
                Log.i(TAG, "rotated identity epoch");
            } catch (Throwable t) {
                Log.e(TAG, "cannot rotate identity", t);
            }
        });
        root.addView(change);

        final EditText search = new EditText(this);
        search.setHint(R.string.search_hint);
        search.setTextColor(textColor());
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
        sysSwitch.setTextColor(textColor());
        sysSwitch.setPadding(dp(16), dp(8), dp(16), dp(8));
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

    /**
     * Regenerates the epoch, which every fake identifier is derived from, so the
     * selected apps show a whole new identity without being reinstalled.
     */
    private View buildShuffleRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(4), dp(16), dp(8));

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView title = new TextView(this);
        title.setText(R.string.identifier_shuffle);
        title.setTextSize(16);
        title.setTextColor(textColor());
        texts.addView(title);

        final TextView desc = new TextView(this);
        desc.setText(R.string.identifier_shuffle_desc);
        desc.setTextSize(12);
        desc.setTextColor(textColor());
        texts.addView(desc);
        row.addView(texts);

        final TextView button = new TextView(this);
        button.setText(R.string.identifier_shuffle);
        button.setTextSize(14);
        button.setTextColor(textColor());
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setOnClickListener(v -> {
            if (readOptedIn().isEmpty()) {
                Toast.makeText(this, R.string.identifier_off_hint, Toast.LENGTH_SHORT).show();
                return;
            }
            writeEpoch(newEpoch());
            Toast.makeText(this, R.string.identifier_shuffled, Toast.LENGTH_SHORT).show();
        });
        row.addView(button);

        return row;
    }

    private static String newEpoch() {
        final byte[] bytes = new byte[16];
        sRandom.nextBytes(bytes);
        final StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private boolean readEnabled() {
        try {
            return Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_ENABLED, 0) != 0;
        } catch (Throwable t) {
            Log.e(TAG, "cannot read identifier switch", t);
            return false;
        }
    }

    private void writeEpoch(String epoch) {
        try {
            Settings.Secure.putString(getContentResolver(), KEY_EPOCH, epoch);
            Log.i(TAG, "identifier epoch=" + epoch);
        } catch (Throwable t) {
            Log.e(TAG, "cannot write identifier epoch", t);
        }
    }

    private void loadApps() {
        mAll.clear();
        final Set<String> optedIn = readOptedIn();
        final List<ApplicationInfo> infos;
        try {
            infos = mPm.getInstalledApplications(PackageManager.GET_META_DATA);
        } catch (Throwable t) {
            Log.e(TAG, "cannot enumerate packages", t);
            return;
        }
        for (ApplicationInfo info : infos) {
            final AppEntry e = new AppEntry();
            e.pkg = info.packageName;
            e.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            final CharSequence label = mPm.getApplicationLabel(info);
            e.label = TextUtils.isEmpty(label) ? info.packageName : label.toString();
            e.virtualized = optedIn.contains(e.pkg);
            mAll.add(e);
        }
        Collections.sort(mAll, Comparator.comparing(
                x -> x.label, String.CASE_INSENSITIVE_ORDER));
    }

    private Set<String> readOptedIn() {
        final Set<String> out = new HashSet<>();
        try {
            final String list = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_PACKAGES);
            if (list != null) {
                for (String entry : list.split(",")) {
                    final String pkg = entry.trim();
                    if (!pkg.isEmpty()) {
                        out.add(pkg);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "cannot read identifier package list", t);
        }
        return out;
    }

    private void writeOptedIn(Set<String> pkgs) {
        try {
            final boolean wasEnabled = readEnabled();
            // A non-empty list is the user asking for the feature; an empty one
            // turns it off again so nothing is virtualised by accident.
            final boolean enable = !pkgs.isEmpty();
            // Switching the feature back on has to hand the apps an identity they
            // have never seen, otherwise "off then on" would be a no-op.
            if (enable && (!wasEnabled || TextUtils.isEmpty(readEpoch()))) {
                writeEpoch(newEpoch());
            }
            final String joined = TextUtils.join(",", pkgs);
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_PACKAGES, joined);
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.IDENTIFIER_VIRTUALIZATION_ENABLED, enable ? 1 : 0);
            Log.i(TAG, "identifier virtualisation packages=" + joined
                    + " enabled=" + enable);
        } catch (Throwable t) {
            Log.e(TAG, "cannot write identifier package list", t);
        }
    }

    private String readEpoch() {
        try {
            return Settings.Secure.getString(getContentResolver(), KEY_EPOCH);
        } catch (Throwable t) {
            Log.e(TAG, "cannot read identifier epoch", t);
            return null;
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
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AppEntry e = mShown.get(position);

            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(IdentifierActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                final TextView label = new TextView(IdentifierActivity.this);
                label.setTextSize(16);
                label.setTextColor(textColor());
                label.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(label);

                final Switch sw = new Switch(IdentifierActivity.this);
                row.addView(sw);
                row.setTag(new Object[]{label, sw});
            }

            final Object[] views = (Object[]) row.getTag();
            final TextView label = (TextView) views[0];
            final Switch sw = (Switch) views[1];

            label.setText(e.label);

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(e.virtualized);
            sw.setOnCheckedChangeListener((CompoundButton button, boolean checked) -> {
                e.virtualized = checked;
                final Set<String> pkgs = readOptedIn();
                if (checked) {
                    pkgs.add(e.pkg);
                } else {
                    pkgs.remove(e.pkg);
                }
                writeOptedIn(pkgs);
            });

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
