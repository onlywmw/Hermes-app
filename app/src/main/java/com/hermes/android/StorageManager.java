package com.hermes.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MOV 存储系统 — 五种存储类型的核心逻辑。
 *
 * 磁盘布局 (Scoped Storage):
 * context.getExternalFilesDir(null)/mov/
 *   rooms/<id>/
 *     files/work/           产出 (当前版本)
 *     files/work-snapshots/ 产出历史版本
 *     files/inbox/          资料 (只读)
 *     files/archive/        归档 (按来源分目录)
 *     files/.meta/index.json 元数据
 *   templates/              模板 (跨房间)
 *   personal/notes/         个人笔记
 *
 * P0-1: /sdcard → Scoped Storage 迁移
 */
public class StorageManager {

    private static final String TAG = "StorageManager";
    private final File baseDir;

    public StorageManager(Context context) {
        this.baseDir = new File(context.getExternalFilesDir(null), "mov");
        this.baseDir.mkdirs();
        migrateIfNeeded();
    }

    public File getBaseDir() { return baseDir; }

    public File getRoomsDir() { return new File(baseDir, "rooms"); }

    // ==================== 旧数据迁移 ====================

    private void migrateIfNeeded() {
        File marker = new File(baseDir, ".migrated");
        if (marker.exists()) return;
        File oldBase = new File("/sdcard/mov");
        if (oldBase.exists() && oldBase.isDirectory()) {
            try {
                copyRecursive(oldBase, baseDir);
                Log.i(TAG, "Migrated /sdcard/mov -> " + baseDir.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Migration failed: " + e.getMessage());
            }
        }
        try { marker.createNewFile(); } catch (Exception ignored) {}
    }

    private void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursive(child, new File(dst, child.getName()));
                }
            }
        } else {
            Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ==================== 目录初始化 ====================

    /** 初始化房间存储目录结构 */
    public void initRoomStorage(String roomId) {
        File room = new File(baseDir, "rooms/" + roomId + "/files/");
        new File(room, "work").mkdirs();
        new File(room, "work-snapshots").mkdirs();
        new File(room, "inbox").mkdirs();
        new File(room, "archive").mkdirs();
        new File(room, ".meta").mkdirs();
        File index = new File(room, ".meta/index.json");
        if (!index.exists()) {
            try (FileWriter fw = new FileWriter(index)) {
                fw.write("{\"files\":[]}");
            } catch (Exception e) {
                Log.w(TAG, "initRoomStorage: " + e.getMessage());
            }
        }
    }

    // ==================== 产出 (work) ====================

    public String listWorkFiles(String roomId) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/work");
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String saveWorkFile(String roomId, String path, String content, String author) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            target.getParentFile().mkdirs();

            if (target.exists()) {
                snapshotWorkFile(roomId, path, target);
            }

            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }

            updateMeta(roomId, path, "work", author, target.length());

            return new JSONObject().put("ok", true)
                    .put("message", "已保存: " + path).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private void snapshotWorkFile(String roomId, String path, File current) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String snapName = path.replace("/", "_") + "." + ts;
            File snapDir = new File(baseDir, "rooms/" + roomId + "/files/work-snapshots");
            snapDir.mkdirs();
            File snap = new File(snapDir, snapName);
            Files.copy(current.toPath(), snap.toPath());
        } catch (Exception e) {
            Log.w(TAG, "snapshot: " + e.getMessage());
        }
    }

    public String listVersions(String roomId, String path) {
        try {
            File snapDir = new File(baseDir, "rooms/" + roomId + "/files/work-snapshots");
            String prefix = path.replace("/", "_") + ".";
            JSONArray arr = new JSONArray();
            File[] snaps = snapDir.listFiles();
            if (snaps != null) {
                for (File f : snaps) {
                    if (f.getName().startsWith(prefix)) {
                        String ts = f.getName().substring(prefix.length());
                        arr.put(new JSONObject()
                                .put("name", f.getName())
                                .put("timestamp", ts)
                                .put("size", f.length()));
                    }
                }
            }
            return new JSONObject().put("ok", true).put("versions", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String restoreVersion(String roomId, String path, String snapshotName) {
        try {
            File snapDir = new File(baseDir, "rooms/" + roomId + "/files/work-snapshots");
            File snap = new File(snapDir, snapshotName);
            if (!isSafe(snapDir, snap)) return errJson("路径越界");
            if (!snap.exists()) return errJson("版本不存在");

            File dir = new File(baseDir, "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");

            if (target.exists()) snapshotWorkFile(roomId, path, target);

            Files.copy(snap.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return new JSONObject().put("ok", true)
                    .put("message", "已恢复到: " + snapshotName).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 资料 (inbox) ====================

    public String listInboxFiles(String roomId) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/inbox");
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public File getInboxDir(String roomId) {
        File dir = new File(baseDir, "rooms/" + roomId + "/files/inbox");
        dir.mkdirs();
        return dir;
    }

    // ==================== 归档 (archive) ====================

    public String listArchiveFiles(String roomId) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/archive");
            JSONArray sources = new JSONArray();
            File[] dirs = dir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File src : dirs) {
                    JSONArray files = listDir(src);
                    sources.put(new JSONObject()
                            .put("source", src.getName())
                            .put("count", files.length())
                            .put("files", files));
                }
            }
            return new JSONObject().put("ok", true).put("sources", sources).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String writeArchive(String roomId, String source, String content) {
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String time = new SimpleDateFormat("HHmm", Locale.US).format(new Date());
            File dir = new File(baseDir, "rooms/" + roomId + "/files/archive/" + source);
            dir.mkdirs();
            String fileName = date + "_" + time + ".md";
            File target = new File(dir, fileName);
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }
            return new JSONObject().put("ok", true)
                    .put("file", source + "/" + fileName).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 模板 (templates) ====================

    public String listTemplates() {
        try {
            File dir = new File(baseDir, "templates");
            dir.mkdirs();
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String saveTemplate(String name, String content) {
        try {
            File dir = new File(baseDir, "templates");
            dir.mkdirs();
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String useTemplate(String templateName, String roomId, String targetName) {
        try {
            File src = new File(baseDir, "templates/" + templateName);
            if (!src.exists()) return errJson("模板不存在");
            String content = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);

            File dir = new File(baseDir, "rooms/" + roomId + "/files/work");
            dir.mkdirs();
            File target = new File(dir, targetName);
            if (!isSafe(dir, target)) return errJson("路径越界");
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }
            return new JSONObject().put("ok", true)
                    .put("message", "已从模板创建: " + targetName).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 个人笔记 ====================

    public String listNotes() {
        try {
            File dir = new File(baseDir, "personal/notes");
            dir.mkdirs();
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String saveNote(String name, String content) {
        try {
            File dir = new File(baseDir, "personal/notes");
            dir.mkdirs();
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String readNote(String name) {
        try {
            File dir = new File(baseDir, "personal/notes");
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (!target.exists()) return errJson("笔记不存在");
            String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            return new JSONObject().put("ok", true).put("content", content).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String deleteNote(String name) {
        try {
            File dir = new File(baseDir, "personal/notes");
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 删除操作 ====================

    public String deleteWorkFile(String roomId, String path) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    public String deleteInboxFile(String roomId, String path) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/inbox");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    public String deleteArchiveFile(String roomId, String path) {
        try {
            File dir = new File(baseDir, "rooms/" + roomId + "/files/archive");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    // ==================== 通用工具 ====================

    public String getRoomMeta(String roomId) {
        try {
            File metaFile = new File(baseDir, "rooms/" + roomId + "/files/.meta/index.json");
            if (!metaFile.exists()) return "{\"ok\":true,\"files\":[]}";
            String content = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(content).put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 聊天按天存储 ====================

    public String appendChatMessage(String roomId, String messageJson) {
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File chatDir = new File(baseDir, "rooms/" + roomId + "/chat");
            chatDir.mkdirs();
            File dayFile = new File(chatDir, date + ".jsonl");
            try (FileWriter fw = new FileWriter(dayFile, true)) {
                fw.write(messageJson + "\n");
            }
            return "{\"ok\":true}";
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String loadChatMessages(String roomId, String date) {
        try {
            File dayFile = new File(baseDir, "rooms/" + roomId + "/chat/" + date + ".jsonl");
            if (!dayFile.exists()) return "{\"ok\":true,\"messages\":[]}";
            String content = new String(Files.readAllBytes(dayFile.toPath()), StandardCharsets.UTF_8);
            JSONArray messages = new JSONArray();
            for (String line : content.split("\n")) {
                if (!line.trim().isEmpty()) {
                    try {
                        messages.put(new JSONObject(line.trim()));
                    } catch (Exception ignored) {
                        // 单行解析失败不中断整个加载
                    }
                }
            }
            return new JSONObject().put("ok", true).put("messages", messages).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 房间文件操作 (BridgeFile 用) ====================

    public String writeFile(String roomId, String path, String content) {
        try {
            File base = new File(baseDir, "rooms/" + roomId);
            File target = new File(base, path);
            if (!isSafe(base, target)) return errJson("路径越界");
            target.getParentFile().mkdirs();
            try (java.io.FileWriter fw = new java.io.FileWriter(target)) {
                fw.write(content != null ? content : "");
            }
            return okJson();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String readFile(String roomId, String path) {
        try {
            File base = new File(baseDir, "rooms/" + roomId);
            File target = new File(base, path);
            if (!isSafe(base, target)) return errJson("路径越界");
            if (!target.exists()) return errJson("文件不存在: " + path);
            byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (content.length() > 100000) content = content.substring(0, 100000) + "\n…(截断)";
            return new JSONObject().put("ok", true).put("content", content).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String deleteFile(String roomId, String path) {
        try {
            File base = new File(baseDir, "rooms/" + roomId);
            File target = new File(base, path);
            if (!isSafe(base, target)) return errJson("路径越界");
            if (!target.exists()) return errJson("文件不存在: " + path);
            if (target.isDirectory()) {
                deleteRecursive(target);
            } else {
                target.delete();
            }
            return okJson();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String listRoomFiles(String roomId, String subPath) {
        try {
            File base = new File(baseDir, "rooms/" + roomId);
            File dir = (subPath == null || subPath.isEmpty()) ? base : new File(base, subPath);
            if (!isSafe(base, dir)) return errJson("路径越界");
            if (!dir.exists() || !dir.isDirectory()) return "[]";
            return listDir(dir).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public String initRoom(String roomId, String name, String description, String membersJson) {
        try {
            File room = new File(baseDir, "rooms/" + roomId);
            room.mkdirs();
            JSONObject meta = new JSONObject();
            meta.put("id", roomId);
            meta.put("name", name != null ? name : "");
            meta.put("description", description != null ? description : "");
            meta.put("members", membersJson != null ? new JSONArray(membersJson) : new JSONArray());
            meta.put("created", System.currentTimeMillis());
            try (java.io.FileWriter fw = new java.io.FileWriter(new File(room, "room.json"))) {
                fw.write(meta.toString(2));
            }
            initRoomStorage(roomId);
            return okJson();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    // ==================== 内部工具 ====================

    private JSONArray listDir(File dir) throws Exception {
        JSONArray arr = new JSONArray();
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return Long.compare(b.lastModified(), a.lastModified());
            });
            for (File f : files) {
                if (f.getName().startsWith(".")) continue;
                arr.put(new JSONObject()
                        .put("name", f.getName())
                        .put("isDir", f.isDirectory())
                        .put("size", f.isFile() ? f.length() : 0)
                        .put("modified", f.lastModified()));
            }
        }
        return arr;
    }

    private void updateMeta(String roomId, String path, String type,
                            String author, long size) {
        try {
            File metaFile = new File(baseDir, "rooms/" + roomId + "/files/.meta/index.json");
            JSONObject meta;
            if (metaFile.exists()) {
                meta = new JSONObject(new String(
                        Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
            } else {
                meta = new JSONObject().put("files", new JSONArray());
            }
            JSONArray files = meta.getJSONArray("files");
            boolean found = false;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (f.getString("path").equals(path) && f.getString("type").equals(type)) {
                    f.put("author", author);
                    f.put("size", size);
                    f.put("modified", System.currentTimeMillis());
                    found = true;
                    break;
                }
            }
            if (!found) {
                files.put(new JSONObject()
                        .put("path", path)
                        .put("type", type)
                        .put("author", author)
                        .put("size", size)
                        .put("modified", System.currentTimeMillis()));
            }
            try (FileWriter fw = new FileWriter(metaFile)) {
                fw.write(meta.toString(2));
            }
        } catch (Exception e) {
            Log.w(TAG, "updateMeta: " + e.getMessage());
        }
    }

    private boolean isSafe(File base, File target) {
        try {
            return target.getCanonicalPath().startsWith(base.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private String okJson() {
        return "{\"ok\":true}";
    }

    private String errJson(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private String errJson(Exception e) {
        return errJson(e.getMessage() != null ? e.getMessage() : "未知错误");
    }
}
