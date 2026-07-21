package com.hermes.android.bridge;

import android.content.Intent;

import com.hermes.android.CapabilityExecutor;
import com.hermes.android.CommandResult;
import com.hermes.android.HermesActivity;
import com.hermes.android.ParsedCommand;
import com.hermes.android.StorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * P1-1: 文件 Bridge — 房间文件 + 存储系统五种类型
 */
public class BridgeFile extends BaseBridge {

    private final StorageManager storage;
    private final CapabilityExecutor executor;

    public BridgeFile(HermesActivity activity, StorageManager storage, CapabilityExecutor executor) {
        super(activity);
        this.storage = storage;
        this.executor = executor;
    }

    // --- 存储系统 ---
    public String listWorkFiles(String roomId) { return storage.listWorkFiles(roomId); }
    public String saveWorkFile(String roomId, String path, String content, String author) {
        return storage.saveWorkFile(roomId, path, content, author);
    }
    public String listVersions(String roomId, String path) { return storage.listVersions(roomId, path); }
    public String restoreVersion(String roomId, String path, String snapshotName) {
        return storage.restoreVersion(roomId, path, snapshotName);
    }
    public String listInboxFiles(String roomId) { return storage.listInboxFiles(roomId); }
    public String listArchiveFiles(String roomId) { return storage.listArchiveFiles(roomId); }
    public String writeArchive(String roomId, String source, String content) {
        return storage.writeArchive(roomId, source, content);
    }
    public String deleteWorkFile(String roomId, String path) { return storage.deleteWorkFile(roomId, path); }
    public String deleteInboxFile(String roomId, String path) { return storage.deleteInboxFile(roomId, path); }
    public String deleteArchiveFile(String roomId, String path) { return storage.deleteArchiveFile(roomId, path); }
    public String initRoomStorage(String roomId) {
        storage.initRoomStorage(roomId);
        return "{\"ok\":true}";
    }
    public String getRoomMeta(String roomId) { return storage.getRoomMeta(roomId); }

    // --- 模板 ---
    public String listTemplates() { return storage.listTemplates(); }
    public String saveTemplate(String name, String content) { return storage.saveTemplate(name, content); }
    public String useTemplate(String templateName, String roomId, String targetName) {
        return storage.useTemplate(templateName, roomId, targetName);
    }

    // --- 笔记 ---
    public String listNotes() { return storage.listNotes(); }
    public String saveNote(String name, String content) { return storage.saveNote(name, content); }
    public String readNote(String name) { return storage.readNote(name); }
    public String deleteNote(String name) { return storage.deleteNote(name); }

    // --- 聊天存储 ---
    public String appendChatMessage(String roomId, String messageJson) {
        return storage.appendChatMessage(roomId, messageJson);
    }
    public String loadChatMessages(String roomId, String date) {
        return storage.loadChatMessages(roomId, date);
    }

    // --- 房间文件直接操作 ---
    public String writeFile(String roomId, String path, String content) {
        ParsedCommand cmd = new ParsedCommand("file.write")
                .arg("roomId", roomId).arg("path", path).arg("content", content);
        CommandResult r = executor.execute(activity, cmd);
        try {
            return new JSONObject().put("ok", r.isSuccess()).put("message", r.getMessage()).toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    public String readFile(String roomId, String path) {
        ParsedCommand cmd = new ParsedCommand("file.read")
                .arg("roomId", roomId).arg("path", path);
        CommandResult r = executor.execute(activity, cmd);
        try {
            JSONObject o = new JSONObject();
            o.put("ok", r.isSuccess());
            if (r.isSuccess()) o.put("content", r.getMessage());
            else o.put("error", r.getMessage());
            return o.toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    public String deleteFile(String roomId, String path) {
        ParsedCommand cmd = new ParsedCommand("file.delete")
                .arg("roomId", roomId).arg("path", path);
        CommandResult r = executor.execute(activity, cmd);
        try {
            return new JSONObject().put("ok", r.isSuccess()).put("message", r.getMessage()).toString();
        } catch (Exception e) { return "{\"ok\":false}"; }
    }

    public String listRoomFiles(String roomId, String subPath) {
        try {
            java.io.File base = new java.io.File(storage.getRoomsDir(), roomId);
            java.io.File dir = (subPath != null && !subPath.isEmpty())
                    ? new java.io.File(base, subPath) : base;
            if (!dir.exists() || !dir.isDirectory()) {
                return "{\"ok\":true,\"files\":[]}";
            }
            java.io.File[] list = dir.listFiles();
            JSONArray arr = new JSONArray();
            if (list != null) {
                java.util.Arrays.sort(list, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (java.io.File f : list) {
                    if (f.getName().startsWith(".hermes")) continue;
                    arr.put(new JSONObject()
                            .put("name", f.getName())
                            .put("isDir", f.isDirectory())
                            .put("size", f.isFile() ? f.length() : 0));
                }
            }
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"files\":[]}";
        }
    }

    public String initRoom(String roomId, String name, String description, String membersJson) {
        try {
            java.io.File base = new java.io.File(storage.getRoomsDir(), roomId);
            base.mkdirs();
            new java.io.File(base, ".hermes").mkdir();

            String readme = "# " + name + "\n\n" + description + "\n\n## 成员\n\n" + membersJson + "\n";
            try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(base, "README.md"))) {
                fw.write(readme);
            }

            JSONObject config = new JSONObject();
            config.put("name", name);
            config.put("description", description);
            config.put("members", new JSONArray(membersJson));
            config.put("created", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(base, ".hermes/config.json"))) {
                fw.write(config.toString(2));
            }

            return "{\"ok\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    public void pickFile(String callbackId, String roomId) {
        activity.pickFilePublic(callbackId, roomId);
    }
}
