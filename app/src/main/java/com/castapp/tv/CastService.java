package com.castapp.tv;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Foreground service that captures the screen (MediaProjection -> ImageReader -> JPEG)
 * and the phone's internal audio (AudioPlaybackCapture -> PCM) and streams both to
 * any TV/browser connected to {@link StreamServer}.
 */
public class CastService extends Service {

    public static final String ACTION_START = "com.castapp.tv.START";
    public static final String ACTION_STOP = "com.castapp.tv.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_QUALITY = "quality"; // 0 low, 1 medium, 2 high
    public static final String EXTRA_AUDIO = "audio";

    public static final int PORT = 8080;

    private static final String TAG = "PhoneToTV";
    private static final String CHANNEL_ID = "cast";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;

    /** Simple state shared with the activity. */
    public interface StateListener {
        void onStateChanged();
    }

    private static volatile CastService instance;
    private static volatile StateListener stateListener;
    private static volatile String lastError;

    public static boolean isRunning() {
        return instance != null;
    }

    public static int viewers() {
        CastService s = instance;
        return s == null || s.server == null ? 0 : s.server.viewerCount();
    }

    public static int port() {
        CastService s = instance;
        return s == null || s.server == null ? PORT : s.server.getPort();
    }

    public static boolean audioActive() {
        CastService s = instance;
        return s != null && s.audioRecord != null;
    }

    public static String takeError() {
        String e = lastError;
        lastError = null;
        return e;
    }

    public static void setStateListener(StateListener l) {
        stateListener = l;
    }

    private static void notifyState() {
        StateListener l = stateListener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l::onStateChanged);
    }

    // -------------------------------------------------------------- state

    private StreamServer server;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread videoThread;
    private Handler videoHandler;
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean audioRunning;
    private WifiManager.WifiLock wifiLock;
    private DisplayManager displayManager;

    private int maxSize;
    private int jpegQuality;
    private long frameIntervalMs;
    private long lastFrameAt;
    private int captureW, captureH;
    private Bitmap rowBitmap, frameBitmap;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) resizeIfRotated();
        }
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            new Handler(Looper.getMainLooper()).post(CastService.this::stopSelf);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY; // already casting

        boolean wantAudio = intent.getBooleanExtra(EXTRA_AUDIO, true)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        // Must be in the foreground (with the right type) before getMediaProjection on Android 14+.
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (wantAudio) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), type);
        } catch (Exception e) {
            fail("Could not start casting service: " + e.getMessage());
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            projection = null;
        }
        if (projection == null) {
            fail("Screen capture permission was not granted.");
            return START_NOT_STICKY;
        }
        instance = this;

        switch (intent.getIntExtra(EXTRA_QUALITY, 1)) {
            case 0:  maxSize = 854;  jpegQuality = 55; frameIntervalMs = 1000 / 20; break;
            case 2:  maxSize = 1920; jpegQuality = 80; frameIntervalMs = 1000 / 30; break;
            default: maxSize = 1280; jpegQuality = 70; frameIntervalMs = 1000 / 30; break;
        }

        try {
            server = new StreamServer(readAsset("receiver.html"));
            server.setListener(count -> notifyState());
            server.start(PORT);
            server.setConfig("{\"sampleRate\":" + SAMPLE_RATE + ",\"channels\":" + CHANNELS + "}");
            // Refresh the notification now that the real port is known.
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, buildNotification());
        } catch (IOException e) {
            fail("Could not start the streaming server: " + e.getMessage());
            return START_NOT_STICKY;
        }

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PhoneToTV");
            wifiLock.acquire();
        }

        projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
        startVideo();
        if (wantAudio) startAudio();
        notifyState();
        return START_NOT_STICKY;
    }

    private void fail(String message) {
        Log.e(TAG, message);
        lastError = message;
        notifyState();
        stopSelf();
    }

    // -------------------------------------------------------------- video

    private void startVideo() {
        videoThread = new HandlerThread("cast-video");
        videoThread.start();
        videoHandler = new Handler(videoThread.getLooper());

        int[] size = computeCaptureSize();
        captureW = size[0];
        captureH = size[1];
        imageReader = newReader(captureW, captureH);
        virtualDisplay = projection.createVirtualDisplay("PhoneToTV", captureW, captureH, size[2],
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.getSurface(), null, videoHandler);

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        displayManager.registerDisplayListener(displayListener, videoHandler);
    }

    /** Returns {width, height, dpi}, scaled so the long side is at most maxSize. */
    private int[] computeCaptureSize() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int w = metrics.widthPixels, h = metrics.heightPixels;
        float scale = Math.min(1f, (float) maxSize / Math.max(w, h));
        int sw = Math.max(2, Math.round(w * scale) & ~1);
        int sh = Math.max(2, Math.round(h * scale) & ~1);
        return new int[]{sw, sh, metrics.densityDpi};
    }

    private ImageReader newReader(int w, int h) {
        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, videoHandler);
        return reader;
    }

    /** Runs on the video thread when the phone rotates: re-size the capture to match. */
    private void resizeIfRotated() {
        if (virtualDisplay == null) return;
        int[] size = computeCaptureSize();
        if (size[0] == captureW && size[1] == captureH) return;
        captureW = size[0];
        captureH = size[1];
        ImageReader old = imageReader;
        imageReader = newReader(captureW, captureH);
        virtualDisplay.resize(captureW, captureH, size[2]);
        virtualDisplay.setSurface(imageReader.getSurface());
        old.setOnImageAvailableListener(null, null);
        old.close();
        rowBitmap = null;
        frameBitmap = null;
    }

    private void onImage(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) return;
        StreamServer srv = server;
        try {
            long now = SystemClock.uptimeMillis();
            if (srv == null || !srv.hasViewers() || now - lastFrameAt < frameIntervalMs) return;
            lastFrameAt = now;

            int w = image.getWidth(), h = image.getHeight();
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowWidth = plane.getRowStride() / pixelStride; // includes row padding

            if (rowBitmap == null || rowBitmap.getWidth() != rowWidth || rowBitmap.getHeight() != h) {
                rowBitmap = Bitmap.createBitmap(rowWidth, h, Bitmap.Config.ARGB_8888);
            }
            buffer.rewind();
            rowBitmap.copyPixelsFromBuffer(buffer);

            Bitmap toEncode = rowBitmap;
            if (rowWidth != w) {
                if (frameBitmap == null || frameBitmap.getWidth() != w || frameBitmap.getHeight() != h) {
                    frameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                new android.graphics.Canvas(frameBitmap).drawBitmap(rowBitmap, 0, 0, null);
                toEncode = frameBitmap;
            }

            jpegOut.reset();
            toEncode.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOut);
            srv.broadcastVideo(jpegOut.toByteArray(), jpegOut.size());
        } catch (Exception e) {
            Log.w(TAG, "frame failed", e);
        } finally {
            image.close();
        }
    }

    // -------------------------------------------------------------- audio

    @SuppressWarnings("MissingPermission")
    private void startAudio() {
        try {
            AudioPlaybackCaptureConfiguration config =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(minBuf * 2, SAMPLE_RATE * CHANNELS * 2 / 5))
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.w(TAG, "audio capture unavailable", e);
            if (audioRecord != null) audioRecord.release();
            audioRecord = null;
            lastError = "Sound could not be captured on this phone; casting video only.";
            return;
        }

        audioRunning = true;
        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] buf = new byte[SAMPLE_RATE / 50 * CHANNELS * 2]; // 20 ms
            while (audioRunning) {
                int n = audioRecord.read(buf, 0, buf.length);
                StreamServer srv = server;
                if (n > 0 && srv != null) srv.broadcastAudio(buf, n);
                else if (n < 0) break;
            }
        }, "cast-audio");
        audioThread.start();
    }

    // ------------------------------------------------------------ cleanup

    @Override
    public void onDestroy() {
        instance = null;
        audioRunning = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) { }
        }
        if (audioThread != null) {
            try { audioThread.join(500); } catch (InterruptedException ignored) { }
        }
        if (audioRecord != null) audioRecord.release();
        audioRecord = null;

        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (videoHandler != null) {
            final ImageReader reader = imageReader;
            videoHandler.post(() -> { if (reader != null) reader.close(); });
        }
        if (videoThread != null) videoThread.quitSafely();

        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
        }
        projection = null;
        if (server != null) server.stop();
        server = null;
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        notifyState();
        super.onDestroy();
    }

    // ------------------------------------------------------------ helpers

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Casting",
                NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CastService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        String url = NetUtil.url(server != null ? server.getPort() : PORT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle("Casting to TV")
                .setContentText(url != null ? "Open " + url + " on your TV" : "Connect to Wi-Fi to cast")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Stop", stop).build())
                .build();
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
}
