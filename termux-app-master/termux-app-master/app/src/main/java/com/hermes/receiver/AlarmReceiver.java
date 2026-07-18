package com.hermes.receiver;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hermes.ui.HermesActivity;
import com.termux.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Hermes 自管闹钟：AlarmManager 精确唤醒 + 全屏通知。
 * 不经过系统时钟 App（MIUI 会清洗第三方 SET_ALARM 的 extras，标签/时间不可靠）。
 * 闹钟持久化在 SharedPreferences，服务启动时重排未来闹钟。
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "AlarmReceiver";
    private static final String PREFS = "hermes_alarms";
    private static final String CHANNEL_ID = "hermes_alarm_channel";

    public static final String EXTRA_ALARM_ID = "alarm_id";
    public static final String EXTRA_MESSAGE = "message";

    @Override
    public void onReceive(Context context, Intent intent) {
        String message = intent.getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.isEmpty()) message = "Hermes 提醒";
        int id = intent.getIntExtra(EXTRA_ALARM_ID, 0);
        Log.i(LOG_TAG, "Alarm fired: " + id + " " + message);
        remove(context, id);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Hermes 闹钟", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Hermes 自管闹钟响铃");
            channel.enableVibration(true);
            channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null);
            nm.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(context, HermesActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, id, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(context, CHANNEL_ID)
            : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("⏰ " + message)
            .setContentText("Hermes 闹钟")
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setFullScreenIntent(openPending, true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(new long[]{0, 500, 300, 500, 300, 500});
        nm.notify(id, builder.build());
    }

    /** 调度一个闹钟；返回错误信息或 null 表示成功。 */
    public static String schedule(@NonNull Context context, int id, long triggerAtMillis,
                                  @NonNull String message) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return "AlarmManager unavailable";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            return "need_exact_alarm_permission";
        }
        Intent intent = new Intent(context, AlarmReceiver.class)
            .putExtra(EXTRA_ALARM_ID, id)
            .putExtra(EXTRA_MESSAGE, message);
        PendingIntent pending = PendingIntent.getBroadcast(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending);
        save(context, id, triggerAtMillis, message);
        Log.i(LOG_TAG, "Alarm scheduled: " + id + " at " + triggerAtMillis);
        return null;
    }

    /** 服务启动时调用：重排所有未来闹钟，清理过期记录。 */
    public static void rescheduleAll(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        JSONObject kept = new JSONObject();
        try {
            JSONObject all = new JSONObject(prefs.getString("alarms", "{}"));
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject alarm = all.getJSONObject(key);
                long triggerAt = alarm.optLong("trigger_at", 0);
                if (triggerAt > now) {
                    String message = alarm.optString("message", "Hermes 提醒");
                    int id = Integer.parseInt(key);
                    // 直接重排（save 会重写，最后再统一覆盖）
                    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                    if (am != null
                        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms())) {
                        Intent intent = new Intent(context, AlarmReceiver.class)
                            .putExtra(EXTRA_ALARM_ID, id)
                            .putExtra(EXTRA_MESSAGE, message);
                        PendingIntent pending = PendingIntent.getBroadcast(context, id, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
                        kept.put(key, alarm);
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to restore alarms", e);
        }
        prefs.edit().putString("alarms", kept.toString()).apply();
    }

    /** 列出所有未来闹钟（含倒计时）。 */
    @NonNull
    public static JSONObject list(@NonNull Context context) {
        JSONObject result = new JSONObject();
        org.json.JSONArray items = new org.json.JSONArray();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            JSONObject all = new JSONObject(prefs.getString("alarms", "{}"));
            Iterator<String> keys = all.keys();
            java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject alarm = all.getJSONObject(key);
                long triggerAt = alarm.optLong("trigger_at", 0);
                if (triggerAt <= now) continue;
                items.put(new JSONObject()
                    .put("id", Integer.parseInt(key))
                    .put("trigger_at", fmt.format(new java.util.Date(triggerAt)))
                    .put("message", alarm.optString("message", "Hermes 提醒")));
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to list alarms", e);
        }
        try {
            result.put("alarms", items).put("count", items.length());
        } catch (JSONException ignored) {
        }
        return result;
    }

    /** 取消一个闹钟；不存在返回 false。 */
    public static boolean cancel(@NonNull Context context, int id) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, AlarmReceiver.class)
            .putExtra(EXTRA_ALARM_ID, id);
        PendingIntent pending = PendingIntent.getBroadcast(context, id, intent,
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        boolean existed = pending != null;
        if (pending != null && am != null) {
            am.cancel(pending);
            pending.cancel();
        }
        remove(context, id);
        return existed;
    }

    private static void save(@NonNull Context context, int id, long triggerAt, @NonNull String message) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject all = new JSONObject(prefs.getString("alarms", "{}"));
            all.put(String.valueOf(id), new JSONObject()
                .put("trigger_at", triggerAt)
                .put("message", message));
            prefs.edit().putString("alarms", all.toString()).apply();
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to save alarm", e);
        }
    }

    private static void remove(@NonNull Context context, int id) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject all = new JSONObject(prefs.getString("alarms", "{}"));
            all.remove(String.valueOf(id));
            prefs.edit().putString("alarms", all.toString()).apply();
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to remove alarm", e);
        }
    }
}
