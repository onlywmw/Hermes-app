package com.hermes.android;

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
 * 磁盘布局:
 * /sdcard/mov/
 *   rooms/<id>/
 *     files/work/           产出 (当前版本)
 *     files/work-snapshots/ 产出历史版本
 *     files/inbox/          资料 (只读)
 *     files/archive/        归档 (按来源分目录)
 *     files/.meta/index.json 元数据
 *   templates/              模板 (跨房间)
 *   personal/notes/         个人笔记
 */
public class StorageManager {

    private static final String TAG = "StorageManager";
    private static final String BASE = "/sdcard/mov/";

    // ==================== 目录初始化 ====================

    /** 初始化房间存储目录结构 */
    public static void initRoomStorage(String roomId) {
        String room = BASE + "rooms/" + roomId + "/files/";
        new File(room + "work").mkdirs();
        new File(room + "work-snapshots").mkdirs();
        new File(room + "inbox").mkdirs();
        new File(room + "archive").mkdirs();
        new File(room + ".meta").mkdirs();
        // 初始化 index.json
        File index = new File(room + ".meta/index.json");
        if (!index.exists()) {
            try (FileWriter fw = new FileWriter(index)) {
                fw.write("{\"files\":[]}");
            } catch (Exception e) {
                Log.w(TAG, "initRoomStorage: " + e.getMessage());
            }
        }
    }

    // ==================== 产出 (work) ====================

    /** 列出产出文件 */
    public static String listWorkFiles(String roomId) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/work");
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 保存产出文件 (自动快照旧版本) */
    public static String saveWorkFile(String roomId, String path, String content, String author) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            target.getParentFile().mkdirs();

            // 快照旧版本
            if (target.exists()) {
                snapshotWorkFile(roomId, path, target);
            }

            try (FileWriter fw = new FileWriter(target)) {
                fw.write(content);
            }

            // 更新元数据
            updateMeta(roomId, path, "work", author, target.length());

            return new JSONObject().put("ok", true)
                    .put("message", "已保存: " + path).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 快照旧版本到 work-snapshots/ */
    private static void snapshotWorkFile(String roomId, String path, File current) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String snapName = path.replace("/", "_") + "." + ts;
            File snapDir = new File(BASE + "rooms/" + roomId + "/files/work-snapshots");
            snapDir.mkdirs();
            File snap = new File(snapDir, snapName);
            Files.copy(current.toPath(), snap.toPath());
        } catch (Exception e) {
            Log.w(TAG, "snapshot: " + e.getMessage());
        }
    }

    /** 列出版本历史 */
    public static String listVersions(String roomId, String path) {
        try {
            File snapDir = new File(BASE + "rooms/" + roomId + "/files/work-snapshots");
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

    /** 恢复版本 */
    public static String restoreVersion(String roomId, String path, String snapshotName) {
        try {
            File snapDir = new File(BASE + "rooms/" + roomId + "/files/work-snapshots");
            File snap = new File(snapDir, snapshotName);
            if (!isSafe(snapDir, snap)) return errJson("路径越界");
            if (!snap.exists()) return errJson("版本不存在");

            File dir = new File(BASE + "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");

            // 先快照当前
            if (target.exists()) snapshotWorkFile(roomId, path, target);

            // 复制旧版本覆盖
            Files.copy(snap.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            return new JSONObject().put("ok", true)
                    .put("message", "已恢复到: " + snapshotName).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 资料 (inbox) ====================

    /** 列出资料 */
    public static String listInboxFiles(String roomId) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/inbox");
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 上传资料 (从 URI 复制) — 由 Activity 调用 */
    public static File getInboxDir(String roomId) {
        File dir = new File(BASE + "rooms/" + roomId + "/files/inbox");
        dir.mkdirs();
        return dir;
    }

    // ==================== 归档 (archive) ====================

    /** 列出归档 (按来源分组) */
    public static String listArchiveFiles(String roomId) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/archive");
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

    /** 写入归档 (Cron 产出) */
    public static String writeArchive(String roomId, String source, String content) {
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            String time = new SimpleDateFormat("HHmm", Locale.US).format(new Date());
            File dir = new File(BASE + "rooms/" + roomId + "/files/archive/" + source);
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

    /** 列出模板 */
    public static String listTemplates() {
        try {
            File dir = new File(BASE + "templates");
            dir.mkdirs();
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 保存模板 */
    public static String saveTemplate(String name, String content) {
        try {
            File dir = new File(BASE + "templates");
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

    /** 使用模板 (复制到房间产出) */
    public static String useTemplate(String templateName, String roomId, String targetName) {
        try {
            File src = new File(BASE + "templates/" + templateName);
            if (!src.exists()) return errJson("模板不存在");
            String content = new String(Files.readAllBytes(src.toPath()), StandardCharsets.UTF_8);

            File dir = new File(BASE + "rooms/" + roomId + "/files/work");
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

    /** 列出笔记 */
    public static String listNotes() {
        try {
            File dir = new File(BASE + "personal/notes");
            dir.mkdirs();
            JSONArray arr = listDir(dir);
            return new JSONObject().put("ok", true).put("files", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 保存笔记 */
    public static String saveNote(String name, String content) {
        try {
            File dir = new File(BASE + "personal/notes");
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

    /** 读取笔记 */
    public static String readNote(String name) {
        try {
            File dir = new File(BASE + "personal/notes");
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (!target.exists()) return errJson("笔记不存在");
            String content = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            return new JSONObject().put("ok", true).put("content", content).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 删除笔记 */
    public static String deleteNote(String name) {
        try {
            File dir = new File(BASE + "personal/notes");
            File target = new File(dir, name);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 删除产出文件 */
    public static String deleteWorkFile(String roomId, String path) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/work");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    /** 删除资料文件 */
    public static String deleteInboxFile(String roomId, String path) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/inbox");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    /** 删除归档文件 */
    public static String deleteArchiveFile(String roomId, String path) {
        try {
            File dir = new File(BASE + "rooms/" + roomId + "/files/archive");
            File target = new File(dir, path);
            if (!isSafe(dir, target)) return errJson("路径越界");
            if (target.exists()) target.delete();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) { return errJson(e); }
    }

    // ==================== 通用工具 ====================

    /** 读取房间元数据 index.json */
    public static String getRoomMeta(String roomId) {
        try {
            File metaFile = new File(BASE + "rooms/" + roomId + "/files/.meta/index.json");
            if (!metaFile.exists()) return "{\"ok\":true,\"files\":[]}";
            String content = new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(content).put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // ==================== 聊天按天存储 ====================

    /** 追加一条消息到当天的聊天文件 (只追加, 不读全量) */
    public static String appendChatMessage(String roomId, String messageJson) {
        try {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            File chatDir = new File(BASE + "rooms/" + roomId + "/chat");
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

    /** 加载某天的聊天记录 */
    public static String loadChatMessages(String roomId, String date) {
        try {
            File dayFile = new File(BASE + "rooms/" + roomId + "/chat/" + date + ".jsonl");
            if (!dayFile.exists()) return "{\"ok\":true,\"messages\":[]}";
            String content = new String(Files.readAllBytes(dayFile.toPath()), StandardCharsets.UTF_8);
            JSONArray messages = new JSONArray();
            for (String line : content.split("\n")) {
                if (!line.trim().isEmpty()) {
                    messages.put(new JSONObject(line.trim()));
                }
            }
            return new JSONObject().put("ok", true).put("messages", messages).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static JSONArray listDir(File dir) throws Exception {
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

    private static void updateMeta(String roomId, String path, String type,
                                   String author, long size) {
        try {
            File metaFile = new File(BASE + "rooms/" + roomId + "/files/.meta/index.json");
            JSONObject meta;
            if (metaFile.exists()) {
                meta = new JSONObject(new String(
                        Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8));
            } else {
                meta = new JSONObject().put("files", new JSONArray());
            }
            JSONArray files = meta.getJSONArray("files");
            // 查找并更新或追加
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

    private static boolean isSafe(File base, File target) {
        try {
            return target.getCanonicalPath().startsWith(base.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String errJson(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static String errJson(Exception e) {
        return errJson(e.getMessage() != null ? e.getMessage() : "未知错误");
    }
}
