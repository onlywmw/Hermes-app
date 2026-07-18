package com.hermes.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.app.admin.DevicePolicyManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.Manifest;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.hermes.receiver.AlarmReceiver;
import com.hermes.service.HermesAccessibilityService;
import com.hermes.service.HermesDeviceAdminReceiver;
import com.termux.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Dispatches JSON-RPC method calls from Hermes into Android system APIs.
 *
 * <p>This initial implementation exposes a small set of safe tools for the
 * Milestone 2 bridge test. Additional tools (sensors, calls, root, Shizuku,
 * accessibility, etc.) are added in later milestones.</p>
 */
public class AndroidToolBridge {

    private static final String LOG_TAG = "AndroidToolBridge";

    private final Context mContext;

    public AndroidToolBridge(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    public JSONObject handle(@NonNull String method, @NonNull JSONObject params) throws JSONException {
        switch (method) {
            case "ping":
                return new JSONObject().put("ok", true);
            case "clipboard_read":
                return handleClipboardRead();
            case "clipboard_write":
                return handleClipboardWrite(params);
            case "notification":
                return handleNotificationShow(params);
            case "device_info":
                return handleDeviceInfo();
            case "vibrate":
                return handleVibrate(params);
            case "torch":
                return handleTorch(params);
            case "battery":
                return handleBatteryStatus();
            case "root_shell":
                return handleRootShell(params);
            case "shell":
                return handleShell(params);
            case "accessibility_dump":
                return handleAccessibilityDump();
            case "accessibility_click":
                return handleAccessibilityClick(params);
            case "accessibility_input":
                return handleAccessibilityInput(params);
            case "device_admin_lock":
                return handleDeviceAdminLock();
            case "device_admin_wipe":
                return handleDeviceAdminWipe(params);
            case "location_get":
                return handleLocationGet();
            case "sms_list":
                return handleSmsList(params);
            case "sms_send":
                return handleSmsSend(params);
            case "contacts_list":
                return handleContactsList();
            case "app_list":
                return handleAppList();
            case "app_open":
                return handleAppOpen(params);
            case "volume_get":
                return handleVolumeGet();
            case "volume_set":
                return handleVolumeSet(params);
            case "brightness_get":
                return handleBrightnessGet();
            case "brightness_set":
                return handleBrightnessSet(params);
            case "open_url":
                return handleOpenUrl(params);
            case "alarm_set":
                return handleAlarmSet(params);
            case "calendar_add":
                return handleCalendarAdd(params);
            default:
                return new JSONObject().put("error", "Unknown method: " + method);
        }
    }

    private JSONObject handleClipboardRead() throws JSONException {
        ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        JSONObject result = new JSONObject();
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                result.put("text", text != null ? text.toString() : "");
            } else {
                result.put("text", "");
            }
        } else {
            result.put("text", "");
        }
        return result;
    }

    private JSONObject handleClipboardWrite(@NonNull JSONObject params) throws JSONException {
        String text = params.optString("text", "");
        ClipboardManager clipboard = (ClipboardManager) mContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("Hermes", text);
            clipboard.setPrimaryClip(clip);
        }
        return new JSONObject().put("success", true);
    }

    private JSONObject handleNotificationShow(@NonNull JSONObject params) throws JSONException {
        String title = params.optString("title", "Hermes");
        String message = params.optString("message", "");

        NotificationManager manager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return new JSONObject().put("success", false).put("error", "NotificationManager unavailable");
        }

        String channelId = "hermes_bridge_notifications";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                channelId, "Hermes Bridge", NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(mContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setAutoCancel(true)
            .build();

        int notificationId = (int) System.currentTimeMillis();
        manager.notify(notificationId, notification);

        return new JSONObject()
            .put("success", true)
            .put("notification_id", notificationId);
    }

    private JSONObject handleDeviceInfo() throws JSONException {
        return new JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_version", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT);
    }

    private JSONObject handleVibrate(@NonNull JSONObject params) throws JSONException {
        long duration = params.optLong("duration", 200);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
            return new JSONObject().put("success", true);
        }
        return new JSONObject().put("success", false).put("error", "Vibrator unavailable");
    }

    private JSONObject handleTorch(@NonNull JSONObject params) throws JSONException {
        boolean on = params.optBoolean("on", true);
        CameraManager cameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            return new JSONObject().put("success", false).put("error", "CameraManager unavailable");
        }
        try {
            // 不能直接用 cameraIdList[0]：多摄设备上前几个可能是无闪光灯的副摄/前摄，
            // 对无闪光摄像头 setTorchMode 会静默失败（灯不亮也不报错）。
            String cameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cc = cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                return new JSONObject().put("success", false)
                    .put("error", "设备没有可用的闪光灯");
            }
            cameraManager.setTorchMode(cameraId, on);
            return new JSONObject().put("success", true).put("camera_id", cameraId);
        } catch (CameraAccessException e) {
            return new JSONObject().put("success", false).put("error", e.getMessage());
        }
    }

    private JSONObject handleBatteryStatus() throws JSONException {
        Intent batteryIntent = mContext.registerReceiver(null,
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject result = new JSONObject();
        if (batteryIntent != null) {
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            float pct = scale > 0 ? (level / (float) scale) * 100f : -1f;
            result.put("level_percent", Math.round(pct));
            result.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        } else {
            result.put("level_percent", -1);
            result.put("charging", false);
        }
        return result;
    }

    private JSONObject handleShell(@NonNull JSONObject params) throws JSONException {
        String command = params.optString("command", "");
        if (command.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "No command provided");
        }
        return executeShell(command, false);
    }

    private JSONObject handleRootShell(@NonNull JSONObject params) throws JSONException {
        String command = params.optString("command", "");
        if (command.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "No command provided");
        }
        return executeShell(command, true);
    }

    private JSONObject executeShell(String command, boolean asRoot) {
        JSONObject result = new JSONObject();
        try {
            Process process;
            if (asRoot) {
                process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            } else {
                process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            }
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream()));
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            String line;
            while ((line = stdout.readLine()) != null) out.append(line).append("\n");
            while ((line = stderr.readLine()) != null) err.append(line).append("\n");
            int exitCode = process.waitFor();
            result.put("success", exitCode == 0);
            result.put("exit_code", exitCode);
            result.put("stdout", out.toString().trim());
            result.put("stderr", err.toString().trim());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return result;
    }

    private JSONObject handleAccessibilityDump() throws JSONException {
        HermesAccessibilityService service = HermesAccessibilityService.getInstance();
        if (service == null) {
            return new JSONObject()
                .put("success", false)
                .put("error", "Accessibility service not enabled. Enable it in Android Settings > Accessibility > Hermes.");
        }
        return new JSONObject()
            .put("success", true)
            .put("window", service.dumpWindow());
    }

    private JSONObject handleAccessibilityClick(@NonNull JSONObject params) throws JSONException {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "text required");
        }
        HermesAccessibilityService service = HermesAccessibilityService.getInstance();
        if (service == null) {
            return new JSONObject()
                .put("success", false)
                .put("error", "Accessibility service not enabled");
        }
        boolean clicked = service.clickByText(text);
        return new JSONObject().put("success", clicked);
    }

    private JSONObject handleAccessibilityInput(@NonNull JSONObject params) throws JSONException {
        String text = params.optString("text", "");
        HermesAccessibilityService service = HermesAccessibilityService.getInstance();
        if (service == null) {
            return new JSONObject()
                .put("success", false)
                .put("error", "Accessibility service not enabled");
        }
        boolean success = service.inputText(text);
        return new JSONObject().put("success", success);
    }

    private JSONObject handleDeviceAdminLock() throws JSONException {
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(mContext, HermesDeviceAdminReceiver.class);
        if (dpm == null || !dpm.isAdminActive(admin)) {
            return new JSONObject()
                .put("success", false)
                .put("error", "Device admin not enabled. Enable it in Android Settings > Security > Device admin apps.");
        }
        dpm.lockNow();
        return new JSONObject().put("success", true);
    }

    private JSONObject handleDeviceAdminWipe(@NonNull JSONObject params) throws JSONException {
        boolean external = params.optBoolean("external", false);
        DevicePolicyManager dpm = (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(mContext, HermesDeviceAdminReceiver.class);
        if (dpm == null || !dpm.isAdminActive(admin)) {
            return new JSONObject()
                .put("success", false)
                .put("error", "Device admin not enabled");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
            dpm.wipeData(external ? DevicePolicyManager.WIPE_EXTERNAL_STORAGE : 0);
        }
        return new JSONObject().put("success", true);
    }

    private JSONObject handleLocationGet() throws JSONException {
        android.location.LocationManager lm = (android.location.LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            return new JSONObject().put("success", false).put("error", "LocationManager unavailable");
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        org.json.JSONArray providers = new org.json.JSONArray();
        for (String p : lm.getAllProviders()) providers.put(p);
        result.put("providers", providers);

        android.location.Location last = null;
        try {
            if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
                last = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            }
            if (last == null && lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                last = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException e) {
            return new JSONObject().put("success", false).put("error", "Location permission denied: " + e.getMessage());
        }

        if (last != null) {
            result.put("latitude", last.getLatitude());
            result.put("longitude", last.getLongitude());
            result.put("accuracy", last.getAccuracy());
            result.put("time", last.getTime());
        }
        return result;
    }

    private JSONObject handleSmsList(@NonNull JSONObject params) throws JSONException {
        android.net.Uri uri = android.net.Uri.parse("content://sms/inbox");
        int limit = params.optInt("limit", 20);
        JSONObject result = new JSONObject();
        org.json.JSONArray messages = new org.json.JSONArray();
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(uri,
                new String[]{"_id", "address", "body", "date", "read"},
                null, null, "date DESC LIMIT " + limit);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject msg = new JSONObject();
                    msg.put("id", cursor.getString(cursor.getColumnIndexOrThrow("_id")));
                    msg.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    msg.put("body", cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    msg.put("date", cursor.getLong(cursor.getColumnIndexOrThrow("date")));
                    msg.put("read", cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1);
                    messages.put(msg);
                }
            }
            result.put("success", true);
            result.put("messages", messages);
        } catch (SecurityException e) {
            result.put("success", false);
            result.put("error", "READ_SMS permission denied: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    private JSONObject handleSmsSend(@NonNull JSONObject params) throws JSONException {
        String to = params.optString("to", "");
        String body = params.optString("body", "");
        if (to.isEmpty() || body.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "Missing 'to' or 'body'");
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(to, null, body, null, null);
            return new JSONObject().put("success", true);
        } catch (Exception e) {
            return new JSONObject().put("success", false).put("error", e.getMessage());
        }
    }

    private JSONObject handleContactsList() throws JSONException {
        JSONObject result = new JSONObject();
        org.json.JSONArray contacts = new org.json.JSONArray();
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject c = new JSONObject();
                    c.put("name", cursor.getString(cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)));
                    c.put("phone", cursor.getString(cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER)));
                    contacts.put(c);
                }
            }
            result.put("success", true);
            result.put("contacts", contacts);
        } catch (SecurityException e) {
            result.put("success", false);
            result.put("error", "READ_CONTACTS permission denied: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    private JSONObject handleAppList() throws JSONException {
        PackageManager pm = mContext.getPackageManager();
        org.json.JSONArray apps = new org.json.JSONArray();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            JSONObject obj = new JSONObject();
            obj.put("package", app.packageName);
            obj.put("label", pm.getApplicationLabel(app).toString());
            obj.put("system", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            apps.put(obj);
        }
        return new JSONObject().put("success", true).put("apps", apps);
    }

    private JSONObject handleAppOpen(@NonNull JSONObject params) throws JSONException {
        String packageName = params.optString("package", "");
        if (packageName.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "Missing 'package'");
        }
        PackageManager pm = mContext.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return new JSONObject().put("success", false).put("error", "No launch intent");
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(launch);
        return new JSONObject().put("success", true);
    }

    private JSONObject handleVolumeGet() throws JSONException {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        JSONObject result = new JSONObject();
        if (am != null) {
            result.put("music", am.getStreamVolume(AudioManager.STREAM_MUSIC));
            result.put("music_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            result.put("ring", am.getStreamVolume(AudioManager.STREAM_RING));
            result.put("ring_max", am.getStreamMaxVolume(AudioManager.STREAM_RING));
            result.put("success", true);
        } else {
            result.put("success", false).put("error", "AudioManager unavailable");
        }
        return result;
    }

    private JSONObject handleVolumeSet(@NonNull JSONObject params) throws JSONException {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return new JSONObject().put("success", false).put("error", "AudioManager unavailable");
        }
        int stream = params.optInt("stream", AudioManager.STREAM_MUSIC);
        int volume = params.optInt("volume", -1);
        if (volume < 0) {
            return new JSONObject().put("success", false).put("error", "Missing 'volume'");
        }
        am.setStreamVolume(stream, volume, AudioManager.FLAG_SHOW_UI);
        return new JSONObject().put("success", true);
    }

    private JSONObject handleBrightnessGet() throws JSONException {
        try {
            int brightness = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS);
            int mode = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE);
            return new JSONObject()
                .put("success", true)
                .put("brightness", brightness)
                .put("auto", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } catch (Settings.SettingNotFoundException e) {
            return new JSONObject().put("success", false).put("error", e.getMessage());
        }
    }

    private JSONObject handleBrightnessSet(@NonNull JSONObject params) throws JSONException {
        int brightness = params.optInt("brightness", -1);
        if (brightness < 0 || brightness > 255) {
            return new JSONObject().put("success", false).put("error", "brightness must be 0-255");
        }
        try {
            Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, brightness);
            return new JSONObject().put("success", true);
        } catch (SecurityException e) {
            return new JSONObject().put("success", false)
                .put("error", "WRITE_SETTINGS permission denied: " + e.getMessage());
        }
    }

    private JSONObject handleOpenUrl(@NonNull JSONObject params) throws JSONException {
        String url = params.optString("url", "");
        if (url.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "Missing 'url'");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
        return new JSONObject().put("success", true);
    }

    private JSONObject handleAlarmSet(@NonNull JSONObject params) throws JSONException {
        int hour = params.optInt("hour", -1);
        int minutes = params.optInt("minutes", 0);
        String message = params.optString("message", "Hermes 提醒");
        if (hour < 0 || hour > 23 || minutes < 0 || minutes > 59) {
            return new JSONObject().put("success", false)
                .put("error", "Invalid time: hour=" + hour + ", minutes=" + minutes);
        }
        // MIUI 会清洗第三方 SET_ALARM intent 的 extras（时间/标签不可靠），
        // 改为 Hermes 自管闹钟：AlarmManager 精确唤醒 + 全屏通知，标签 100% 可靠。
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minutes);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);  // 今天已过点则排到明天
        }
        int id = (int) (cal.getTimeInMillis() / 60000);
        String error = AlarmReceiver.schedule(mContext, id, cal.getTimeInMillis(), message);
        if (error != null) {
            if ("need_exact_alarm_permission".equals(error)) {
                try {
                    mContext.startActivity(
                        new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    Log.w(LOG_TAG, "Failed to open exact-alarm settings", e);
                }
                return new JSONObject().put("success", false)
                    .put("error", "需要「闹钟和提醒」权限，已打开设置页，请授予后重试");
            }
            return new JSONObject().put("success", false).put("error", error);
        }
        String when = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            .format(cal.getTime());
        return new JSONObject().put("success", true)
            .put("trigger_at", when)
            .put("message", message)
            .put("note", "Hermes 自管闹钟（非系统时钟 App），到点全屏响铃通知");
    }

    private JSONObject handleCalendarAdd(@NonNull JSONObject params) throws JSONException {
        String title = params.optString("title", "");
        long startMs = params.optLong("start_ms", 0);
        long endMs = params.optLong("end_ms", 0);
        String description = params.optString("description", "");
        String location = params.optString("location", "");
        if (title.isEmpty() || startMs <= 0) {
            return new JSONObject().put("success", false)
                .put("error", "Missing 'title' or 'start_ms'");
        }
        if (endMs <= startMs) endMs = startMs + 3600_000L;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && mContext.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return new JSONObject().put("success", false)
                .put("error", "need_calendar_permission")
                .put("note", "请在 Hermes App 中授予日历权限后重试");
        }
        long calendarId = findWritableCalendarId();
        if (calendarId < 0) {
            return new JSONObject().put("success", false)
                .put("error", "no_calendar_account")
                .put("note", "设备上没有可写入的日历账户，请先在日历 App 中添加账户");
        }
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DTSTART, startMs);
        values.put(CalendarContract.Events.DTEND, endMs);
        values.put(CalendarContract.Events.DESCRIPTION, description);
        if (!location.isEmpty()) {
            values.put(CalendarContract.Events.EVENT_LOCATION, location);
        }
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        try {
            Uri uri = mContext.getContentResolver()
                .insert(CalendarContract.Events.CONTENT_URI, values);
            if (uri == null) {
                return new JSONObject().put("success", false).put("error", "insert failed");
            }
            return new JSONObject().put("success", true)
                .put("event_id", ContentUris.parseId(uri))
                .put("title", title);
        } catch (Exception e) {
            return new JSONObject().put("success", false)
                .put("error", "calendar insert failed: " + e.getMessage());
        }
    }

    private long findWritableCalendarId() {
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                new String[]{String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)},
                CalendarContract.Calendars.VISIBLE + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(LOG_TAG, "Query calendars failed", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return -1;
    }
}
