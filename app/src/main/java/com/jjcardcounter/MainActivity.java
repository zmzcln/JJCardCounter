package com.jjcardcounter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_MEDIA = 100;
    private static final int REQ_OVERLAY = 101;

    private TextView status;
    private Button start;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.tv_status);
        start = findViewById(R.id.btn_start);

        updateUiState();

        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                openOverlaySettings();
                return;
            }
            requestCapture();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiState();
        // If user just came back from granting overlay permission, continue to capture.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            requestCapture();
        }
    }

    private void updateUiState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            status.setText("首次使用需要开启\"显示在其他应用上层\"权限。\n点下方按钮会自动跳转系统设置，找到 JJ记牌器 并允许。");
            start.setText("去开启悬浮窗权限");
        } else {
            status.setText("权限已就绪，点击开始记牌。");
            start.setText("开始记牌");
        }
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQ_OVERLAY);
            status.setText("请在系统设置里找到 JJ记牌器，开启\"显示在其他应用上层\"，然后返回。");
        } catch (Exception e) {
            status.setText("无法自动跳转，请手动去：设置 → 应用设置 → 授权管理 → 悬浮窗 → 开启 JJ记牌器");
        }
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            openOverlaySettings();
            return;
        }
        android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            updateUiState();
            return;
        }
        if (req == REQ_MEDIA && res == RESULT_OK && data != null) {
            Intent s = new Intent(this, CardCounterService.class);
            s.putExtra("code", res);
            s.putExtra("data", data);
            startForegroundService(s);
            finish();
        }
    }
}
