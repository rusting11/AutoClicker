package com.example.autoclicker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ClickAccessibilityService.Listener {

    private static final String PREFS = "autoclicker";
    private static final String KEY_X = "pos_x";
    private static final String KEY_Y = "pos_y";
    private static final String KEY_COUNT = "click_count";
    private static final String KEY_INTERVAL = "click_interval";
    private static final String KEY_DELAY = "start_delay";

    private EditText etX;
    private EditText etY;
    private EditText etCount;
    private EditText etInterval;
    private EditText etDelay;
    private TextView tvServiceStatus;
    private TextView tvProgress;
    private MaterialButton btnEnableService;
    private MaterialButton btnToggleSidebar;
    private MaterialButton btnPickPosition;
    private MaterialButton btnTestClick;
    private MaterialButton btnStart;
    private MaterialButton btnStop;

    private SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshServiceStatus();
            syncRunningState();
            uiHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        etX = findViewById(R.id.etX);
        etY = findViewById(R.id.etY);
        etCount = findViewById(R.id.etCount);
        etInterval = findViewById(R.id.etInterval);
        etDelay = findViewById(R.id.etDelay);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvProgress = findViewById(R.id.tvProgress);
        btnEnableService = findViewById(R.id.btnEnableService);
        btnToggleSidebar = findViewById(R.id.btnToggleSidebar);
        btnPickPosition = findViewById(R.id.btnPickPosition);
        btnTestClick = findViewById(R.id.btnTestClick);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        loadSettings();

        btnEnableService.setOnClickListener(v -> openAccessibilitySettings());
        btnToggleSidebar.setOnClickListener(v -> toggleSidebar());
        btnPickPosition.setOnClickListener(v -> pickPosition());
        btnTestClick.setOnClickListener(v -> testClick());
        btnStart.setOnClickListener(v -> startClicking());
        btnStop.setOnClickListener(v -> stopClicking());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClickAccessibilityService.setListener(this);
        loadSettings();
        refreshServiceStatus();
        syncRunningState();
        uiHandler.removeCallbacks(statusTick);
        uiHandler.post(statusTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        persistSettings();
        ClickAccessibilityService.setListener(null);
        uiHandler.removeCallbacks(statusTick);
    }

    private void loadSettings() {
        int x = prefs.getInt(KEY_X, -1);
        int y = prefs.getInt(KEY_Y, -1);
        if (x >= 0) {
            etX.setText(String.valueOf(x));
        }
        if (y >= 0) {
            etY.setText(String.valueOf(y));
        }
        etCount.setText(String.valueOf(prefs.getInt(KEY_COUNT, 100)));
        etInterval.setText(String.valueOf(prefs.getInt(KEY_INTERVAL, 500)));
        etDelay.setText(String.valueOf(prefs.getInt(KEY_DELAY, 3)));
    }

    private void saveSettings(float x, float y, int count, int interval, int delay) {
        prefs.edit()
                .putInt(KEY_X, Math.round(x))
                .putInt(KEY_Y, Math.round(y))
                .putInt(KEY_COUNT, count)
                .putInt(KEY_INTERVAL, interval)
                .putInt(KEY_DELAY, delay)
                .apply();
    }

    private void persistSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        int x = parseInt(etX, -1);
        int y = parseInt(etY, -1);
        if (x >= 0 && y >= 0) {
            editor.putInt(KEY_X, x).putInt(KEY_Y, y);
        }
        int count = parseInt(etCount, 100);
        if (count >= 0) {
            editor.putInt(KEY_COUNT, count);
        }
        int interval = parseInt(etInterval, 500);
        if (interval >= ClickAccessibilityService.MIN_INTERVAL_MS) {
            editor.putInt(KEY_INTERVAL, interval);
        }
        int delay = parseInt(etDelay, 3);
        if (delay >= 0 && delay <= 60) {
            editor.putInt(KEY_DELAY, delay);
        }
        editor.apply();
    }

    private void toggleSidebar() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        service.setSidebarVisible(!service.isSidebarVisible());
        refreshServiceStatus();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请在列表中找到“自动连点器”并开启服务", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshServiceStatus() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        boolean enabled = ClickAccessibilityService.isEnabled(this);

        if (service != null) {
            tvServiceStatus.setText("无障碍服务已连接");
            tvServiceStatus.setTextColor(getColor(R.color.success));
            btnEnableService.setText("服务设置");
        } else if (enabled) {
            tvServiceStatus.setText("服务已开启，正在连接…");
            tvServiceStatus.setTextColor(getColor(R.color.warning));
            btnEnableService.setText("服务设置");
        } else {
            tvServiceStatus.setText("无障碍服务未开启");
            tvServiceStatus.setTextColor(getColor(R.color.danger));
            btnEnableService.setText("去开启");
        }

        boolean ready = service != null;
        btnPickPosition.setEnabled(ready);
        btnTestClick.setEnabled(ready);
        btnPickPosition.setAlpha(ready ? 1f : 0.5f);
        btnTestClick.setAlpha(ready ? 1f : 0.5f);
        btnToggleSidebar.setEnabled(ready);
        btnToggleSidebar.setAlpha(ready ? 1f : 0.5f);
        boolean sidebar = ready && service.isSidebarVisible();
        btnToggleSidebar.setText(sidebar ? "隐藏悬浮控制条" : "显示悬浮控制条");
    }

    private void syncRunningState() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        boolean running = service != null && service.isRunning();
        int done = service == null ? 0 : service.getDoneCount();
        int total = service == null ? 0 : service.getTotalCount();

        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        btnStart.setAlpha(running ? 0.5f : 1f);
        btnStop.setAlpha(running ? 1f : 0.5f);

        if (running) {
            if (total > 0) {
                tvProgress.setText(String.format(Locale.CHINA, "进行中：%d / %d", done, total));
            } else {
                tvProgress.setText(String.format(Locale.CHINA, "进行中：已点击 %d 次（无限）", done));
            }
            tvProgress.setTextColor(getColor(R.color.primary));
        } else if (done > 0) {
            if (total > 0 && done >= total) {
                tvProgress.setText(String.format(Locale.CHINA, "已完成：%d / %d", done, total));
                tvProgress.setTextColor(getColor(R.color.success));
            } else if (total > 0) {
                tvProgress.setText(String.format(Locale.CHINA, "已停止：%d / %d", done, total));
                tvProgress.setTextColor(getColor(R.color.warning));
            } else {
                tvProgress.setText(String.format(Locale.CHINA, "已停止：共点击 %d 次", done));
                tvProgress.setTextColor(getColor(R.color.warning));
            }
        } else {
            tvProgress.setText("就绪");
            tvProgress.setTextColor(getColor(R.color.text_secondary));
        }
    }

    private void pickPosition() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        Toast.makeText(this, "取点模式已开启，请切换到目标应用，再点击悬浮按钮“取点”", Toast.LENGTH_LONG).show();
        service.startPositionPicker((x, y) -> runOnUiThread(() -> {
            int px = Math.round(x);
            int py = Math.round(y);
            etX.setText(String.valueOf(px));
            etY.setText(String.valueOf(py));
            prefs.edit().putInt(KEY_X, px).putInt(KEY_Y, py).apply();
            Toast.makeText(this, "已记录位置：" + px + ", " + py, Toast.LENGTH_SHORT).show();
        }));
    }

    private void testClick() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            return;
        }
        float[] position = readPosition();
        if (position == null) {
            return;
        }
        service.clickOnce(position[0], position[1]);
        Toast.makeText(this, "已执行一次点击", Toast.LENGTH_SHORT).show();
    }

    private void startClicking() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        if (service == null) {
            Toast.makeText(this, "请先在系统设置中开启无障碍服务", Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }

        float[] position = readPosition();
        if (position == null) {
            return;
        }

        int count = parseInt(etCount, -1);
        int interval = parseInt(etInterval, -1);
        int delay = parseInt(etDelay, 0);

        if (count < 0) {
            Toast.makeText(this, "请输入点击次数（0 表示无限次）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (interval < ClickAccessibilityService.MIN_INTERVAL_MS) {
            Toast.makeText(this, "点击间隔不能小于 50 毫秒", Toast.LENGTH_SHORT).show();
            return;
        }
        if (delay < 0 || delay > 60) {
            Toast.makeText(this, "开始延迟请设置在 0-60 秒之间", Toast.LENGTH_SHORT).show();
            return;
        }

        saveSettings(position[0], position[1], count, interval, delay);
        service.startClicking(count, interval, position[0], position[1], delay * 1000L);
        syncRunningState();
        Toast.makeText(this, "已开始，请切换到目标应用", Toast.LENGTH_SHORT).show();
    }

    private void stopClicking() {
        ClickAccessibilityService service = ClickAccessibilityService.getInstance();
        if (service != null) {
            service.stopClicking();
        }
    }

    @Nullable
    private float[] readPosition() {
        int x = parseInt(etX, -1);
        int y = parseInt(etY, -1);
        if (x < 0 || y < 0) {
            Toast.makeText(this, "请先设置点击位置", Toast.LENGTH_SHORT).show();
            return null;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (x > metrics.widthPixels || y > metrics.heightPixels) {
            Toast.makeText(this, "坐标超出屏幕范围", Toast.LENGTH_SHORT).show();
            return null;
        }
        return new float[]{x, y};
    }

    private int parseInt(EditText editText, int defaultValue) {
        String text = editText.getText() == null ? "" : editText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    public void onProgress(int done, int total) {
        runOnUiThread(this::syncRunningState);
    }

    @Override
    public void onFinished(int done, int total, boolean cancelled) {
        runOnUiThread(() -> {
            syncRunningState();
            if (cancelled) {
                Toast.makeText(this, "已停止连点", Toast.LENGTH_SHORT).show();
            } else if (total > 0 && done >= total) {
                Toast.makeText(this, "连点完成，共 " + done + " 次", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onPositionPicked(int x, int y) {
        runOnUiThread(() -> {
            etX.setText(String.valueOf(x));
            etY.setText(String.valueOf(y));
            prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply();
        });
    }
}
