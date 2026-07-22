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
    private final BridgeCron cron;
    private final BridgeSkill skill;
    private final BridgeModel model;

    public BridgeFactory(HermesActivity activity) {
        this.activity = activity;
        device = new BridgeDevice(activity);
        ai = new BridgeAi(activity);
        file = new BridgeFile(activity);
        cron = new BridgeCron(activity);
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

    // ==================== AI ====================
    @JavascriptInterface public void aiChatAsync(String text, String cbId) { ai.aiChatAsync(text, cbId); }
    @JavascriptInterface public void councilAsync(String topic, String cbId) { ai.councilAsync(topic, cbId); }
    @JavascriptInterface public void councilAsync(String topic, String modelIdsJson, String context, String cbId) { ai.councilAsync(topic, modelIdsJson, context, cbId); }
    @JavascriptInterface public String aiChat(String text) { return ai.aiChat(text); }
    @JavascriptInterface public String getAiInfo() { return ai.getAiInfo(); }
    @JavascriptInterface public String getLanguage() { return ai.getLanguage(); }
    @JavascriptInterface public void setLanguage(String lang) { ai.setLanguage(lang); }

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
    @JavascriptInterface public String listTemplates() { return file.listTemplates(); }
    @JavascriptInterface public String saveTemplate(String name, String content) { return file.saveTemplate(name, content); }
    @JavascriptInterface public String useTemplate(String templateName, String roomId, String targetName) { return file.useTemplate(templateName, roomId, targetName); }
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

    // ==================== Cron ====================
    @JavascriptInterface public String listCronJobs() { return cron.listCronJobs(); }
    @JavascriptInterface public String createCronJob(String name, String cronExpr, String command) { return cron.createCronJob(name, cronExpr, command); }
    @JavascriptInterface public String toggleCronJob(String jobId, boolean enabled) { return cron.toggleCronJob(jobId, enabled); }
    @JavascriptInterface public String deleteCronJob(String jobId) { return cron.deleteCronJob(jobId); }

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
    @JavascriptInterface public String testModel(String json) { return model.testModel(json); }
    @JavascriptInterface public String getEncStatus() { return model.getEncStatus(); }

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

    @JavascriptInterface
    public void setRoomOpen(String roomId) {
        activity.setRoomOpenPublic(roomId != null && !roomId.isEmpty());
    }

    @JavascriptInterface
    public void log(String message) {
        android.util.Log.d("MOV", message);
    }
}
