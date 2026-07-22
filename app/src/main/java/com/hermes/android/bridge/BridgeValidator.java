package com.hermes.android.bridge;

/**
 * 统一桥参数校验层 (DESIGN_OPTIMIZE §5.6).
 * 所有桥方法接收的参数在此通过校验后才能进入业务逻辑。
 *
 * 规则:
 * - roomId: 必须匹配 [a-zA-Z0-9_-]+，最多 64 字符
 * - path:   不能含 .. ，不能以 / 开头（相对路径），最多 256 字符
 * - content: 最多 5MB
 * - text:    最多 10000 字符（单条消息上限）
 */
public final class BridgeValidator {

    private static final int MAX_ROOM_ID_LEN = 64;
    private static final int MAX_PATH_LEN = 256;
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024; // 5MB
    private static final int MAX_TEXT_LEN = 10000;

    private static final String ROOM_ID_RE = "^[a-zA-Z0-9_-]+$";

    /** 校验 roomId，不合法返回错误 JSON，合法返回 null */
    public static String checkRoomId(String roomId) {
        if (roomId == null || roomId.isEmpty()) {
            return err("roomId 不能为空");
        }
        if (roomId.length() > MAX_ROOM_ID_LEN) {
            return err("roomId 过长 (>" + MAX_ROOM_ID_LEN + ")");
        }
        if (!roomId.matches(ROOM_ID_RE)) {
            return err("roomId 格式非法: " + roomId);
        }
        return null; // 通过
    }

    /** 校验文件路径（相对路径），不合法返回错误 JSON */
    public static String checkPath(String path) {
        if (path == null || path.isEmpty()) {
            return err("path 不能为空");
        }
        if (path.length() > MAX_PATH_LEN) {
            return err("path 过长 (>" + MAX_PATH_LEN + ")");
        }
        if (path.contains("..")) {
            return err("路径越界: " + path);
        }
        if (path.startsWith("/")) {
            return err("不允许绝对路径: " + path);
        }
        return null;
    }

    /** 校验可选路径（空值放行） */
    public static String checkPathOptional(String path) {
        if (path == null || path.isEmpty()) return null;
        return checkPath(path);
    }

    /** 校验内容大小 */
    public static String checkContent(String content) {
        if (content != null && content.length() > MAX_CONTENT_BYTES) {
            return err("内容过大 (>" + (MAX_CONTENT_BYTES / 1024 / 1024) + "MB)");
        }
        return null;
    }

    /** 校验消息文本长度 */
    public static String checkText(String text) {
        if (text != null && text.length() > MAX_TEXT_LEN) {
            return err("消息过长 (>" + MAX_TEXT_LEN + ")");
        }
        return null;
    }

    /** 校验 name 参数 */
    public static String checkName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        if (name.length() > 128) return err("名称过长 (>" + 128 + ")");
        if (name.contains("\n") || name.contains("\r")) return err("名称含非法字符");
        return null;
    }

    private static String err(String msg) {
        try {
            return new org.json.JSONObject().put("ok", false).put("error", msg).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
