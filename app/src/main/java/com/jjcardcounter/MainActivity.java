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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        TextView status = findViewById(R.id.tv_status);
        Button start = findViewById(R.id.btn_start);
        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                status.setText("请先授权悬浮窗权限");
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
                return;
            }
            requestCapture();
        });
    }

    private void requestCapture() {
        android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                requestCapture();
            }
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
