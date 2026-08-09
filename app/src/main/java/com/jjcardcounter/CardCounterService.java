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
 * 坐标使用归一化比例（相对屏幕尺寸）。
 * v1.0.3：每个关键步骤写 MyApplication.log，便于真机崩溃诊断。
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

    // 手牌区（底部一排），归一化 [x0,y0,x1,y1]
    private final float[] handArea = {0.0f, 0.70f, 1.0f, 0.97f};
    // 出牌区（三家各自出牌的位置）。坐标系待手机上按真实布局校准。
    private final float[][] playAreas = {
            {0.27f, 0.24f, 0.42f, 0.46f}, // 左家
            {0.34f, 0.18f, 0.60f, 0.66f}, // 自己（中下）
            {0.58f, 0.24f, 0.73f, 0.46f}  // 右家
    };

    @Override
    public void onCreate() {
        super.onCreate();
        MyApplication.log("SERVICE onCreate start");
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(m);
            density = m.densityDpi;
            screenW = m.widthPixels;
            screenH = m.heightPixels;
            MyApplication.log("screen=" + screenW + "x" + screenH + " density=" + density);

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

            mImageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
            mVirtualDisplay = mMediaProjection.createVirtualDisplay("jj", screenW, screenH, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mImageReader.getSurface(), null, null);
            MyApplication.log("VirtualDisplay created");
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

    private void tick() {
        if (!running) return;
        try {
            Image img = mImageReader.acquireLatestImage();
            if (img != null) {
                Bitmap bmp = imageToBitmap(img);
                int[] hand = recognizer.recognizeHand(cropArea(bmp, handArea));
                int[] desk = new int[15];
                for (float[] a : playAreas) {
                    int[] c = recognizer.recognizePlay(cropArea(bmp, a));
                    for (int i = 0; i < 15; i++) desk[i] += c[i];
                }
                counter.update(hand, desk);
                updateUI();
                bmp.recycle();
                tickCount++;
                if (tickCount == 1) {
                    MyApplication.log("first tick OK; handCount=" + sum(hand)
                            + " deskCount=" + sum(desk));
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "tick error", e);
            MyApplication.log("tick FAILED: " + Log.getStackTraceString(e));
        }
        handler.postDelayed(this::tick, 1500);
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    private Bitmap cropArea(Bitmap src, float[] a) {
        int x0 = clamp((int) (a[0] * screenW), 0, src.getWidth());
        int y0 = clamp((int) (a[1] * screenH), 0, src.getHeight());
        int x1 = clamp((int) (a[2] * screenW), 0, src.getWidth());
        int y1 = clamp((int) (a[3] * screenH), 0, src.getHeight());
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

    private void updateUI() {
        int[] rem = counter.getRemaining();
        StringBuilder sb = new StringBuilder();
        sb.append("剩余  ");
        for (int i = 2; i < 15; i++) {
            sb.append(CardCounter.TYPE_NAMES[i]).append(':').append(rem[i]).append(" ");
        }
        sb.append("王:").append(rem[0] + rem[1]);
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
        if (wm == null) return;
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(m);
        screenW = m.widthPixels;
        screenH = m.heightPixels;
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
        if (mVirtualDisplay != null) mVirtualDisplay.release();
        if (mMediaProjection != null) mMediaProjection.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
