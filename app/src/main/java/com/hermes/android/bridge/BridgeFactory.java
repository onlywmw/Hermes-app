package com.hermes.android.bridge;

import android.webkit.JavascriptInterface;

import com.hermes.android.HermesActivity;

/**
 * Bridge 聚合工厂 — 唯一注入 WebView 的对象。
 * 所有 @JavascriptInterface 方法委托给子 Bridge。
 * JS 侧接口签名不变 (window.HermesBridge.xxx)。
 */
public class BridgeFactory {

    private final HermesActivity activity;
    private final BridgeDevice device;
    private final BridgeAi ai;
    private final BridgeFile file;
    private final BridgeSkill skill;
    private final BridgeModel model;

    public BridgeFactory(HermesActivity activity) {
        this.activity = activity;
        device = new BridgeDevice(activity);
        ai = new BridgeAi(activity);
        file = new BridgeFile(activity);
        skill = new BridgeSkill(activity);
        model = new BridgeModel(activity);
    }

    // ==================== Device ====================
    @JavascriptInterface public String parseIntent(String text) { return device.parseIntent(text); }
    @JavascriptInterface public String execCommand(String text) { return device.execCommand(text); }
    @JavascriptInterface public String getDeviceInfo() { return device.getDeviceInfo(); }
    @JavascriptInterface public String getRuntimeStats() { return device.getRuntimeStats(); }
    @JavascriptInterface public String getPermissionState() { return device.getPermissionState(); }
    @JavascriptInterface public String getWidgetInfo() { return device.getWidgetInfo(); }
    @JavascriptInterface public void openAppSettings() { device.openAppSettings(); }
    @JavascriptInterface public void openUrl(String url) { device.openUrl(url); }

    // ==================== AI ====================
    @JavascriptInterface public void aiChatAsync(String text, String cbId) { ai.aiChatAsync(text, cbId); }
    @JavascriptInterface public void aiChatWithModel(String text, String modelId, String cbId) { ai.aiChatWithModel(text, modelId, cbId); }
    @JavascriptInterface public void agentStart(String goal, String roomId, String modelIdsJson, String cbId) { ai.agentStart(goal, roomId, modelIdsJson, cbId); }
    @JavascriptInterface public void agentStop(String loopId) { ai.agentStop(loopId); }
    @JavascriptInterface public void agentAnswer(String loopId, String text) { ai.agentAnswer(loopId, text); }
    @JavascriptInterface public void agentPlanRespond(String loopId, boolean approved, String note) { ai.agentPlanRespond(loopId, approved, note); }
    @JavascriptInterface public void agentFileWriteRespond(String loopId, boolean approved) { ai.agentFileWriteRespond(loopId, approved); }
    @JavascriptInterface public void agentShellRespond(String loopId, boolean approved) { ai.agentShellRespond(loopId, approved); }
    @JavascriptInterface public String aiChat(String text) { return ai.aiChat(text); }
    @JavascriptInterface public String getAiInfo() { return ai.getAiInfo(); }

    // ==================== File / Storage ====================
    @JavascriptInterface public String listWorkFiles(String roomId) { return file.listWorkFiles(roomId); }
    @JavascriptInterface public String saveWorkFile(String roomId, String path, String content, String author) { return file.saveWorkFile(roomId, path, content, author); }
    @JavascriptInterface public String listVersions(String roomId, String path) { return file.listVersions(roomId, path); }
    @JavascriptInterface public String restoreVersion(String roomId, String path, String snapshotName) { return file.restoreVersion(roomId, path, snapshotName); }
    @JavascriptInterface public String listInboxFiles(String roomId) { return file.listInboxFiles(roomId); }
    @JavascriptInterface public String listArchiveFiles(String roomId) { return file.listArchiveFiles(roomId); }
    @JavascriptInterface public String writeArchive(String roomId, String source, String content) { return file.writeArchive(roomId, source, content); }
    @JavascriptInterface public String deleteWorkFile(String roomId, String path) { return file.deleteWorkFile(roomId, path); }
    @JavascriptInterface public String deleteInboxFile(String roomId, String path) { return file.deleteInboxFile(roomId, path); }
    @JavascriptInterface public String deleteArchiveFile(String roomId, String path) { return file.deleteArchiveFile(roomId, path); }
    @JavascriptInterface public String initRoomStorage(String roomId) { return file.initRoomStorage(roomId); }
    @JavascriptInterface public String getRoomMeta(String roomId) { return file.getRoomMeta(roomId); }
    @JavascriptInterface public String listNotes() { return file.listNotes(); }
    @JavascriptInterface public String saveNote(String name, String content) { return file.saveNote(name, content); }
    @JavascriptInterface public String readNote(String name) { return file.readNote(name); }
    @JavascriptInterface public String deleteNote(String name) { return file.deleteNote(name); }
    @JavascriptInterface public String appendChatMessage(String roomId, String messageJson) { return file.appendChatMessage(roomId, messageJson); }
    @JavascriptInterface public String loadChatMessages(String roomId, String date) { return file.loadChatMessages(roomId, date); }
    @JavascriptInterface public String writeFile(String roomId, String path, String content) { return file.writeFile(roomId, path, content); }
    @JavascriptInterface public String readFile(String roomId, String path) { return file.readFile(roomId, path); }
    @JavascriptInterface public String deleteFile(String roomId, String path) { return file.deleteFile(roomId, path); }
    @JavascriptInterface public String listRoomFiles(String roomId, String subPath) { return file.listRoomFiles(roomId, subPath); }
    @JavascriptInterface public String initRoom(String roomId, String name, String description, String membersJson) { return file.initRoom(roomId, name, description, membersJson); }
    @JavascriptInterface public void pickFile(String cbId, String roomId) { file.pickFile(cbId, roomId); }
    @JavascriptInterface public String pinFileShortcut(String roomId, String path, String label) { return file.pinFileShortcut(roomId, path, label); }
    /* 打包成应用: 签名耗时, 走 testModel 同款后台线程 + 回调 */
    @JavascriptInterface public void buildApk(String roomId, String path, String appName, String cbId) {
        activity.getAiExecutor().execute(() -> {
            String r = file.buildApk(roomId, path, appName);
            activity.evalJsPublic("window._hermesCb('" + cbId + "'," + r + ")");
        });
    }
    /* 一键安装: 房间产出 APK → 系统安装器 */
    @JavascriptInterface public String installApk(String roomId, String path) { return file.installApk(roomId, path); }

    // ==================== Skill ====================
    @JavascriptInterface public String listSkills() { return skill.listSkills(); }
    @JavascriptInterface public String recordSkillUse(String skillId) { return skill.recordSkillUse(skillId); }
    @JavascriptInterface public String deleteSkill(String skillId) { return skill.deleteSkill(skillId); }

    // ==================== Model ====================
    @JavascriptInterface public String listModels() { return model.listModels(); }
    @JavascriptInterface public String getProviderPresets() { return model.getProviderPresets(); }
    @JavascriptInterface public String addModel(String json) { return model.addModel(json); }
    @JavascriptInterface public String updateModel(String json) { return model.updateModel(json); }
    @JavascriptInterface public String deleteModel(String id) { return model.deleteModel(id); }
    @JavascriptInterface public String setDefaultModel(String id) { return model.setDefaultModel(id); }
    @JavascriptInterface public String setReviewer(String id) { return model.setReviewer(id); }
    /* TC-M09: 异步两参版 (bridge.js 回调式调用); 替换原一参同步版 — 同名 @JavascriptInterface 不可重载 */
    @JavascriptInterface public void testModel(String json, String cbId) {
        activity.getAiExecutor().execute(() -> {
            String r = model.testModel(json);
            activity.evalJsPublic("window._hermesCb('" + cbId + "'," + r + ")");
        });
    }
    @JavascriptInterface public String getEncStatus() { return model.getEncStatus(); }
    @JavascriptInterface public String getTokenStats() { return model.getTokenStats(); }

    // ==================== 基础 ====================
    @JavascriptInterface
    public void toast(final String message) {
        activity.runOnUiThread(() ->
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void openAiSettings() {
        activity.runOnUiThread(() ->
            activity.startActivity(new android.content.Intent(activity,
                com.hermes.android.HermesSettingsActivity.class)));
    }

    /* M3: 打开交互式终端 (内嵌 Ubuntu, rootfs 未就绪时终端内给引导) */
    @JavascriptInterface
    public void openTerminal() {
        activity.runOnUiThread(() ->
            activity.startActivity(new android.content.Intent(activity,
                com.hermes.android.TerminalActivity.class)));
    }

    /* M4: 部署服务器配置 (读[脱敏]/写/测试连接; 密钥加密存储) */
    @JavascriptInterface
    public String getDeployConfig() {
        com.hermes.android.linux.DeployConfig d =
                new com.hermes.android.linux.DeployConfig(activity);
        org.json.JSONObject r = new org.json.JSONObject();
        try {
            r.put("host", d.host).put("port", d.port).put("user", d.user)
             .put("authType", d.authType)
             .put("secretMasked", com.hermes.android.linux.DeployConfig.maskSecret(d.secret))
             .put("configured", d.isConfigured());
        } catch (Exception ignored) {}
        return r.toString();
    }

    @JavascriptInterface
    public String saveDeployConfig(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            com.hermes.android.linux.DeployConfig d =
                    new com.hermes.android.linux.DeployConfig(activity);
            d.host = o.optString("host", d.host);
            d.port = o.optInt("port", d.port);
            d.user = o.optString("user", d.user);
            d.authType = o.optString("authType", d.authType);
            if (o.has("secret")) d.secret = o.optString("secret");
            d.save(activity);
            return "{\"ok\":true,\"configured\":" + d.isConfigured() + "}";
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    @JavascriptInterface
    public void testDeployConnection(String callbackId) {
        new Thread(() -> {
            String result;
            com.hermes.android.linux.DeployConfig d =
                    new com.hermes.android.linux.DeployConfig(activity);
            if (!d.isConfigured()) {
                result = "{\"ok\":false,\"content\":\"未配置部署服务器\"}";
            } else if (!com.hermes.android.linux.RootfsManager.isReady(activity)) {
                result = "{\"ok\":false,\"content\":\"Linux 环境未就绪\"}";
            } else {
                com.hermes.android.linux.ProotRunner.ExecResult r =
                        com.hermes.android.linux.ProotRunner.exec(activity,
                                com.hermes.android.linux.DeployConfig.buildTestCmd(), 30);
                boolean ok = r.exitCode == 0 && r.stdout.contains("MOV_SSH_OK");
                try {
                    result = new org.json.JSONObject().put("ok", ok)
                            .put("content", ok ? "连接成功: " + d.user + "@" + d.host
                                    : "exit=" + r.exitCode + " "
                                            + (r.stderr.isEmpty() ? r.stdout : r.stderr)
                                                      .trim()).toString();
                } catch (Exception e) {
                    result = "{\"ok\":false,\"content\":\"测试异常\"}";
                }
            }
            activity.evalJsPublic("window._hermesCb('" + callbackId + "'," + result + ")");
        }).start();
    }

    @JavascriptInterface
    public void setRoomOpen(String roomId) {
        activity.setRoomOpenPublic(roomId != null && !roomId.isEmpty());
    }

    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("MOV", message);
    }
}
