/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacy;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * Invisible activity that gates access to a locked app.
 *
 * <p>Started by {@link AppLockService} on top of the target app. It shows the
 * system credential dialog and either dismisses itself (letting the app through)
 * or sends the user home. An activity is needed because
 * {@code createConfirmDeviceCredentialIntent} only reports its result through
 * {@link #onActivityResult}; the accessibility service has no such callback.
 */
public class AppLockGateActivity extends Activity {

    private static final String TAG = "HimaPrivacy";
    private static final int REQ_CREDENTIAL = 1;

    /** Settings finishes the credential prompt after this long without interaction. */
    private static final long CANCEL_RETRY_MS = 3_000L;

    static final String EXTRA_PKG = "app_lock.pkg";

    private String mPkg;
    private long mPromptRequestedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPkg = getIntent().getStringExtra(EXTRA_PKG);
        if (mPkg == null) {
            finish();
            return;
        }
        showPrompt();
    }

    private void showPrompt() {
        final KeyguardManager km = getSystemService(KeyguardManager.class);
        final Intent intent = km == null ? null
                : km.createConfirmDeviceCredentialIntent(
                        getString(R.string.app_lock_title), mPkg);
        if (intent == null) {
            // No credential configured: nothing to check against, so allow.
            Log.w(TAG, "no device credential set, not locking " + mPkg);
            AppLockService.grantGrace(this, mPkg);
            finish();
            return;
        }
        mPromptRequestedAt = SystemClock.elapsedRealtime();
        startActivityForResult(intent, REQ_CREDENTIAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_CREDENTIAL) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode == RESULT_OK) {
            AppLockService.grantGrace(this, mPkg);
            finish();
            return;
        }
        // The credential activity also reports CANCELED when something merely
        // covered it (its onPause finishes without a result). Treat a prompt
        // that barely got on screen as a dismissal, not a failed attempt.
        if (SystemClock.elapsedRealtime() - mPromptRequestedAt < CANCEL_RETRY_MS) {
            Log.i(TAG, "credential prompt was displaced, retrying for " + mPkg);
            showPrompt();
            return;
        }
        goHome();
    }

    @Override
    public void onBackPressed() {
        // Back out of the gate must not reveal the app behind it.
        goHome();
    }

    private void goHome() {
        AppLockService.clearSessionGate(mPkg);
        final Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }

    @Override
    protected void onDestroy() {
        // Only a real finish re-arms the gate; onDestroy while the credential
        // dialog is up would let a second gate race it.
        if (isFinishing()) {
            AppLockService.clearSessionGate(mPkg);
        }
        super.onDestroy();
    }
}
