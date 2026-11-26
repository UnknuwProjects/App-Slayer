package com.appslayer.official;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.nio.ByteBuffer;

// Non-root FPS measurement. Up to 6.3.6 the only FPS paths went
// through the root shell (SurfaceFlinger latency, then gfxinfo),
// so without a root grant the sampler never read anything and
// recordings collected zero samples. This service is the non-root
// path: with the user's screen-capture consent (the system prompt,
// asked fresh each time — Android 14 makes every consent token
// single-use) it mirrors the display into a small ImageReader and
// counts DISTINCT presented frames: a frame whose pixels differ
// from the previous one. That is a real measurement of what the
// screen actually presented — the foreground game's frames — not
// an estimate, and when neither this nor the shell can measure,
// SystemStats keeps reporting no reading, exactly as before.
//
// The latest per-second count is published to
// SystemStats.setProjectionFps, so the overlay chip, the recorder
// and the function test all read it through the same getters as
// the shell paths, with the source reported as "MediaProjection".
//
// The capture runs at a reduced width (360 px): enough to tell
// frames apart, cheap enough to leave running during a game. A
// perfectly static screen honestly reports 0 FPS — nothing new
// was presented.
public class FpsCaptureService extends Service {

    public static final String CHANNEL_ID = "AppSlayerCaptureChannel";
    public static final int NOTIF_ID = 3;

    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";
    private static final int CAPTURE_WIDTH = 360;
    // If no frame has arrived for this long the display is off or
    // the mirror stalled: stop publishing rather than dress up a
    // stale number as live.
    private static final long STALE_MS = 5000;

    private static volatile FpsCaptureService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    public static void start(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, FpsCaptureService.class);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            DiagLog.log(context, "FPS", "capture service start FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, FpsCaptureService.class));
        } catch (Exception ignored) {
        }
    }

    private HandlerThread workerThread;
    private Handler workerHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private volatile long lastSignature = 0;
    private volatile boolean haveSignature = false;
    private volatile int distinctFrames = 0;
    private volatile long lastImageMs = 0;
    private volatile boolean tickerRunning = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!tickerRunning) {
                return;
            }
            int frames = distinctFrames;
            distinctFrames = 0;
            if (lastImageMs > 0 && System.currentTimeMillis() - lastImageMs <= STALE_MS) {
                SystemStats.setProjectionFps((double) frames);
            } else {
                SystemStats.setProjectionFps(null);
            }
            workerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        workerThread = new HandlerThread("AppSlayerFpsCapture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) {
            return START_STICKY; // already capturing; a second start changes nothing
        }
        if (intent == null || !intent.hasExtra(EXTRA_RESULT_CODE)
                || !intent.hasExtra(EXTRA_RESULT_DATA)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        try {
            Notification notification = buildNotification();
            // The mediaProjection foreground service must be up
            // BEFORE the projection is created (Android 14 rule),
            // with the type passed explicitly at this targetSdk.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            DiagLog.log("FPS", "capture startForeground FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            // Android 14 requires a registered callback before the
            // virtual display is created.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, workerHandler);
            startCapture();
            tickerRunning = true;
            workerHandler.postDelayed(ticker, 1000);
            DiagLog.log("FPS", "screen capture started (MediaProjection FPS path)");
        } catch (Exception e) {
            DiagLog.log("FPS", "screen capture setup FAILED: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startCapture() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        int width = CAPTURE_WIDTH;
        int height = Math.max(1, Math.round(
                metrics.heightPixels * (CAPTURE_WIDTH / (float) metrics.widthPixels)));
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        lastImageMs = System.currentTimeMillis();
                        long signature = signature(image);
                        if (!haveSignature || signature != lastSignature) {
                            haveSignature = true;
                            lastSignature = signature;
                            distinctFrames++;
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }
        }, workerHandler);
        virtualDisplay = projection.createVirtualDisplay("AppSlayerFps",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, workerHandler);
    }

    // Cheap frame fingerprint: an FNV-1a hash over a few hundred
    // bytes sampled evenly from the first plane. Two identical
    // presented frames hash the same; any visible change — a game
    // frame, a moved overlay, a blinking cursor — changes it.
    private static long signature(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int remaining = buffer.remaining();
        if (remaining <= 0) {
            return 0;
        }
        long hash = 0xcbf29ce484222325L;
        int samples = 512;
        int step = Math.max(1, remaining / samples);
        for (int i = 0; i < remaining; i += step) {
            hash ^= buffer.get(i) & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    @Override
    public void onDestroy() {
        tickerRunning = false;
        SystemStats.setProjectionFps(null);
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }
        instance = null;
        DiagLog.log("FPS", "screen capture stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.fps_capture_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService("notification");
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }
        return builder.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.fps_capture_notif))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }
}
