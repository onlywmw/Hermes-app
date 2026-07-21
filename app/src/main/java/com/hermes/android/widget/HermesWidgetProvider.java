package com.hermes.android.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.hermes.android.CapabilityExecutor;
import com.hermes.android.CommandResult;
import com.hermes.android.ParsedCommand;
import com.hermes.android.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Hermes home screen widget - quick action buttons.
 * Executes commands directly via CapabilityExecutor (no Termux needed).
 * P0-2: 签名级权限保护 + 指令白名单
 */
public class HermesWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_EXECUTE = "com.hermes.android.widget.ACTION_EXECUTE";
    public static final String ACTION_REFRESH = "com.hermes.android.widget.ACTION_REFRESH";
    public static final String EXTRA_COMMAND = "command";

    /** P0-2: 只允许小组件预置的快捷指令 */
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
        "打开手电筒", "关闭手电筒", "电量多少", "当前音量",
        "WiFi状态", "震动", "亮度调到 128", "设备信息",
        "截屏", "ip地址", "应用列表", "联系人",
        "最近短信", "读取剪贴板"
    ));

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.hermes_widget_layout);

        // Setup the list adapter
        Intent serviceIntent = new Intent(context, HermesWidgetService.class);
        serviceIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        serviceIntent.setData(Uri.parse(serviceIntent.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);
        views.setEmptyView(R.id.widget_list, R.id.widget_empty);

        // Setup click template - each item fills in EXTRA_COMMAND
        Intent clickIntent = new Intent(context, HermesWidgetProvider.class);
        clickIntent.setAction(ACTION_EXECUTE);
        clickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        clickIntent.setData(Uri.parse(clickIntent.toUri(Intent.URI_INTENT_SCHEME)));
        PendingIntent clickPending = PendingIntent.getBroadcast(context, 0, clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        views.setPendingIntentTemplate(R.id.widget_list, clickPending);

        // Refresh button
        Intent refreshIntent = new Intent(context, HermesWidgetProvider.class);
        refreshIntent.setAction(ACTION_REFRESH);
        refreshIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        PendingIntent refreshPending = PendingIntent.getBroadcast(context, 1, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPending);

        // Open app button
        Intent openIntent = new Intent(context, com.hermes.android.HermesActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(context, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_open_app, openPending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            super.onReceive(context, intent);
            return;
        }

        switch (action) {
            case ACTION_EXECUTE: {
                String command = intent.getStringExtra(EXTRA_COMMAND);
                if (command != null && !command.isEmpty()) {
                    executeCommand(context, command);
                }
                break;
            }
            case ACTION_REFRESH: {
                int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
                AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list);
                } else {
                    int[] ids = mgr.getAppWidgetIds(new ComponentName(context, HermesWidgetProvider.class));
                    for (int id : ids) {
                        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list);
                    }
                }
                Toast.makeText(context, "🔄 已刷新", Toast.LENGTH_SHORT).show();
                break;
            }
            default:
                super.onReceive(context, intent);
        }
    }

    private void executeCommand(Context context, String command) {
        // P0-2: 白名单过滤
        if (!ALLOWED_COMMANDS.contains(command)) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(context, "小组件不支持此指令", Toast.LENGTH_SHORT).show());
            return;
        }
        // P0-J4: 异步执行, 避免阻塞 BroadcastReceiver 主线程 (10s 限制)
        new Thread(() -> {
            try {
                com.hermes.android.IntentParser parser = new com.hermes.android.IntentParser();
                ParsedCommand cmd = parser.parse(command);
                if (cmd == null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, "未识别: " + command, Toast.LENGTH_SHORT).show());
                    return;
                }
                CapabilityExecutor executor = new CapabilityExecutor();
                executor.init(context);
                CommandResult result = executor.execute(context, cmd);
                String icon = result.isSuccess() ? "OK" : "FAIL";
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, icon + " " + result.getMessage(), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
