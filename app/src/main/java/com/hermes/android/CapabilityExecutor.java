package com.hermes.android;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Capability executor - directly calls Android APIs.
 * No Termux dependency. All capabilities run in-process.
 */
public class CapabilityExecutor {

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean torchOn = false;

    /* 运行统计: 进程级命令计数 (RUNTIME 页真数据) */
    private static final java.util.concurrent.atomic.AtomicLong CMD_COUNT =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static volatile long lastCmdMs = 0;
    private static volatile String lastCmdName = "";

    public static long getCmdCount() { return CMD_COUNT.get(); }
    public static long getLastCmdMs() { return lastCmdMs; }
    public static String getLastCmdName() { return lastCmdName; }

    /** P0-3: 释放 TTS 资源, 防止实例泄漏 */
    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ttsReady = false;
        }
    }

    public CommandResult execute(Context ctx, ParsedCommand cmd) {
        if (cmd.isError()) {
            return CommandResult.fail(cmd.getError());
        }

        long t0 = System.currentTimeMillis();
        CommandResult result = doExecute(ctx, cmd);
        lastCmdMs = System.currentTimeMillis() - t0;
        lastCmdName = cmd.getCapability();
        CMD_COUNT.incrementAndGet();
        return result;
    }

    private CommandResult doExecute(Context ctx, ParsedCommand cmd) {
        switch (cmd.getCapability()) {
            case "help":          return doHelp();
            case "torch.on":      return doTorch(ctx, true);
            case "torch.off":     return doTorch(ctx, false);
            case "battery.status":return doBattery(ctx);
            case "system.info":   return doSystemInfo();
            case "brightness.get":return doBrightnessGet(ctx);
            case "brightness.set":return doBrightnessSet(ctx, cmd.getIntArg("value", 128));
            case "volume.get":    return doVolumeGet(ctx);
            case "volume.set":    return doVolumeSet(ctx, cmd.getIntArg("value", 7));
            case "wifi.status":   return doWifiStatus(ctx);
            case "wifi.toggle":   return doWifiToggle(ctx, cmd.getStringArg("state", "enable"));
            case "vibrate":       return doVibrate(ctx, cmd.getIntArg("duration", 500));
            case "tts.speak":     return doTts(ctx, cmd.getStringArg("text", ""));
            case "clipboard.get": return doClipboardGet(ctx);
            case "clipboard.set": return doClipboardSet(ctx, cmd.getStringArg("text", ""));
            case "toast":         return doToast(ctx, cmd.getStringArg("message", "MOV"));
            case "notification.post": return doNotification(ctx, cmd.getStringArg("content", ""));
            case "location.get":  return doLocation(ctx);
            case "camera.photo":  return CommandResult.fail("拍照功能开发中，敬请期待");
            case "sms.recent":    return doSmsRecent(ctx, cmd.getIntArg("limit", 5));
            case "contacts.list": return doContacts(ctx);
            case "telephony.call":return doCall(ctx, cmd.getStringArg("number", ""));
            case "screen.capture":return doScreenCapture(ctx);
            case "app.list":      return doAppList(ctx);
            case "app.launch":    return doAppLaunch(ctx, cmd.getStringArg("package", ""));
            case "input.tap":     return doInputTap(cmd.getIntArg("x", 0), cmd.getIntArg("y", 0));
            case "input.swipe":   return doInputSwipe(cmd);
            case "network.info":  return doNetworkInfo();
            case "process.list":  return doProcessList();
            case "file.ls":       return doFileLs(cmd.getStringArg("path", "/sdcard/"));
            case "file.write":    return doFileWrite(cmd);
            case "file.read":     return doFileRead(cmd);
            case "file.delete":   return doFileDelete(cmd);
            case "file.mkdir":    return doFileMkdir(cmd);
            default:
                return CommandResult.fail("未知能力: " + cmd.getCapability());
        }
    }

    // ==================== HELP ====================
    private CommandResult doHelp() {
        return CommandResult.ok(
            "MOV v3.0 能力列表：\n\n" +
            "🔦 手电筒：打开手电筒 / 关闭手电筒\n" +
            "🔋 电池：电量多少\n" +
            "📱 系统：设备信息\n" +
            "🔆 亮度：亮度调到 128 / 当前亮度\n" +
            "🔊 音量：音量调到 10 / 当前音量\n" +
            "📶 WiFi：WiFi状态 / 打开wifi / 关闭wifi\n" +
            "📳 震动：震动 / 震动 1000\n" +
            "🗣️ 语音：朗读 你好世界\n" +
            "📋 剪贴板：读取剪贴板 / 复制到剪贴板 xxx\n" +
            "💬 提示：toast 你好\n" +
            "🔔 通知：发通知 内容\n" +
            "📍 定位：我在哪 / 定位\n" +
            "📷 拍照：拍照\n" +
            "📩 短信：最近短信\n" +
            "👤 联系人：联系人\n" +
            "📞 电话：打电话 13800138000\n" +
            "📸 截屏：截屏\n" +
            "📱 应用：应用列表 / 打开应用 com.xxx\n" +
            "👆 触摸：点击 500,800 / 滑动 500 1500 500 500\n" +
            "🌐 网络：ip地址\n" +
            "⚙️ 进程：进程\n" +
            "📁 文件：文件列表 / 看看文件 /sdcard/\n" +
            "📝 房间文件：写文件 / 读文件 / 删文件 / 创建目录 (在文件 tab 操作)"
        );
    }

    // ==================== TORCH ====================
    @SuppressLint("MissingPermission")
    private CommandResult doTorch(Context ctx, boolean on) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String torchId = null;
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    torchId = id;
                    break;
                }
            }
            if (torchId == null) {
                return CommandResult.fail("此设备没有闪光灯");
            }
            cm.setTorchMode(torchId, on);
            torchOn = on;
            return CommandResult.ok(on ? "手电筒已打开 🔦" : "手电筒已关闭");
        } catch (Exception e) {
            return CommandResult.fail("手电筒操作失败: " + e.getMessage());
        }
    }

    // ==================== BATTERY ====================
    private CommandResult doBattery(Context ctx) {
        try {
            Intent batteryIntent = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) return CommandResult.fail("无法获取电池信息");

            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int pct = scale > 0 ? (level * 100 / scale) : -1;

            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            String statusStr;
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING: statusStr = "充电中 ⚡"; break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING: statusStr = "放电中"; break;
                case BatteryManager.BATTERY_STATUS_FULL: statusStr = "已充满 ✅"; break;
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING: statusStr = "未充电"; break;
                default: statusStr = "未知";
            }

            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            String plugStr;
            switch (plugged) {
                case BatteryManager.BATTERY_PLUGGED_AC: plugStr = "AC充电"; break;
                case BatteryManager.BATTERY_PLUGGED_USB: plugStr = "USB"; break;
                case BatteryManager.BATTERY_PLUGGED_WIRELESS: plugStr = "无线充电"; break;
                default: plugStr = "未连接";
            }

            double temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            String tech = batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);

            int health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            String healthStr;
            switch (health) {
                case BatteryManager.BATTERY_HEALTH_GOOD: healthStr = "良好"; break;
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: healthStr = "过热 ⚠️"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: healthStr = "损坏"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: healthStr = "过冷"; break;
                default: healthStr = "未知";
            }

            return CommandResult.ok(String.format(Locale.getDefault(),
                "🔋 电量: %d%%\n状态: %s\n电源: %s\n温度: %.1f°C\n电压: %dmV\n电池: %s\n健康: %s",
                pct, statusStr, plugStr, temp, voltage, tech != null ? tech : "未知", healthStr));
        } catch (Exception e) {
            return CommandResult.fail("电池信息获取失败: " + e.getMessage());
        }
    }

    // ==================== SYSTEM INFO ====================
    private CommandResult doSystemInfo() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("📱 设备信息\n\n");
            sb.append("型号: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("安全补丁: ").append(Build.VERSION.SECURITY_PATCH).append("\n");
            sb.append("主板: ").append(Build.BOARD).append("\n");
            sb.append("硬件: ").append(Build.HARDWARE).append("\n");
            sb.append("CPU ABI: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n");

            // Memory
            Runtime rt = Runtime.getRuntime();
            long maxMem = rt.maxMemory() / 1024 / 1024;
            long totalMem = rt.totalMemory() / 1024 / 1024;
            long freeMem = rt.freeMemory() / 1024 / 1024;
            sb.append("\nJVM 内存: ").append(totalMem).append("MB / ").append(maxMem).append("MB (空闲 ").append(freeMem).append("MB)\n");

            // System memory via /proc/meminfo
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(
                    Runtime.getRuntime().exec("cat /proc/meminfo").getInputStream()));
                String line;
                int count = 0;
                sb.append("\n系统内存:\n");
                while ((line = br.readLine()) != null && count < 3) {
                    sb.append("  ").append(line).append("\n");
                    count++;
                }
                br.close();
            } catch (Exception ignored) {}

            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("系统信息获取失败: " + e.getMessage());
        }
    }

    // ==================== BRIGHTNESS ====================
    private CommandResult doBrightnessGet(Context ctx) {
        try {
            int brightness = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int mode = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE);
            String modeStr = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC ? "自动" : "手动";
            return CommandResult.ok("🔆 当前亮度: " + brightness + "/255 (" + modeStr + "模式)");
        } catch (Exception e) {
            return CommandResult.fail("亮度查询失败: " + e.getMessage());
        }
    }

    private CommandResult doBrightnessSet(Context ctx, int value) {
        try {
            if (!Settings.System.canWrite(ctx)) {
                // 引导用户跳转系统设置授权
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        android.net.Uri.parse("package:" + ctx.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ctx.startActivity(intent));
                return CommandResult.fail("需要「修改系统设置」权限，已打开设置页面，请授权后重试");
            }
            value = Math.max(0, Math.min(255, value));
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
            return CommandResult.ok("🔆 亮度已设置为 " + value + "/255");
        } catch (Exception e) {
            return CommandResult.fail("亮度设置失败: " + e.getMessage());
        }
    }

    // ==================== VOLUME ====================
    private CommandResult doVolumeGet(Context ctx) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            StringBuilder sb = new StringBuilder("🔊 音量信息\n\n");
            appendVolume(sb, am, AudioManager.STREAM_MUSIC, "媒体");
            appendVolume(sb, am, AudioManager.STREAM_RING, "铃声");
            appendVolume(sb, am, AudioManager.STREAM_NOTIFICATION, "通知");
            appendVolume(sb, am, AudioManager.STREAM_ALARM, "闹钟");
            appendVolume(sb, am, AudioManager.STREAM_VOICE_CALL, "通话");
            appendVolume(sb, am, AudioManager.STREAM_SYSTEM, "系统");
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("音量查询失败: " + e.getMessage());
        }
    }

    private void appendVolume(StringBuilder sb, AudioManager am, int stream, String name) {
        int vol = am.getStreamVolume(stream);
        int max = am.getStreamMaxVolume(stream);
        sb.append(name).append(": ").append(vol).append("/").append(max).append("\n");
    }

    private CommandResult doVolumeSet(Context ctx, int value) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            value = Math.max(0, Math.min(max, value));
            am.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
            return CommandResult.ok("🔊 媒体音量已设置为 " + value + "/" + max);
        } catch (Exception e) {
            return CommandResult.fail("音量设置失败: " + e.getMessage());
        }
    }

    // ==================== WIFI ====================
    @SuppressLint("MissingPermission")
    private CommandResult doWifiStatus(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wm.isWifiEnabled()) {
                return CommandResult.ok("📶 WiFi 已关闭");
            }
            WifiInfo info = wm.getConnectionInfo();
            StringBuilder sb = new StringBuilder("📶 WiFi 状态\n\n");
            sb.append("SSID: ").append(info.getSSID().replace("\"", "")).append("\n");
            sb.append("BSSID: ").append(info.getBSSID()).append("\n");
            sb.append("IP: ").append(intToIp(info.getIpAddress())).append("\n");
            sb.append("信号: ").append(WifiManager.calculateSignalLevel(info.getRssi(), 5)).append("/4\n");
            sb.append("速度: ").append(info.getLinkSpeed()).append(" Mbps\n");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sb.append("频段: ").append(info.getFrequency()).append(" MHz\n");
            }
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("WiFi 信息获取失败: " + e.getMessage());
        }
    }

    private CommandResult doWifiToggle(Context ctx, String state) {
        return CommandResult.fail("Android 10+ 禁止应用直接开关 WiFi\n请从下拉通知栏手动操作");
    }

    private String intToIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    // ==================== VIBRATE ====================
    private CommandResult doVibrate(Context ctx, int duration) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) {
                return CommandResult.fail("此设备没有震动马达");
            }
            duration = Math.max(100, Math.min(5000, duration));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(duration);
            }
            return CommandResult.ok("📳 震动 " + duration + "ms");
        } catch (Exception e) {
            return CommandResult.fail("震动失败: " + e.getMessage());
        }
    }

    // ==================== TTS ====================
    private CommandResult doTts(Context ctx, String text) {
        if (text.isEmpty()) return CommandResult.fail("需要朗读内容，如: 朗读 你好世界");
        try {
            if (tts == null) {
                tts = new TextToSpeech(ctx, status -> {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                });
            }
            if (!ttsReady) {
                return CommandResult.fail("语音引擎初始化中，请稍后重试");
            }
            tts.setLanguage(Locale.CHINESE);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes_tts");
            return CommandResult.ok("🗣️ 正在朗读: " + text);
        } catch (Exception e) {
            return CommandResult.fail("语音朗读失败: " + e.getMessage());
        }
    }

    // ==================== CLIPBOARD ====================
    private CommandResult doClipboardGet(Context ctx) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
                return CommandResult.ok("📋 剪贴板内容:\n" + (text != null ? text.toString() : "(空)"));
            }
            return CommandResult.ok("📋 剪贴板为空");
        } catch (Exception e) {
            return CommandResult.fail("剪贴板读取失败: " + e.getMessage());
        }
    }

    private CommandResult doClipboardSet(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("MOV", text));
            return CommandResult.ok("📋 已复制到剪贴板: " + text);
        } catch (Exception e) {
            return CommandResult.fail("剪贴板写入失败: " + e.getMessage());
        }
    }

    // ==================== TOAST ====================
    private CommandResult doToast(Context ctx, String message) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show());
            return CommandResult.ok("💬 Toast: " + message);
        } catch (Exception e) {
            return CommandResult.fail("Toast 失败: " + e.getMessage());
        }
    }

    // ==================== NOTIFICATION ====================
    private CommandResult doNotification(Context ctx, String content) {
        try {
            if (content.isEmpty()) return CommandResult.fail("需要通知内容");
            String channelId = "hermes_default";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId, "MOV 通知", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.createNotificationChannel(channel);
                android.app.Notification notification = new android.app.Notification.Builder(ctx, channelId)
                    .setContentTitle("MOV")
                    .setContentText(content)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .build();
                nm.notify((int) (System.currentTimeMillis() % Integer.MAX_VALUE), notification);
            }
            return CommandResult.ok("🔔 通知已发送: " + content);
        } catch (Exception e) {
            return CommandResult.fail("通知发送失败: " + e.getMessage());
        }
    }

    // ==================== LOCATION ====================
    @SuppressLint("MissingPermission")
    private CommandResult doLocation(Context ctx) {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location loc = lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
            if (loc == null) {
                loc = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
            }
            if (loc == null) {
                return CommandResult.fail("无法获取位置。请确保 GPS 已开启且有定位权限。");
            }
            return CommandResult.ok(String.format(Locale.getDefault(),
                "📍 位置信息\n\n纬度: %.6f\n经度: %.6f\n精度: %.1fm\n时间: %s",
                loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(),
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date(loc.getTime()))));
        } catch (SecurityException e) {
            return CommandResult.fail("定位需要位置权限，请在弹窗中允许");
        } catch (Exception e) {
            return CommandResult.fail("定位失败: " + e.getMessage());
        }
    }

    // ==================== SMS ====================
    @SuppressLint("Range")
    private CommandResult doSmsRecent(Context ctx, int limit) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.net.Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                null, null, "date DESC LIMIT " + Math.min(limit, 20));
            if (cursor == null) return CommandResult.fail("无法访问短信");

            StringBuilder sb = new StringBuilder("📩 最近 " + limit + " 条短信\n\n");
            int count = 0;
            while (cursor.moveToNext() && count < limit) {
                String addr = cursor.getString(cursor.getColumnIndex("address"));
                String body = cursor.getString(cursor.getColumnIndex("body"));
                long date = cursor.getLong(cursor.getColumnIndex("date"));
                String dateStr = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(date));
                sb.append("[").append(dateStr).append("] ").append(addr).append("\n");
                sb.append(body != null && body.length() > 100 ? body.substring(0, 100) + "…" : body).append("\n\n");
                count++;
            }
            cursor.close();
            if (count == 0) sb.append("(无短信)");
            return CommandResult.ok(sb.toString().trim());
        } catch (SecurityException e) {
            return CommandResult.fail("短信需要读取权限，请在弹窗中允许");
        } catch (Exception e) {
            return CommandResult.fail("短信读取失败: " + e.getMessage());
        }
    }

    // ==================== CONTACTS ====================
    @SuppressLint("Range")
    private CommandResult doContacts(Context ctx) {
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT 20");
            if (cursor == null) return CommandResult.fail("无法访问联系人");

            StringBuilder sb = new StringBuilder("👤 联系人 (前20条)\n\n");
            int count = 0;
            while (cursor.moveToNext() && count < 20) {
                String name = cursor.getString(cursor.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String number = cursor.getString(cursor.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER));
                sb.append(name).append(": ").append(number).append("\n");
                count++;
            }
            cursor.close();
            if (count == 0) sb.append("(无联系人)");
            return CommandResult.ok(sb.toString().trim());
        } catch (SecurityException e) {
            return CommandResult.fail("联系人需要读取权限，请在弹窗中允许");
        } catch (Exception e) {
            return CommandResult.fail("联系人读取失败: " + e.getMessage());
        }
    }

    // ==================== CALL ====================
    @SuppressLint("MissingPermission")
    private CommandResult doCall(Context ctx, String number) {
        if (number.isEmpty()) return CommandResult.fail("需要电话号码");
        try {
            Intent intent = new Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:" + number));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ctx.startActivity(intent));
            return CommandResult.ok("📞 正在拨打: " + number);
        } catch (SecurityException e) {
            return CommandResult.fail("拨打电话需要电话权限");
        } catch (Exception e) {
            return CommandResult.fail("拨号失败: " + e.getMessage());
        }
    }

    // ==================== SCREEN CAPTURE ====================
    private CommandResult doScreenCapture(Context ctx) {
        try {
            String path = "/sdcard/mov_screenshot_" + System.currentTimeMillis() + ".png";
            Process proc = Runtime.getRuntime().exec(new String[]{"screencap", "-p", path});
            proc.waitFor();
            return CommandResult.ok("📸 截屏已保存: " + path);
        } catch (Exception e) {
            return CommandResult.fail("截屏失败: " + e.getMessage() + "\n(需要 ADB 权限或 root)");
        }
    }

    // ==================== APP LIST ====================
    private CommandResult doAppList(Context ctx) {
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            java.util.List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
            StringBuilder sb = new StringBuilder("📱 已安装应用 (" + packages.size() + "个)\n\n");
            int count = 0;
            for (android.content.pm.PackageInfo pi : packages) {
                android.content.pm.ApplicationInfo ai = pi.applicationInfo;
                if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    sb.append(pm.getApplicationLabel(ai)).append("\n  ").append(pi.packageName).append("\n");
                    count++;
                    if (count >= 30) {
                        sb.append("... 还有 ").append(packages.size() - 30).append(" 个应用\n");
                        break;
                    }
                }
            }
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("应用列表获取失败: " + e.getMessage());
        }
    }

    // ==================== APP LAUNCH ====================
    private CommandResult doAppLaunch(Context ctx, String pkg) {
        if (pkg.isEmpty()) return CommandResult.fail("需要包名");
        try {
            Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return CommandResult.fail("未找到应用: " + pkg);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> ctx.startActivity(intent));
            return CommandResult.ok("📱 已启动: " + pkg);
        } catch (Exception e) {
            return CommandResult.fail("启动失败: " + e.getMessage());
        }
    }

    // ==================== INPUT TAP ====================
    private CommandResult doInputTap(int x, int y) {
        try {
            Runtime.getRuntime().exec(new String[]{"input", "tap", String.valueOf(x), String.valueOf(y)});
            return CommandResult.ok("👆 已点击 (" + x + ", " + y + ")");
        } catch (Exception e) {
            return CommandResult.fail("点击失败: " + e.getMessage());
        }
    }

    // ==================== INPUT SWIPE ====================
    private CommandResult doInputSwipe(ParsedCommand cmd) {
        try {
            int x1 = cmd.getIntArg("x1", 0), y1 = cmd.getIntArg("y1", 0);
            int x2 = cmd.getIntArg("x2", 0), y2 = cmd.getIntArg("y2", 0);
            Runtime.getRuntime().exec(new String[]{"input", "swipe",
                String.valueOf(x1), String.valueOf(y1), String.valueOf(x2), String.valueOf(y2), "300"});
            return CommandResult.ok("👆 已滑动 (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ")");
        } catch (Exception e) {
            return CommandResult.fail("滑动失败: " + e.getMessage());
        }
    }

    // ==================== NETWORK INFO ====================
    private CommandResult doNetworkInfo() {
        try {
            StringBuilder sb = new StringBuilder("🌐 网络接口\n\n");
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                sb.append(ni.getDisplayName()).append(" (").append(ni.getName()).append(")\n");
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress()) {
                        sb.append("  ").append(addr.getHostAddress()).append("\n");
                    }
                }
            }
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("网络信息获取失败: " + e.getMessage());
        }
    }

    // ==================== PROCESS LIST ====================
    private CommandResult doProcessList() {
        try {
            Process proc = Runtime.getRuntime().exec("ps -ef");
            BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb = new StringBuilder("⚙️ 运行中的进程\n\n");
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 25) {
                sb.append(line).append("\n");
                count++;
            }
            br.close();
            if (count >= 25) sb.append("... (仅显示前25条)");
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("进程列表获取失败: " + e.getMessage());
        }
    }

    // ==================== FILE LS ====================
    private CommandResult doFileLs(String path) {
        try {
            java.io.File dir = new java.io.File(path);
            if (!dir.exists()) return CommandResult.fail("路径不存在: " + path);
            if (!dir.isDirectory()) return CommandResult.ok("📁 " + path + " (文件, " + dir.length() + " bytes)");

            java.io.File[] files = dir.listFiles();
            StringBuilder sb = new StringBuilder("📁 " + path + "\n\n");
            if (files == null || files.length == 0) {
                sb.append("(空目录)");
            } else {
                java.util.Arrays.sort(files, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                int count = 0;
                for (java.io.File f : files) {
                    if (count >= 50) {
                        sb.append("... 还有 ").append(files.length - 50).append(" 个项目\n");
                        break;
                    }
                    String icon = f.isDirectory() ? "📁" : "📄";
                    String size = f.isDirectory() ? "" : " (" + formatSize(f.length()) + ")";
                    sb.append(icon).append(" ").append(f.getName()).append(size).append("\n");
                    count++;
                }
            }
            return CommandResult.ok(sb.toString().trim());
        } catch (Exception e) {
            return CommandResult.fail("文件列表失败: " + e.getMessage());
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.getDefault(), "%.1fMB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.getDefault(), "%.1fGB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    // ==================== ROOM FILE OPS ====================
    private static final String ROOMS_BASE = "/sdcard/mov/rooms/";

    /** 路径逃逸检查: target 必须在 base 内 */
    private boolean isPathSafe(java.io.File base, java.io.File target) {
        try {
            return target.getCanonicalPath().startsWith(base.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private CommandResult doFileWrite(ParsedCommand cmd) {
        String roomId = cmd.getStringArg("roomId", "");
        String path = cmd.getStringArg("path", "");
        String content = cmd.getStringArg("content", "");
        if (roomId.isEmpty() || path.isEmpty()) return CommandResult.fail("需要 roomId 和 path");
        try {
            java.io.File base = new java.io.File(ROOMS_BASE + roomId);
            java.io.File target = new java.io.File(base, path);
            if (!isPathSafe(base, target)) return CommandResult.fail("路径越界");
            target.getParentFile().mkdirs();
            try (java.io.FileWriter fw = new java.io.FileWriter(target)) {
                fw.write(content);
            }
            return CommandResult.ok("已写入: " + path + " (" + target.length() + " bytes)");
        } catch (Exception e) {
            return CommandResult.fail("写入失败: " + e.getMessage());
        }
    }

    private CommandResult doFileRead(ParsedCommand cmd) {
        String roomId = cmd.getStringArg("roomId", "");
        String path = cmd.getStringArg("path", "");
        if (roomId.isEmpty() || path.isEmpty()) return CommandResult.fail("需要 roomId 和 path");
        try {
            java.io.File base = new java.io.File(ROOMS_BASE + roomId);
            java.io.File target = new java.io.File(base, path);
            if (!isPathSafe(base, target)) return CommandResult.fail("路径越界");
            if (!target.exists()) return CommandResult.fail("文件不存在: " + path);
            if (target.length() > 100 * 1024) return CommandResult.fail("文件过大 (>100KB)");
            String content = new String(java.nio.file.Files.readAllBytes(target.toPath()));
            return CommandResult.ok(content);
        } catch (Exception e) {
            return CommandResult.fail("读取失败: " + e.getMessage());
        }
    }

    private CommandResult doFileDelete(ParsedCommand cmd) {
        String roomId = cmd.getStringArg("roomId", "");
        String path = cmd.getStringArg("path", "");
        if (roomId.isEmpty() || path.isEmpty()) return CommandResult.fail("需要 roomId 和 path");
        try {
            java.io.File base = new java.io.File(ROOMS_BASE + roomId);
            java.io.File target = new java.io.File(base, path);
            if (!isPathSafe(base, target)) return CommandResult.fail("路径越界");
            if (!target.exists()) return CommandResult.fail("文件不存在");
            if (target.isDirectory()) return CommandResult.fail("不可删除目录");
            target.delete();
            return CommandResult.ok("已删除: " + path);
        } catch (Exception e) {
            return CommandResult.fail("删除失败: " + e.getMessage());
        }
    }

    private CommandResult doFileMkdir(ParsedCommand cmd) {
        String roomId = cmd.getStringArg("roomId", "");
        String path = cmd.getStringArg("path", "");
        if (roomId.isEmpty() || path.isEmpty()) return CommandResult.fail("需要 roomId 和 path");
        try {
            java.io.File base = new java.io.File(ROOMS_BASE + roomId);
            java.io.File target = new java.io.File(base, path);
            if (!isPathSafe(base, target)) return CommandResult.fail("路径越界");
            target.mkdirs();
            return CommandResult.ok("已创建目录: " + path);
        } catch (Exception e) {
            return CommandResult.fail("创建目录失败: " + e.getMessage());
        }
    }
}
