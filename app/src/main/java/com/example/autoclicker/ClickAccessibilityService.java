package com.example.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class ClickAccessibilityService extends AccessibilityService {

    public interface Listener {
        void onProgress(int done, int total);

        void onFinished(int done, int total, boolean cancelled);

        default void onPositionPicked(int x, int y) {
        }
    }

    public interface PositionCallback {
        void onPicked(float x, float y);
    }

    public static final long MIN_INTERVAL_MS = 50L;
    private static final long MIN_CLICK_DURATION_MS = 20L;
    private static final long MAX_CLICK_DURATION_MS = 50L;

    private static final String PREFS = "autoclicker";
    private static final String KEY_X = "pos_x";
    private static final String KEY_Y = "pos_y";
    private static final String KEY_COUNT = "click_count";
    private static final String KEY_INTERVAL = "click_interval";
    private static final String KEY_DELAY = "start_delay";
    public static final String KEY_SIDEBAR = "show_sidebar";

    private static volatile ClickAccessibilityService instance;
    private static volatile Listener listener;

    public static ClickAccessibilityService getInstance() {
        return instance;
    }

    public static void setListener(Listener value) {
        listener = value;
    }

    private Handler handler;
    private WindowManager windowManager;

    private boolean running;
    private int totalCount;
    private int doneCount;
    private long intervalMs = 500L;
    private long clickDurationMs = MAX_CLICK_DURATION_MS;
    private long nextClickAt;
    private float targetX;
    private float targetY;

    private View pickerView;
    private TextView pickerTip;
    private CrosshairView pickerCrosshair;
    private boolean pickerSelecting;
    private PositionCallback positionCallback;
    private float pickedX = -1f;
    private float pickedY = -1f;

    private View sidebarView;
    private WindowManager.LayoutParams sidebarParams;
    private boolean sidebarVisible;
    private boolean sidebarTemporary;
    private boolean sidebarExpanded;
    private boolean sidebarOnLeft = true;
    private int sidebarY = -1;

    private final Runnable clickRunnable = new Runnable() {
        @Override
        public void run() {
            if (running) {
                performClick();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        instance = this;
        sidebarVisible = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SIDEBAR, false);
        if (sidebarVisible) {
            refreshSidebar();
        }
        notifyProgress();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
        finishClicking(true);
        dismissPickerInternal();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        dismissPickerInternal();
        removeSidebarView();
        if (instance == this) {
            instance = null;
        }
        listener = null;
        super.onDestroy();
    }

    public static boolean isEnabled(Context context) {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null) {
                List<AccessibilityServiceInfo> services = manager
                        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                String full = context.getPackageName() + "/" + ClickAccessibilityService.class.getName();
                String shortId = context.getPackageName() + "/."
                        + ClickAccessibilityService.class.getSimpleName();
                for (AccessibilityServiceInfo info : services) {
                    String id = info.getId();
                    if (id != null && (id.equals(full) || id.equals(shortId))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                String full = context.getPackageName() + "/" + ClickAccessibilityService.class.getName();
                String shortId = context.getPackageName() + "/."
                        + ClickAccessibilityService.class.getSimpleName();
                return enabled.contains(full) || enabled.contains(shortId);
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void startClicking(int count, long interval, float x, float y, long delayMs) {
        if (handler == null) {
            return;
        }
        handler.removeCallbacks(clickRunnable);
        running = true;
        totalCount = count;
        doneCount = 0;
        intervalMs = Math.max(MIN_INTERVAL_MS, interval);
        clickDurationMs = Math.min(MAX_CLICK_DURATION_MS,
                Math.max(MIN_CLICK_DURATION_MS, intervalMs * 2 / 5));
        targetX = x;
        targetY = y;
        notifyProgress();
        nextClickAt = SystemClock.uptimeMillis() + Math.max(0L, delayMs);
        scheduleNextClick();
        refreshSidebar();
    }

    public void stopClicking() {
        finishClicking(true);
    }

    public void clickOnce(float x, float y) {
        if (handler == null || running) {
            return;
        }
        dispatchClick(x, y, null);
    }

    public boolean isRunning() {
        return running;
    }

    public int getDoneCount() {
        return doneCount;
    }

    public int getTotalCount() {
        return totalCount;
    }

    private void performClick() {
        if (!running) {
            return;
        }
        boolean dispatched = dispatchClick(targetX, targetY, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                handleGestureResult(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                handleGestureResult(false);
            }
        });
        if (!dispatched) {
            finishClicking(true);
        }
    }

    private boolean dispatchClick(float x, float y, GestureResultCallback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, clickDurationMs);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return dispatchGesture(gesture, callback, null);
    }

    private void handleGestureResult(boolean completed) {
        if (!running) {
            return;
        }
        if (!completed) {
            finishClicking(true);
            return;
        }
        doneCount++;
        notifyProgress();
        if (totalCount > 0 && doneCount >= totalCount) {
            finishClicking(false);
            return;
        }
        nextClickAt += intervalMs;
        long now = SystemClock.uptimeMillis();
        if (nextClickAt < now) {
            nextClickAt = now;
        }
        scheduleNextClick();
    }

    private void scheduleNextClick() {
        long delay = nextClickAt - SystemClock.uptimeMillis();
        handler.postDelayed(clickRunnable, Math.max(0L, delay));
    }

    private void finishClicking(boolean cancelled) {
        if (!running) {
            return;
        }
        running = false;
        if (handler != null) {
            handler.removeCallbacks(clickRunnable);
        }
        if (listener != null) {
            listener.onFinished(doneCount, totalCount, cancelled);
        }
        refreshSidebar();
    }

    private void notifyProgress() {
        if (listener != null) {
            listener.onProgress(doneCount, totalCount);
        }
    }

    public void startPositionPicker(PositionCallback callback) {
        if (windowManager == null || handler == null) {
            return;
        }
        dismissPickerOverlay();
        positionCallback = callback;
        pickerSelecting = true;
        sidebarExpanded = true;
        if (!sidebarVisible) {
            sidebarTemporary = true;
        }
        showSelectionOverlay();
        refreshSidebar();
    }

    public void setSidebarVisible(boolean visible) {
        if (windowManager == null) {
            return;
        }
        sidebarVisible = visible;
        sidebarTemporary = false;
        if (!visible) {
            sidebarExpanded = false;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SIDEBAR, visible)
                .apply();
        if (visible) {
            refreshSidebar();
        } else {
            removeSidebarView();
        }
    }

    public boolean isSidebarVisible() {
        return sidebarVisible;
    }

    private void refreshSidebar() {
        if (windowManager == null) {
            return;
        }
        if (!sidebarVisible && !sidebarTemporary) {
            removeSidebarView();
            return;
        }
        removeSidebarView();

        View.OnTouchListener drag = new SidebarTouchListener();
        View content;
        if (!sidebarExpanded) {
            TextView tab = new TextView(this);
            tab.setText("控");
            tab.setTextColor(Color.WHITE);
            tab.setTextSize(15f);
            tab.setGravity(Gravity.CENTER);
            tab.setWidth(dp(40));
            tab.setHeight(dp(68));
            GradientDrawable tabBg = new GradientDrawable();
            tabBg.setColor(Color.argb(235, 30, 34, 42));
            tabBg.setCornerRadius(dp(20));
            tab.setBackground(tabBg);
            tab.setOnClickListener(v -> handler.post(() -> {
                sidebarExpanded = true;
                refreshSidebar();
            }));
            tab.setOnTouchListener(drag);
            content = tab;
        } else {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.setPadding(dp(8), dp(6), dp(8), dp(8));
            column.setMinimumWidth(dp(88));
            GradientDrawable columnBg = new GradientDrawable();
            columnBg.setColor(Color.argb(235, 30, 34, 42));
            columnBg.setCornerRadius(dp(20));
            column.setBackground(columnBg);
            column.setOnTouchListener(drag);

            TextView header = new TextView(this);
            header.setText("收起");
            header.setTextColor(Color.argb(210, 255, 255, 255));
            header.setTextSize(11f);
            header.setGravity(Gravity.CENTER);
            header.setPadding(dp(6), dp(6), dp(6), dp(6));
            header.setOnClickListener(v -> handler.post(() -> {
                sidebarExpanded = false;
                refreshSidebar();
            }));
            header.setOnTouchListener(drag);
            column.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            int accent = Color.rgb(0, 191, 165);
            int danger = Color.rgb(198, 40, 40);
            int neutral = Color.argb(255, 62, 68, 80);
            if (pickerSelecting) {
                addSidebarButton(column, "确认", accent,
                        pickedX < 0f ? 0.5f : 1f,
                        () -> handler.post(this::confirmPickerPoint), drag);
                addSidebarButton(column, "取消", danger, 1f,
                        () -> handler.post(this::cancelPicker), drag);
            } else if (running) {
                addSidebarButton(column, "停止", danger, 1f,
                        () -> handler.post(this::stopClicking), drag);
            } else {
                addSidebarButton(column, "取点", accent, 1f,
                        () -> handler.post(() -> startPositionPicker(null)), drag);
                addSidebarButton(column, "开始", accent, 1f,
                        () -> handler.post(this::startFromSidebar), drag);
                addSidebarButton(column, "测试", neutral, 1f,
                        () -> handler.post(this::testFromSidebar), drag);
            }
            content = column;
        }

        int widthGuess = sidebarExpanded ? dp(88) : dp(40);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        sidebarParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        sidebarParams.gravity = Gravity.TOP | Gravity.START;
        sidebarParams.x = sidebarOnLeft ? 0 : Math.max(0, screenWidth - widthGuess);
        sidebarParams.y = sidebarY >= 0 ? sidebarY : dp(160);

        sidebarView = content;
        try {
            windowManager.addView(content, sidebarParams);
        } catch (Exception e) {
            sidebarView = null;
            sidebarParams = null;
            return;
        }
        content.post(this::alignSidebarEdge);
    }

    private void addSidebarButton(LinearLayout parent, String text, int color, float alpha,
                                  Runnable action, View.OnTouchListener drag) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        button.setBackground(bg);
        button.setAlpha(alpha);
        button.setOnClickListener(v -> action.run());
        button.setOnTouchListener(drag);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(button, lp);
    }

    private void startFromSidebar() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int x = prefs.getInt(KEY_X, -1);
        int y = prefs.getInt(KEY_Y, -1);
        int count = prefs.getInt(KEY_COUNT, 100);
        int interval = prefs.getInt(KEY_INTERVAL, 500);
        int delay = prefs.getInt(KEY_DELAY, 3);
        if (x < 0 || y < 0) {
            Toast.makeText(this, "请先取点设置位置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (x > getResources().getDisplayMetrics().widthPixels
                || y > getResources().getDisplayMetrics().heightPixels) {
            Toast.makeText(this, "保存的坐标超出屏幕，请重新取点", Toast.LENGTH_SHORT).show();
            return;
        }
        if (count < 0) {
            count = 0;
        }
        if (interval < MIN_INTERVAL_MS) {
            interval = (int) MIN_INTERVAL_MS;
        }
        if (delay < 0) {
            delay = 0;
        } else if (delay > 60) {
            delay = 60;
        }
        startClicking(count, interval, x, y, delay * 1000L);
        Toast.makeText(this, "已开始，请切换到目标应用", Toast.LENGTH_SHORT).show();
    }

    private void testFromSidebar() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int x = prefs.getInt(KEY_X, -1);
        int y = prefs.getInt(KEY_Y, -1);
        if (x < 0 || y < 0) {
            Toast.makeText(this, "请先取点设置位置", Toast.LENGTH_SHORT).show();
            return;
        }
        clickOnce(x, y);
        Toast.makeText(this, "已执行一次点击", Toast.LENGTH_SHORT).show();
    }

    private void showSelectionOverlay() {
        if (windowManager == null) {
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(40, 0, 0, 0));

        pickerCrosshair = new CrosshairView(this);
        root.addView(pickerCrosshair, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        View capture = new View(this);
        capture.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_UP) {
                selectPickerPoint(event.getRawX(), event.getRawY());
            }
            return true;
        });
        root.addView(capture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        pickerTip = new TextView(this);
        pickerTip.setTextColor(Color.WHITE);
        pickerTip.setTextSize(14f);
        pickerTip.setGravity(Gravity.CENTER);
        int padH = dp(20);
        int padV = dp(12);
        pickerTip.setPadding(padH, padV, padH, padV);
        GradientDrawable tipBg = new GradientDrawable();
        tipBg.setColor(Color.argb(225, 30, 34, 42));
        tipBg.setCornerRadius(dp(24));
        pickerTip.setBackground(tipBg);
        updatePickerText("点击或拖动要模拟点击的位置\n再点侧边栏“确认”保存");
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        tipLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        tipLp.topMargin = dp(72);
        root.addView(pickerTip, tipLp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        pickerView = root;
        try {
            windowManager.addView(root, lp);
        } catch (Exception e) {
            dismissPickerOverlay();
        }
    }

    private void selectPickerPoint(float px, float py) {
        boolean wasUnpicked = pickedX < 0f;
        pickedX = px;
        pickedY = py;
        if (pickerCrosshair != null) {
            pickerCrosshair.setPoint(px, py);
        }
        updatePickerText("已选择 (" + Math.round(px) + ", " + Math.round(py)
                + ")\n可拖动调整，点侧边栏“确认”保存");
        if (wasUnpicked) {
            refreshSidebar();
        }
    }

    private void confirmPickerPoint() {
        if (!pickerSelecting || pickedX < 0f || pickedY < 0f) {
            return;
        }
        float px = pickedX;
        float py = pickedY;
        PositionCallback cb = positionCallback;
        savePosition(px, py);
        dismissPickerOverlay();
        sidebarTemporary = false;
        refreshSidebar();
        if (cb != null) {
            cb.onPicked(px, py);
        } else if (listener != null) {
            listener.onPositionPicked(Math.round(px), Math.round(py));
        }
    }

    private void cancelPicker() {
        dismissPickerOverlay();
        sidebarTemporary = false;
        refreshSidebar();
    }

    private void savePosition(float px, float py) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_X, Math.round(px))
                .putInt(KEY_Y, Math.round(py))
                .apply();
    }

    private void updatePickerText(String text) {
        if (pickerTip != null) {
            pickerTip.setText(text);
        }
    }

    private void dismissPickerOverlay() {
        if (pickerView != null && windowManager != null) {
            try {
                windowManager.removeView(pickerView);
            } catch (Exception ignored) {
            }
        }
        pickerView = null;
        pickerTip = null;
        pickerCrosshair = null;
        pickerSelecting = false;
        positionCallback = null;
        pickedX = -1f;
        pickedY = -1f;
    }

    private void dismissPickerInternal() {
        dismissPickerOverlay();
        sidebarTemporary = false;
        removeSidebarView();
    }

    private void removeSidebarView() {
        if (sidebarView != null && windowManager != null) {
            try {
                windowManager.removeView(sidebarView);
            } catch (Exception ignored) {
            }
        }
        sidebarView = null;
        sidebarParams = null;
    }

    private void alignSidebarEdge() {
        if (sidebarView == null || sidebarParams == null) {
            return;
        }
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int width = sidebarView.getWidth();
        int height = sidebarView.getHeight();
        sidebarParams.x = sidebarOnLeft ? 0 : Math.max(0, screenWidth - width);
        int maxY = Math.max(0, screenHeight - height);
        int y = sidebarY >= 0 ? sidebarY : dp(160);
        sidebarParams.y = Math.min(Math.max(y, 0), maxY);
        sidebarY = sidebarParams.y;
        try {
            windowManager.updateViewLayout(sidebarView, sidebarParams);
        } catch (Exception ignored) {
        }
    }

    private final class SidebarTouchListener implements View.OnTouchListener {
        private final int slop =
                ViewConfiguration.get(ClickAccessibilityService.this).getScaledTouchSlop();
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean dragging;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            WindowManager.LayoutParams lp = sidebarParams;
            View view = sidebarView;
            if (lp == null || view == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        dragging = true;
                    }
                    if (dragging) {
                        int screenWidth = getResources().getDisplayMetrics().widthPixels;
                        int screenHeight = getResources().getDisplayMetrics().heightPixels;
                        int maxX = Math.max(0, screenWidth - view.getWidth());
                        int maxY = Math.max(0, screenHeight - view.getHeight());
                        lp.x = Math.min(Math.max(startX + Math.round(dx), 0), maxX);
                        lp.y = Math.min(Math.max(startY + Math.round(dy), 0), maxY);
                        try {
                            windowManager.updateViewLayout(view, lp);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        int screenWidth = getResources().getDisplayMetrics().widthPixels;
                        sidebarOnLeft = lp.x + view.getWidth() / 2 < screenWidth / 2;
                        sidebarY = lp.y;
                        alignSidebarEdge();
                    } else {
                        v.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    private final class CrosshairView extends View {
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cx = -1f;
        private float cy = -1f;

        CrosshairView(Context context) {
            super(context);
            shadowPaint.setStyle(Paint.Style.STROKE);
            shadowPaint.setStrokeWidth(dp(5));
            shadowPaint.setColor(Color.argb(130, 0, 0, 0));
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(Color.rgb(0, 191, 165));
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(Color.rgb(0, 191, 165));
        }

        void setPoint(float x, float y) {
            cx = x;
            cy = y;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (cx < 0f || cy < 0f) {
                return;
            }
            float ring = dp(16);
            float outer = dp(30);
            float gap = dp(6);
            canvas.drawCircle(cx, cy, ring + dp(1), shadowPaint);
            canvas.drawCircle(cx, cy, ring, strokePaint);
            canvas.drawLine(cx - outer, cy, cx - gap, cy, shadowPaint);
            canvas.drawLine(cx + gap, cy, cx + outer, cy, shadowPaint);
            canvas.drawLine(cx, cy - outer, cx, cy - gap, shadowPaint);
            canvas.drawLine(cx, cy + gap, cx, cy + outer, shadowPaint);
            canvas.drawLine(cx - outer, cy, cx - gap, cy, strokePaint);
            canvas.drawLine(cx + gap, cy, cx + outer, cy, strokePaint);
            canvas.drawLine(cx, cy - outer, cx, cy - gap, strokePaint);
            canvas.drawLine(cx, cy + gap, cx, cy + outer, strokePaint);
            canvas.drawCircle(cx, cy, dp(3), dotPaint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
