/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sikuai.privacyprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaDrm;
import android.media.MediaRecorder;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Privacy probe.
 *
 * Exercises each protected capability through ordinary public APIs and reports
 * whether the platform actually allowed it. Deliberately a separate, unprivileged
 * app from the privacy centre, so the checks hit the same gates a real third
 * party app would.
 */
public class ProbeActivity extends Activity {

    private static final String TAG = "PrivacyProbe";

    private TextView mOutput;
    private Button mRun;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Without this the button is laid out under the status bar and cannot
        // be tapped from automation.
        root.setFitsSystemWindows(true);

        mRun = new Button(this);
        mRun.setText("Run checks");
        mRun.setOnClickListener(v -> runAll());

        mOutput = new TextView(this);
        mOutput.setTextSize(13);
        mOutput.setPadding(24, 24, 24, 24);
        mOutput.setTextIsSelectable(true);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(mOutput);

        root.addView(mRun);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // "am start ... --ez autorun true" runs the matrix without a tap, which
        // is the only reliable way to drive this from a script.
        if (getIntent() != null && getIntent().getBooleanExtra("autorun", false)) {
            runAll();
        }
    }

    private final StringBuilder mBuf = new StringBuilder();

    private void log(String tag, String msg) {
        mBuf.append('[').append(tag).append("] ").append(msg).append('\n');
        Log.i(TAG, tag + ": " + msg);
    }

    private void runAll() {
        mBuf.setLength(0);
        mRun.setEnabled(false);
        mOutput.setText("");
        // Network does blocking IO, so keep it off the main thread.
        new Thread(() -> {
            checkNetwork();
            checkClipboard();
            checkDeviceIdentifiers();
            checkCamera();
            checkMicrophone();
            Log.i(TAG, "PROBE_BEGIN\n" + mBuf + "PROBE_END");
            runOnUiThread(() -> {
                mOutput.setText(mBuf.toString());
                mRun.setEnabled(true);
            });
        }, "probe").start();
    }

    private void checkNetwork() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        try {
            final Network net = cm.getActiveNetwork();
            final NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            log("network", caps != null && caps.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ? "active network reports INTERNET capability"
                    : "no active network or no INTERNET capability");
        } catch (Throwable t) {
            log("network", "capability query failed: " + t.getClass().getSimpleName());
        }

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)
                    new URL("https://clients3.google.com/generate_204").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            final int code = c.getResponseCode();
            log("network", "HTTPS returned " + code + " -> TRAFFIC ALLOWED");
        } catch (Throwable t) {
            log("network", "request failed -> traffic blocked or offline: "
                    + t.getClass().getSimpleName());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void checkClipboard() {
        final ClipboardManager cb = getSystemService(ClipboardManager.class);
        try {
            cb.setPrimaryClip(ClipData.newPlainText("probe", "probe-secret-123"));
            final ClipData clip = cb.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                log("clipboard", "READ ALLOWED -> "
                        + clip.getItemAt(0).coerceToText(this));
            } else {
                log("clipboard", "clip is null/empty -> access blocked");
            }
        } catch (Throwable t) {
            log("clipboard", "blocked: " + t.getClass().getSimpleName()
                    + " (" + t.getMessage() + ")");
        }
    }

    private void checkDeviceIdentifiers() {
        final String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        log("id", "ANDROID_ID = " + androidId);

        // android.os.Build is what an app actually sees, and it is initialised
        // inside zygote, so these reflect the profile only after the reboot that
        // follows the one which published the persist properties.
        log("build", "MODEL = " + android.os.Build.MODEL);
        log("build", "BRAND = " + android.os.Build.BRAND);
        log("build", "DEVICE = " + android.os.Build.DEVICE);
        log("build", "MANUFACTURER = " + android.os.Build.MANUFACTURER);
        log("build", "PRODUCT = " + android.os.Build.PRODUCT);
        log("build", "FINGERPRINT = " + android.os.Build.FINGERPRINT);

        String serial;
        try {
            serial = android.os.Build.getSerial();
        } catch (Throwable t) {
            serial = "error:" + t.getClass().getSimpleName();
        }
        log("id", "Build.SERIAL = " + serial);

        // Call every getter unconditionally: whether the framework throws or hands
        // back a fake is exactly what this probe is measuring, so gating on
        // checkSelfPermission() here would hide the answer.
        final boolean permitted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
        log("id", "READ_PHONE_STATE = " + (permitted ? "granted" : "denied"));
        final TelephonyManager tm = getSystemService(TelephonyManager.class);
        logId("IMEI", () -> tm.getImei());
        logId("deviceId", () -> tm.getDeviceId());
        logId("TAC", () -> tm.getTypeAllocationCode());
        logId("subscriberId", () -> tm.getSubscriberId());
        logId("simSerial", () -> tm.getSimSerialNumber());
        logId("meid", () -> tm.getMeid());

        // Widevine is not present on every device (and the vendor build may refuse
        // to hand out a device id), so this whole probe is best effort.
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"));
            final byte[] id = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (id == null) {
                log("id", "MediaDrm.deviceUniqueId = null");
            } else {
                final StringBuilder hex = new StringBuilder(id.length * 2);
                for (byte b : id) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                    hex.append(Character.forDigit(b & 0xf, 16));
                }
                log("id", "MediaDrm.deviceUniqueId = " + hex
                        + " (len=" + id.length + ")");
            }
        } catch (Throwable t) {
            log("id", "MediaDrm.deviceUniqueId -> " + t.getClass().getSimpleName()
                    + " (" + t.getMessage() + ")");
        } finally {
            if (drm != null) {
                drm.close();
            }
        }
    }

    private interface IdCall {
        String get();
    }

    private void logId(String name, IdCall call) {
        try {
            log("id", name + " = " + call.get());
        } catch (Throwable t) {
            log("id", name + " -> " + t.getClass().getSimpleName()
                    + " (" + t.getMessage() + ")");
        }
    }

    private void checkCamera() {
        try {
            final CameraManager mgr = getSystemService(CameraManager.class);
            final String[] ids = mgr.getCameraIdList();
            log("camera", "enumerated " + ids.length + " camera(s)");
            for (String id : ids) {
                final CameraCharacteristics ch = mgr.getCameraCharacteristics(id);
                final Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                log("camera", "  id=" + id + " facing=" + facing);
            }
        } catch (Throwable t) {
            log("camera", "blocked: " + t.getClass().getSimpleName()
                    + " (" + t.getMessage() + ")");
        }
    }

    private void checkMicrophone() {
        final int r = checkSelfPermission(Manifest.permission.RECORD_AUDIO);
        log("mic", "RECORD_AUDIO = "
                + (r == PackageManager.PERMISSION_GRANTED ? "granted" : "denied"));
        if (r != PackageManager.PERMISSION_GRANTED) {
            log("mic", "permission gate blocks recording (expected for a 3rd-party app)");
            return;
        }

        // Sensor privacy does not revoke RECORD_AUDIO, it makes the recorder fail,
        // so actually opening it is the only meaningful test.
        AudioRecord recorder = null;
        try {
            final int minBuf = AudioRecord.getMinBufferSize(
                    44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 1024));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                log("mic", "AudioRecord did not initialise -> microphone restricted");
                return;
            }
            recorder.startRecording();
            final short[] buf = new short[1024];
            final int read = recorder.read(buf, 0, buf.length);
            log("mic", "read " + read + " samples -> RECORDING ALLOWED");
        } catch (Throwable t) {
            log("mic", "blocked: " + t.getClass().getSimpleName()
                    + " (" + t.getMessage() + ")");
        } finally {
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (Throwable ignored) {
                }
                recorder.release();
            }
        }
    }
}
