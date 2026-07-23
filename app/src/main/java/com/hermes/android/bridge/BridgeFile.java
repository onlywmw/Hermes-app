package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.StorageManager;

/**
 * 文件/存储 Bridge — 委托 StorageManager 实例
 *
 * P0: 所有方法入口全量过 BridgeValidator (DESIGN_OPTIMIZE §5.6)
 */
public class BridgeFile extends BaseBridge {

    private final StorageManager sm;

    public BridgeFile(HermesActivity activity) {
        super(activity);
        this.sm = activity.getStorageManager();
    }

    public String listWorkFiles(String roomId) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        return sm.listWorkFiles(roomId);
    }
    public String saveWorkFile(String roomId, String path, String content, String author) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        e = BridgeValidator.checkContent(content); if (e != null) return e;
        return sm.saveWorkFile(roomId, path, content, author);
    }
    public String listVersions(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.listVersions(roomId, path);
    }
    public String restoreVersion(String roomId, String path, String snapshotName) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        e = BridgeValidator.checkPath(snapshotName); if (e != null) return e;
        return sm.restoreVersion(roomId, path, snapshotName);
    }
    public String listInboxFiles(String roomId) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        return sm.listInboxFiles(roomId);
    }
    public String listArchiveFiles(String roomId) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        return sm.listArchiveFiles(roomId);
    }
    public String writeArchive(String roomId, String source, String content) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(source); if (e != null) return e;
        e = BridgeValidator.checkContent(content); if (e != null) return e;
        return sm.writeArchive(roomId, source, content);
    }
    public String deleteWorkFile(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.deleteWorkFile(roomId, path);
    }
    public String deleteInboxFile(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.deleteInboxFile(roomId, path);
    }
    public String deleteArchiveFile(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.deleteArchiveFile(roomId, path);
    }
    public String initRoomStorage(String roomId) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        sm.initRoomStorage(roomId);
        return "{\"ok\":true}";
    }
    public String getRoomMeta(String roomId) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        return sm.getRoomMeta(roomId);
    }
    public String listNotes() { return sm.listNotes(); }
    public String saveNote(String name, String content) {
        String e = BridgeValidator.checkPath(name); if (e != null) return e;
        e = BridgeValidator.checkContent(content); if (e != null) return e;
        return sm.saveNote(name, content);
    }
    public String readNote(String name) {
        String e = BridgeValidator.checkPath(name); if (e != null) return e;
        return sm.readNote(name);
    }
    public String deleteNote(String name) {
        String e = BridgeValidator.checkPath(name); if (e != null) return e;
        return sm.deleteNote(name);
    }
    public String appendChatMessage(String roomId, String messageJson) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkContent(messageJson); if (e != null) return e;
        return sm.appendChatMessage(roomId, messageJson);
    }
    public String loadChatMessages(String roomId, String date) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        // date 只允许 yyyy-MM-dd，防路径遍历
        if (date == null || !date.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return "{\"ok\":false,\"error\":\"非法日期格式\"}";
        }
        return sm.loadChatMessages(roomId, date);
    }
    public String writeFile(String roomId, String path, String content) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        e = BridgeValidator.checkContent(content); if (e != null) return e;
        return sm.writeFile(roomId, path, content);
    }
    public String readFile(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.readFile(roomId, path);
    }
    public String deleteFile(String roomId, String path) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        return sm.deleteFile(roomId, path);
    }
    public String listRoomFiles(String roomId, String subPath) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        if (subPath != null && !subPath.isEmpty()) {
            e = BridgeValidator.checkPath(subPath); if (e != null) return e;
        }
        return sm.listRoomFiles(roomId, subPath);
    }
    public String initRoom(String roomId, String name, String description, String membersJson) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkName(name); if (e != null) return e;
        return sm.initRoom(roomId, name, description, membersJson);
    }
    public void pickFile(String cbId, String roomId) {
        // roomId 非法时不做房间拷贝，仅返回文件信息
        String safeRoomId = BridgeValidator.checkRoomId(roomId) == null ? roomId : null;
        activity.pickFilePublic(cbId, safeRoomId);
    }

    /**
     * 发送到桌面 (CONTRACT_STORAGE 发送到桌面 §): 为产出文件固定桌面快捷方式,
     * 点击经 HtmlViewerActivity 全屏打开。requestPinShortcut 会弹系统确认框 (正常行为);
     * MIUI 需「桌面快捷方式」权限, 拒绝时返回含引导的错误。
     */
    public String pinFileShortcut(String roomId, String path, String label) {
        String e = BridgeValidator.checkRoomId(roomId); if (e != null) return e;
        e = BridgeValidator.checkPath(path); if (e != null) return e;
        final java.io.File target = sm.resolveWorkFile(roomId, path);
        if (target == null) {
            return "{\"ok\":false,\"error\":\"文件不存在: " + path + "\"}";
        }
        String clean = BridgeValidator.sanitizeLabel(label, 20);
        if (clean.isEmpty()) clean = target.getName();
        final String fLabel = clean;
        /* ShortcutManagerCompat 需在主线程调用; JS 桥在 binder 线程, 闩锁同步等结果 */
        final String[] result = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                result[0] = doPinShortcut(roomId, path, fLabel);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return "{\"ok\":false,\"error\":\"请求超时\"}";
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return "{\"ok\":false,\"error\":\"请求被中断\"}";
        }
        return result[0] != null ? result[0] : "{\"ok\":false,\"error\":\"未知错误\"}";
    }

    private String doPinShortcut(String roomId, String path, String label) {
        try {
            if (!androidx.core.content.pm.ShortcutManagerCompat
                    .isRequestPinShortcutSupported(activity)) {
                return "{\"ok\":false,\"error\":\"当前桌面不支持固定快捷方式\"}";
            }
            android.content.Intent intent = new android.content.Intent(
                    activity, com.hermes.android.HtmlViewerActivity.class);
            intent.setAction(android.content.Intent.ACTION_VIEW);
            intent.putExtra(com.hermes.android.HtmlViewerActivity.EXTRA_ROOM_ID, roomId);
            intent.putExtra(com.hermes.android.HtmlViewerActivity.EXTRA_PATH, path);
            /* 快捷方式 id 稳定唯一: 同房间同路径重复添加时系统复用/更新 */
            String id = "movfile_" + roomId + "_" + Integer.toHexString(path.hashCode());
            androidx.core.content.pm.ShortcutInfoCompat shortcut =
                    new androidx.core.content.pm.ShortcutInfoCompat.Builder(activity, id)
                            .setShortLabel(label)
                            .setIcon(androidx.core.graphics.drawable.IconCompat
                                    .createWithResource(activity, com.hermes.android.R.drawable.ic_launcher))
                            .setIntent(intent)
                            .build();
            boolean requested = androidx.core.content.pm.ShortcutManagerCompat
                    .requestPinShortcut(activity, shortcut, null);
            if (requested) return "{\"ok\":true}";
            return "{\"ok\":false,\"error\":\"桌面拒绝了请求 — MIUI 请在 设置→应用管理→MOV→权限管理 开启「桌面快捷方式」后重试\"}";
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"添加快捷方式失败: "
                    + (ex.getMessage() != null ? ex.getMessage().replace("\"", "'") : "未知") + "\"}";
        }
    }
}
