package com.hermes.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AccessibilityService that exposes UI automation capabilities to Hermes.
 *
 * <p>When enabled by the user in Android accessibility settings, this service
 * can inspect the screen hierarchy and perform clicks/text input on behalf of
 * Hermes tools.</p>
 */
public class HermesAccessibilityService extends AccessibilityService {

    private static final String LOG_TAG = "HermesAccessibility";
    private static HermesAccessibilityService sInstance;

    public static HermesAccessibilityService getInstance() {
        return sInstance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        Log.i(LOG_TAG, "Accessibility service connected");

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        Log.i(LOG_TAG, "Accessibility service disconnected");
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Events are handled on-demand by tools; no need to buffer here.
    }

    @Override
    public void onInterrupt() {
        // No continuous feedback to interrupt.
    }

    /**
     * Dump a simple text representation of the active window hierarchy.
     */
    public String dumpWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        dumpNode(root, sb, 0);
        root.recycle();
        return sb.toString();
    }

    private void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(node.getClassName() != null ? node.getClassName() : "View");
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(" text=").append(text);
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(" desc=").append(desc);
        }
        sb.append(" clickable=").append(node.isClickable());
        sb.append("\n");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            dumpNode(child, sb, depth + 1);
            if (child != null) child.recycle();
        }
    }

    /**
     * Find and click the first node whose text or content description contains
     * the given substring.
     */
    public boolean clickByText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findNodeByText(root, text);
        boolean clicked = false;
        if (target != null) {
            clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            target.recycle();
        }
        root.recycle();
        return clicked;
    }

    /**
     * Find a focused or first editable node and set its text.
     */
    public boolean inputText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findEditableNode(root);
        boolean success = false;
        if (target != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            }
            target.recycle();
        }
        root.recycle();
        return success;
    }

    private AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        String lower = text.toLowerCase();
        if ((nodeText != null && nodeText.toString().toLowerCase().contains(lower))
            || (nodeDesc != null && nodeDesc.toString().toLowerCase().contains(lower))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findNodeByText(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isFocused()) return node;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findEditableNode(child);
            if (found != null) return found;
        }
        return null;
    }

    /** 全局导航动作：GLOBAL_ACTION_HOME/BACK/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/POWER_DIALOG。 */
    public boolean globalAction(int action) {
        return performGlobalAction(action);
    }

    /** 手势滑动（坐标为屏幕像素）。 */
    public boolean swipe(float x1, float y1, float x2, float y2, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
            .build();
        return dispatchGesture(gesture, null, null);
    }

    /** 截图保存为 PNG（API 30+），同步等待结果。 */
    public boolean takeScreenshotSync(File outFile, long timeoutMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        CountDownLatch latch = new CountDownLatch(1);
        boolean[] ok = {false};
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult screenshot) {
                try {
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                    screenshot.getHardwareBuffer().close();
                    if (bitmap != null) {
                        //noinspection ResultOfMethodCallIgnored
                        outFile.getParentFile().mkdirs();
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                        }
                        ok[0] = true;
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Screenshot save failed", e);
                }
                latch.countDown();
            }

            @Override
            public void onFailure(int errorCode) {
                Log.w(LOG_TAG, "Screenshot failed: " + errorCode);
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }
}
