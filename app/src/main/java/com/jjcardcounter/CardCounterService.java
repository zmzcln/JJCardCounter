package com.jjcardcounter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * 核心服务：录屏 -> 定时截图 -> 识别手牌区/出牌区 -> 更新记牌器 -> 刷新悬浮窗。
 * v1.0.5：解决屏幕方向变化导致截图尺寸与裁剪坐标不匹配的问题；
 *         每次 tick 用实际位图尺寸裁剪；按横屏布局重设默认坐标。
 */
public class CardCounterService extends Service {
    private static final String TAG = "JJCardCounter";

    private WindowManager wm;
    private View floatView;
    private TextView tvRemain;
    private int screenW, screenH, density;
    private MediaProjection mMediaProjection;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private final Handler handler = new Handler();
    private CardRecognizer recognizer;
    private final CardCounter counter = new CardCounter();
    private boolean running = false;
    private boolean initialized = false;
    private int tickCount = 0;

    // v1.0.6：基于 36 张真机横屏截图(2400x1080)实测坐标校准。
    // 手牌区横跨整个底部（之前 0.30~0.70 太窄，只认到 4 张），牌角固定在牌顶。
    private final float[] handArea = {0.06f, 0.70f, 0.96f, 0.97f};
    // 出牌区：左家(左侧中部)、自己(中下方)、右家(右侧中部)。
    // 顶部只要 <= 牌组顶部即可（识别会扫顶部 6 个牌角高度覆盖牌组），底部随意。
    private final float[][] playAreas = {
            {0.03f, 0.24f, 0.34f, 0.55f}, // 左家
            {0.33f, 0.38f, 0.67f, 0.66f}, // 自己出牌区（中下方）
            {0.66f, 0.24f, 0.97f, 0.55f}  // 右家
    };

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.log("SERVICE onCreate start");
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            updateScreenMetrics();

            recognizer = new CardRecognizer(getApplicationContext());
            recognizer.loadTemplates();
            MyApplication.log("templates loaded; templateOK=" + recognizer.isLoaded());

            initFloatView();
            createChannelAndForeground();
            initialized = true;
            MyApplication.log("SERVICE onCreate OK");
        } catch (Throwable e) {
            Log.e(TAG, "onCreate failed", e);
            MyApplication.log("SERVICE onCreate FAILED: " + Log.getStackTraceString(e));
        }
    }

    private void updateScreenMetrics() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        density = m.densityDpi;
        screenW = m.widthPixels;
        screenH = m.heightPixels;
        MyApplication.log("screen=" + screenW + "x" + screenH + " density=" + density);
    }

    private void initFloatView() {
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        floatView = inflater.inflate(R.layout.floating_layout, null);
        tvRemain = floatView.findViewById(R.id.tv_remain);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;
        wm.addView(floatView, lp);
        floatView.findViewById(R.id.btn_close).setOnClickListener(v -> stopSelf());
        MyApplication.log("floatView added");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MyApplication.log("onStartCommand start");
        if (!initialized) {
            MyApplication.log("onStartCommand: not initialized, stop");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            MyApplication.log("onStartCommand: intent null");
            return START_NOT_STICKY;
        }
        createChannelAndForeground();

        try {
            int code = intent.getIntExtra("code", -1);
            Intent data = intent.getParcelableExtra("data");
            MyApplication.log("code=" + code + " data=" + (data != null));
            if (data == null) {
                showError("录屏授权数据为空，请重新启动应用授权。");
                return START_NOT_STICKY;
            }
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mMediaProjection = mpm.getMediaProjection(code, data);
            if (mMediaProjection == null) {
                showError("MediaProjection 创建失败，请重新授权录屏。");
                return START_NOT_STICKY;
            }
            MyApplication.log("MediaProjection created");

            createCapture();
            running = true;
            showText("记牌器运行中...");
            handler.postDelayed(this::tick, 1000);
        } catch (Throwable e) {
            Log.e(TAG, "onStartCommand failed", e);
            MyApplication.log("onStartCommand FAILED: " + Log.getStackTraceString(e));
            showError("启动失败: " + e.getMessage());
        }
        return START_STICKY;
    }

    private void createCapture() {
        releaseCapture();
        mImageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay("jj", screenW, screenH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mImageReader.getSurface(), null, null);
        MyApplication.log("Capture created " + screenW + "x" + screenH);
    }

    private void releaseCapture() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void tick() {
        if (!running) return;
        try {
            // 方向可能变化，用实际位图尺寸裁剪
            updateScreenMetrics();
            if (mImageReader == null || mImageReader.getWidth() != screenW || mImageReader.getHeight() != screenH) {
                MyApplication.log("screen size changed, recreate capture");
                createCapture();
            }

            Image img = mImageReader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = imageToBitmap(img);
                // 用实际图片尺寸覆盖 screenW/screenH，确保裁剪正确
                screenW = bmp.getWidth();
                screenH = bmp.getHeight();

                int[] hand = recognizer.recognizeHand(cropArea(bmp, handArea));
                int[] desk = new int[15];
                for (float[] a : playAreas) {
                    int[] c = recognizer.recognizePlay(cropArea(bmp, a));
                    for (int i = 0; i < 15; i++) desk[i] += c[i];
                }
                counter.update(hand, desk);
                updateUI(hand, desk);
                bmp.recycle();
                tickCount++;
                if (tickCount <= 3) {
                    MyApplication.log("tick#" + tickCount + " size=" + screenW + "x" + screenH
                            + " hand=" + sum(hand) + " desk=" + sum(desk));
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "tick error", e);
            MyApplication.log("tick FAILED: " + Log.getStackTraceString(e));
        }
        handler.postDelayed(this::tick, 2000);
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    private Bitmap cropArea(Bitmap src, float[] a) {
        // 用实际位图尺寸，避免方向/分辨率不一致导致空裁剪
        int w = src.getWidth();
        int h = src.getHeight();
        int x0 = clamp((int) (a[0] * w), 0, w);
        int y0 = clamp((int) (a[1] * h), 0, h);
        int x1 = clamp((int) (a[2] * w), 0, w);
        int y1 = clamp((int) (a[3] * h), 0, h);
        if (x1 <= x0 || y1 <= y0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buf = planes[0].getBuffer();
        int ps = planes[0].getPixelStride();
        int rs = planes[0].getRowStride();
        int rp = rs - ps * w;
        Bitmap bmp = Bitmap.createBitmap(w + rp / ps, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        bmp = Bitmap.createBitmap(bmp, 0, 0, w, h);
        image.close();
        return bmp;
    }

    private void updateUI(int[] hand, int[] desk) {
        int[] rem = counter.getRemaining();
        StringBuilder sb = new StringBuilder();
        sb.append("剩余  ");
        for (int i = 2; i < 15; i++) {
            sb.append(CardCounter.TYPE_NAMES[i]).append(':').append(rem[i]).append(" ");
        }
        sb.append("王:").append(rem[0] + rem[1]);
        sb.append("\n手:").append(sum(hand)).append(" 桌:").append(sum(desk));
        showText(sb.toString());
    }

    private void showText(String text) {
        if (tvRemain != null) {
            tvRemain.post(() -> tvRemain.setText(text));
        }
    }

    private void showError(String msg) {
        Log.e(TAG, msg);
        MyApplication.log("UI ERROR: " + msg);
        if (tvRemain != null) {
            tvRemain.post(() -> tvRemain.setText("[错误] " + msg));
        }
    }

    private void createChannelAndForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("jj_chan", "jj",
                    NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification.Builder b = new Notification.Builder(this);
        Intent ni = new Intent(this, MainActivity.class);
        b.setContentIntent(PendingIntent.getActivity(this, 0, ni, PendingIntent.FLAG_IMMUTABLE))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("JJ记牌器")
                .setContentText("服务运行中");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setChannelId("jj_chan");
        }
        startForeground(1, b.build());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        MyApplication.log("onConfigurationChanged orientation=" + newConfig.orientation);
        updateScreenMetrics();
        // tick() 里检测到尺寸变化会自动重建 ImageReader
    }

    @Override
    public void onDestroy() {
        MyApplication.log("SERVICE onDestroy");
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            if (floatView != null) wm.removeView(floatView);
        } catch (Exception ignored) {
        }
        releaseCapture();
        if (mMediaProjection != null) mMediaProjection.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
