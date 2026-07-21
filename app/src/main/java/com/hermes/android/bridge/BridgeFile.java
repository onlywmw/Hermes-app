package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.StorageManager;

/**
 * 文件/存储 Bridge — 委托 StorageManager 实例
 */
public class BridgeFile extends BaseBridge {

    private final StorageManager sm;

    public BridgeFile(HermesActivity activity) {
        super(activity);
        this.sm = activity.getStorageManager();
    }

    public String listWorkFiles(String roomId) { return sm.listWorkFiles(roomId); }
    public String saveWorkFile(String roomId, String path, String content, String author) { return sm.saveWorkFile(roomId, path, content, author); }
    public String listVersions(String roomId, String path) { return sm.listVersions(roomId, path); }
    public String restoreVersion(String roomId, String path, String snapshotName) { return sm.restoreVersion(roomId, path, snapshotName); }
    public String listInboxFiles(String roomId) { return sm.listInboxFiles(roomId); }
    public String listArchiveFiles(String roomId) { return sm.listArchiveFiles(roomId); }
    public String writeArchive(String roomId, String source, String content) { return sm.writeArchive(roomId, source, content); }
    public String deleteWorkFile(String roomId, String path) { return sm.deleteWorkFile(roomId, path); }
    public String deleteInboxFile(String roomId, String path) { return sm.deleteInboxFile(roomId, path); }
    public String deleteArchiveFile(String roomId, String path) { return sm.deleteArchiveFile(roomId, path); }
    public String initRoomStorage(String roomId) { sm.initRoomStorage(roomId); return "{\"ok\":true}"; }
    public String getRoomMeta(String roomId) { return sm.getRoomMeta(roomId); }
    public String listTemplates() { return sm.listTemplates(); }
    public String saveTemplate(String name, String content) { return sm.saveTemplate(name, content); }
    public String useTemplate(String templateName, String roomId, String targetName) { return sm.useTemplate(templateName, roomId, targetName); }
    public String listNotes() { return sm.listNotes(); }
    public String saveNote(String name, String content) { return sm.saveNote(name, content); }
    public String readNote(String name) { return sm.readNote(name); }
    public String deleteNote(String name) { return sm.deleteNote(name); }
    public String appendChatMessage(String roomId, String messageJson) { return sm.appendChatMessage(roomId, messageJson); }
    public String loadChatMessages(String roomId, String date) { return sm.loadChatMessages(roomId, date); }
    public String writeFile(String roomId, String path, String content) { return sm.writeFile(roomId, path, content); }
    public String readFile(String roomId, String path) { return sm.readFile(roomId, path); }
    public String deleteFile(String roomId, String path) { return sm.deleteFile(roomId, path); }
    public String listRoomFiles(String roomId, String subPath) { return sm.listRoomFiles(roomId, subPath); }
    public String initRoom(String roomId, String name, String description, String membersJson) { return sm.initRoom(roomId, name, description, membersJson); }
    public void pickFile(String cbId, String roomId) { activity.pickFilePublic(cbId, roomId); }
}
