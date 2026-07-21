package com.hermes.android.bridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.hermes.android.CapabilityExecutor;
import com.hermes.android.CommandResult;
import com.hermes.android.HermesActivity;
import com.hermes.android.IntentParser;
import com.hermes.android.ParsedCommand;

import org.json.JSONObject;

/**
 * P1-1: 设备能力 Bridge — 指令执行 + 设备信息 + 权限 + 小组件
 */
public class BridgeDevice extends BaseBridge {

    private final IntentParser parser = new IntentParser();
    private volatile String deviceInfoCache;
    private volatile long deviceInfoCacheTime;
    private static final long DEVICE_CACHE_TTL = 5000;

    public BridgeDevice(HermesActivity activity) {
        super(activity);
    }

    public String parseIntent(String text) {
        try {
            ParsedCommand cmd = parser.parse(text);
            if (cmd != null && !cmd.isError()) {
                return new JSONObject().put("cmd", cmd.getCapability()).toString();
            }
        } catch (Exception ignored) {}
        return "{}";
    }

    public String execCommand(String text) {
        long t0 = System.currentTimeMillis();
        try {
            ParsedCommand cmd = parser.parse(text);
            if (cmd == null) return "无法识别指令: " + text;
            if (cmd.isError()) return cmd.getError();
            CommandResult result = activity.getCapabilityExecutor().execute(activity, cmd);
            android.util.Log.i("MOV", "cmd [" + text + "] " + (result.isSuccess() ? "OK" : "FAIL")
                    + " in " + (System.currentTimeMillis() - t0) + "ms");
            return (result.isSuccess() ? "" : "") + result.getMessage();
        } catch (SecurityException e) {
            return "权限不足: " + e.getMessage();
        } catch (Exception e) {
            return "执行异常: " + e.getMessage();
        }
    }

    public String getDeviceInfo() {
        long now = System.currentTimeMillis();
        if (deviceInfoCache != null && (now - deviceInfoCacheTime) < DEVICE_CACHE_TTL) {
            return deviceInfoCache;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("pid", android.os.Process.myPid());
            try {
                Intent batt = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (batt != null) {
                    int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (scale > 0) o.put("batteryLevel", level * 100 / scale);
                    int st = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    o.put("batteryCharging",
                            st == BatteryManager.BATTERY_STATUS_CHARGING
                                    || st == BatteryManager.BATTERY_STATUS_FULL);
                }
            } catch (Exception ignored) {}
            try {
                WifiManager wm = (WifiManager) activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                o.put("wifiEnabled", wm.isWifiEnabled());
                WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    o.put("wifiSsid", info.getSSID().replace("\"", ""));
                }
            } catch (Exception ignored) {}
            try {
                o.put("brightness", Settings.System.getInt(
                        activity.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
            } catch (Exception ignored) {}
            String result = o.toString();
            deviceInfoCache = result;
            deviceInfoCacheTime = now;
            return result;
        } catch (Exception e) {
            return "{}";
        }
    }

    public String getRuntimeStats() {
        try {
            Runtime rt = Runtime.getRuntime();
            JSONObject o = new JSONObject();
            o.put("pid", android.os.Process.myPid());
            o.put("uptimeMs", android.os.SystemClock.elapsedRealtime()
                    - android.os.Process.getStartElapsedRealtime());
            o.put("memUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
            o.put("memMaxMb", rt.maxMemory() / 1024 / 1024);
            o.put("cmdCount", CapabilityExecutor.getCmdCount());
            o.put("lastCmdMs", CapabilityExecutor.getLastCmdMs());
            o.put("lastCmdName", CapabilityExecutor.getLastCmdName());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String getPermissionState() {
        try {
            JSONObject o = new JSONObject();
            String[][] perms = {
                {"CAMERA", Manifest.permission.CAMERA},
                {"LOCATION", Manifest.permission.ACCESS_FINE_LOCATION},
                {"CONTACTS", Manifest.permission.READ_CONTACTS},
                {"SMS", Manifest.permission.READ_SMS},
                {"CALL", Manifest.permission.CALL_PHONE},
                {"NOTIFY", Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.POST_NOTIFICATIONS : null},
            };
            for (String[] p : perms) {
                if (p[1] == null) { o.put(p[0], true); continue; }
                o.put(p[0], ContextCompat.checkSelfPermission(
                        activity, p[1]) == PackageManager.PERMISSION_GRANTED);
            }
            o.put("SETTINGS", Settings.System.canWrite(activity));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String getWidgetInfo() {
        try {
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(activity);
            int[] ids = mgr.getAppWidgetIds(new android.content.ComponentName(
                    activity, com.hermes.android.widget.HermesWidgetProvider.class));
            return new JSONObject().put("count", ids != null ? ids.length : 0).toString();
        } catch (Exception e) {
            return "{\"count\":0}";
        }
    }

    public void openAppSettings() {
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开设置页", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
