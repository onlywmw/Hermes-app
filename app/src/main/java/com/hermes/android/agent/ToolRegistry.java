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
            sb.append("{\"action\":\"").append(t.name).append("\"");
            if (t.name.startsWith("file.")) {
                sb.append(",\"path\":\"f\"");
                if ("file.write".equals(t.name)) sb.append(",\"content\":\"完整内容\"");
                if ("file.read".equals(t.name)) sb.append(",\"offset\":0,\"length\":32768");
            } else if ("app.package".equals(t.name)) {
                sb.append(",\"path\":\"game.html\",\"appName\":\"可选\"");
            }
            sb.append("} — ").append(t.desc).append("\n");
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
        r.register(new Tool("file.read", "读房间文件 (分页: offset=起始字符默认0, length=本次长度默认32k, 单次上限32k;"
                + " 结果尾部写『用 offset=N 继续读』就再调一次传 offset=N, 写『文件读完』则禁止再读)", a -> {
            String res = tools.fileRead(roomId, a.optString("path"));
            if (res.startsWith("ERROR:")) return new Result(false, res);
            /* 底层 StorageManager 100k 截断标记: 分页天花板 */
            boolean baseCapped = res.endsWith("\n…(截断)");
            if (baseCapped) res = res.substring(0, res.length() - "\n…(截断)".length());
            int total = res.length();
            int offset = Math.max(0, a.optInt("offset", 0));
            int length = a.optInt("length", 32768);
            if (length <= 0 || length > 32768) length = 32768;
            if (offset >= total) {
                return new Result(false, "offset 越界: 文件共 " + total + " 字符, 全部内容已在你的工作日志里,"
                        + "禁止再读, 直接用已读内容完成修改" + (baseCapped ? " (文件超底层 100k 上限)" : ""));
            }
            int end = Math.min(total, offset + length);
            String out = res.substring(offset, end);
            /* 显式页脚: 大脑需要"读完了"的确定信号, 否则会对 32k 上限产生幻觉得出"文件被截断" */
            if (end < total) out += "\n…(已读 " + offset + "-" + end + "/" + total + " 字符, 用 offset=" + end + " 继续读)";
            else if (baseCapped) out += "\n…(已达底层 100k 上限, 文件实际更大)";
            else out += "\n—(文件读完, 共 " + total + " 字符)";
            return new Result(true, out);
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
        r.register(new Tool("app.package", "把房间里的 HTML 打包成签名 APK (path=html文件, appName 可选)", a -> {
            String path = a.optString("path");
            if (!path.toLowerCase().endsWith(".html") && !path.toLowerCase().endsWith(".htm")) {
                return new Result(false, "app.package 只支持 HTML 文件");
            }
            String res = tools.packageApk(roomId, path, a.optString("appName", ""));
            if (!res.startsWith("OK:")) return new Result(false, res);
            String produced = res.substring(4, res.indexOf(" · "));
            return new Result(true, res.substring(4), produced);
        }));
        return r;
    }

    /** 测试用: 不探测硬件, 手工指定不可用集 */
    public static DevicePolicy policyForTest(Set<String> unavailable) {
        return new DevicePolicy(unavailable);
    }
}
