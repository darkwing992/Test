package com.castapp.tv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMISSIONS = 1;
    private static final int REQ_CAPTURE = 2;

    private TextView urlText, statusText;
    private Button startButton, stopButton;
    private CheckBox audioCheck;
    private RadioGroup qualityGroup;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 2000); // picks up Wi-Fi changes
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        urlText = findViewById(R.id.url);
        statusText = findViewById(R.id.status);
        startButton = findViewById(R.id.start);
        stopButton = findViewById(R.id.stop);
        audioCheck = findViewById(R.id.audio);
        qualityGroup = findViewById(R.id.quality);

        startButton.setOnClickListener(v -> requestPermissionsThenCapture());
        stopButton.setOnClickListener(v ->
                startService(new Intent(this, CastService.class).setAction(CastService.ACTION_STOP)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        CastService.setStateListener(this::refresh);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CastService.setStateListener(null);
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        String err = CastService.takeError();
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();

        boolean running = CastService.isRunning();
        String url = NetUtil.url(CastService.port());
        if (url == null) {
            urlText.setText("Not on Wi-Fi");
        } else {
            urlText.setText(url);
        }
        if (running) {
            int n = CastService.viewers();
            String sound = CastService.audioActive() ? "with sound" : "video only";
            statusText.setText(n == 0
                    ? "Casting (" + sound + "). On your TV, open the web browser and go to the address above."
                    : "Casting " + sound + " to " + n + (n == 1 ? " screen" : " screens") + ".");
        } else {
            statusText.setText(url == null
                    ? "Connect your phone and TV to the same Wi-Fi network (or turn on your phone's hotspot and join it from the TV)."
                    : "Press Start, then open the address above in your TV's web browser.");
        }
        startButton.setVisibility(running ? View.GONE : View.VISIBLE);
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
        audioCheck.setEnabled(!running);
        for (int i = 0; i < qualityGroup.getChildCount(); i++) qualityGroup.getChildAt(i).setEnabled(!running);
    }

    private void requestPermissionsThenCapture() {
        List<String> needed = new ArrayList<>();
        if (audioCheck.isChecked()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO); // required to capture other apps' sound
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            requestCapture();
        } else {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])
                    && results[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Without the audio permission only the picture will be cast.",
                        Toast.LENGTH_LONG).show();
            }
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }
        int checked = qualityGroup.getCheckedRadioButtonId();
        int quality = checked == R.id.q_low ? 0 : checked == R.id.q_high ? 2 : 1;
        Intent svc = new Intent(this, CastService.class)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra(CastService.EXTRA_QUALITY, quality)
                .putExtra(CastService.EXTRA_AUDIO, audioCheck.isChecked());
        startForegroundService(svc);
    }
}
