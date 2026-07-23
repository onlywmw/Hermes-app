package com.hermes.android.agent;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Vibrator;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ToolRegistry — agent 的工具注册表 (agent 与硬件控制的分割层)。
 *
 * AgentLoop 不认识任何具体工具: 拿动作名来查, 查到就执行。
 * - 工具 = 数据 (name/desc/handler), 加工具 = 注册一行, 循环零改动
 * - DevicePolicy = 唯一安全闸门: device.cmd 的风险分级(只读/动作/危险)
 *   + 硬件能力探测(无闪光灯/无马达/权限未批 → 对应能力一开始就不放行)
 * - 换手机 = 重新 probe 生成注册表, 可携带的 agent 核心(循环/文件/大脑)不变
 */
public class ToolRegistry {

    // ==================== 工具 ====================

    public interface Handler {
        Result run(JSONObject args) throws Exception;
    }

    /** 工具执行结果: ok/文本/产出文件路径(可空) */
    public static class Result {
        public final boolean ok;
        public final String text;
        public final String produced;

        public Result(boolean ok, String text) { this(ok, text, null); }
        public Result(boolean ok, String text, String produced) {
            this.ok = ok; this.text = text; this.produced = produced;
        }
    }

    public static class Tool {
        public final String name;
        public final String desc;
        public final Handler handler;

        public Tool(String name, String desc, Handler handler) {
            this.name = name; this.desc = desc; this.handler = handler;
        }
    }

    // ==================== 设备策略 (唯一安全闸门) ====================

    /**
     * device.cmd 分级:
     * - READONLY: 查询类, 永远放行
     * - ACTION:   可逆低风险动作 (经硬件探测可用才放行)
     * - 其余:     循环内一律拒绝 (电话/短信/联系人/截屏/触摸/全局文件 等)
     */
    public static class DevicePolicy {
        private static final Set<String> READONLY = new HashSet<>(Arrays.asList(
                "battery.status", "system.info", "network.info", "process.list",
                "volume.get", "brightness.get", "wifi.status", "clipboard.get"));
        private static final Set<String> ACTION = new HashSet<>(Arrays.asList(
                "torch.on", "torch.off", "volume.set", "brightness.set", "vibrate",
                "tts.speak", "notification.post", "app.launch", "clipboard.set", "location.get"));

        private final Set<String> unavailable;

        public DevicePolicy(Set<String> unavailable) {
            this.unavailable = unavailable;
        }

        /** 探测本机硬件与权限, 生成策略 */
        public static DevicePolicy probe(Context ctx) {
            Set<String> un = new HashSet<>();
            // 闪光灯
            boolean hasFlash = false;
            try {
                CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
                for (String id : cm.getCameraIdList()) {
                    Boolean b = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (b != null && b) { hasFlash = true; break; }
                }
            } catch (Exception ignored) {}
            if (!hasFlash) { un.add("torch.on"); un.add("torch.off"); }
            // 震动马达
            try {
                Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null || !v.hasVibrator()) un.add("vibrate");
            } catch (Exception e) { un.add("vibrate"); }
            // 写系统设置 (亮度)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(ctx)) {
                un.add("brightness.set");
            }
            // 定位权限
            if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                    && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                un.add("location.get");
            }
            return new DevicePolicy(un);
        }

        /** null = 放行; 否则拒绝原因 (回灌大脑) */
        public String check(String capability) {
            if (capability == null || capability.isEmpty()) return "无法解析指令";
            if (unavailable.contains(capability)) return "此设备不支持该能力: " + capability;
            if (READONLY.contains(capability) || ACTION.contains(capability)) return null;
            return "循环内禁止该能力: " + capability;
        }

        public String promptText() {
            return "device.cmd{text} 控制手机(自然语言)。只读: " + READONLY
                    + "; 动作: " + ACTION
                    + (unavailable.isEmpty() ? "" : "; 本机不可用: " + unavailable)
                    + "; 其余一律禁止。";
        }
    }

    // ==================== 注册表 ====================

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final DevicePolicy devicePolicy;

    private ToolRegistry(DevicePolicy policy) {
        this.devicePolicy = policy;
    }

    private void register(Tool t) {
        tools.put(t.name, t);
    }

    /** 查工具; null = 不存在或未注册 (循环据此拒绝) */
    public Tool find(String name) {
        return name == null ? null : tools.get(name);
    }

    /** 拼给大脑的工具说明段 */
    public String promptText() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("{\"action\":\"").append(t.name).append("\"")
                    .append(t.name.startsWith("file.")
                            ? ",\"path\":\"f\"" + ("file.write".equals(t.name) ? ",\"content\":\"完整内容\"" : "")
                            : "")
                    .append("} — ").append(t.desc).append("\n");
        }
        sb.append(devicePolicy.promptText());
        return sb.toString();
    }

    /**
     * 组装本机注册表。
     * @param tools        生产实现 (StorageManager/IntentParser/CapabilityExecutor 的封装)
     * @param roomId       当前房间 (file.* 的作用域)
     * @param allowedPaths 计划内可写路径的活引用 (revise_plan 后自动生效)
     * @param policy       DevicePolicy.probe(ctx) 的结果
     */
    public static ToolRegistry build(AgentLoop.Tools tools, String roomId,
                                     Supplier<Set<String>> allowedPaths, DevicePolicy policy) {
        ToolRegistry r = new ToolRegistry(policy);
        r.register(new Tool("file.list", "列房间文件",
                a -> new Result(true, tools.fileList(roomId))));
        r.register(new Tool("file.read", "读房间文件 (结果截断2k)", a -> {
            String res = tools.fileRead(roomId, a.optString("path"));
            if (res.startsWith("ERROR:")) return new Result(false, res);
            if (res.length() > 2000) res = res.substring(0, 2000) + "\n…(截断)";
            return new Result(true, res);
        }));
        r.register(new Tool("file.write", "写房间文件 (仅限已批准计划内路径)", a -> {
            String path = a.optString("path");
            if (!allowedPaths.get().contains(path)) {
                return new Result(false, "此文件不在批准计划内");
            }
            String res = tools.fileWrite(roomId, path, a.optString("content"));
            boolean ok = res.startsWith("OK:");
            return new Result(ok, ok ? path + " → " + a.optString("content").length() + " 字符已写入" : res,
                    ok ? path : null);
        }));
        r.register(new Tool("device.cmd", "控制手机(自然语言, 分级放行)", a -> {
            String cap = tools.capabilityOf(a.optString("text"));
            String deny = policy.check(cap);
            if (deny != null) return new Result(false, deny);
            return new Result(true, tools.deviceCmd(a.optString("text")));
        }));
        return r;
    }

    /** 测试用: 不探测硬件, 手工指定不可用集 */
    public static DevicePolicy policyForTest(Set<String> unavailable) {
        return new DevicePolicy(unavailable);
    }
}
