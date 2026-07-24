package com.hermes.android.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ProductDigest — 交付评审用的产物摘要 (纯逻辑, 可单测)。
 *
 * 两件事:
 * ① build: 每个文本产物取头 4000 字符 + HTML 交互元素清单 (button/onclick/
 *    addEventListener 片段 + 空函数检测), 让评审能证伪「死按钮/空实现」
 * ② parseChecklist: 从 finish summary 解析「功能自检清单」(✅真实现/⚠️演示),
 *    供交付卡结构化展示
 */
public class ProductDigest {

    private static final int PER_FILE_CAP = 4000;
    private static final int TOTAL_CAP = 14000;
    private static final int MAX_FILES = 5;

    private static final Pattern TAG_BUTTON = Pattern.compile(
            "<button[^>]*>(.*?)</button>|<button[^>]*/?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_ONCLICK = Pattern.compile(
            "\\son\\w+\\s*=\\s*[\"']([^\"']{1,80})[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_ADDLISTENER = Pattern.compile(
            "addEventListener\\s*\\(\\s*[\"']([\\w-]+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMPTY_FUNC = Pattern.compile(
            "function\\s+([\\w$]+)\\s*\\([^)]*\\)\\s*\\{\\s*(?:\\/\\/[^\\n]*)?\\s*\\}"
            + "|(?:const|let|var)\\s+([\\w$]+)\\s*=\\s*(?:\\([^)]*\\)|[\\w$]+)\\s*=>\\s*\\{\\s*\\}",
            Pattern.CASE_INSENSITIVE);

    /** 产物内容摘要: 头 4000 字符/文件 + HTML 交互元素清单 */
    public static String build(Map<String, String> files) {
        if (files == null || files.isEmpty()) return "(无产物内容)";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (count >= MAX_FILES || sb.length() >= TOTAL_CAP) {
                sb.append("\n…(更多文件略)\n");
                break;
            }
            count++;
            String name = e.getKey();
            String content = e.getValue() != null ? e.getValue() : "";
            sb.append("=== ").append(name).append(" (共 ").append(content.length()).append(" 字符) ===\n");
            sb.append(content, 0, Math.min(PER_FILE_CAP, content.length()));
            if (content.length() > PER_FILE_CAP) sb.append("\n…(截断)\n");
            sb.append('\n');
            if (name.toLowerCase().endsWith(".html")) {
                sb.append("[交互元素清单]\n").append(extractInteractiveElements(content)).append('\n');
            }
        }
        return sb.toString();
    }

    /** HTML 交互元素清单: 按钮文本/onclick/addEventListener/空函数, 供评审找死按钮 */
    public static String extractInteractiveElements(String html) {
        if (html == null || html.isEmpty()) return "(无)";
        StringBuilder sb = new StringBuilder();
        List<String> emptyFuncs = new ArrayList<>();
        Matcher ef = EMPTY_FUNC.matcher(html);
        while (ef.find()) {
            String n = ef.group(1) != null ? ef.group(1) : ef.group(2);
            if (n != null) emptyFuncs.add(n);
        }
        Matcher btn = TAG_BUTTON.matcher(html);
        int buttons = 0;
        while (btn.find() && buttons < 12) {
            String tag = btn.group(0).replaceAll("\\s+", " ").trim();
            if (tag.length() > 120) tag = tag.substring(0, 120) + "…";
            buttons++;
            sb.append("· button: ").append(tag);
            Matcher oc = TAG_ONCLICK.matcher(tag);
            if (oc.find()) {
                String handler = oc.group(1);
                sb.append(" → ").append(handler);
                String fn = handler.replaceAll("\\(.*\\)", "").trim();
                if (emptyFuncs.contains(fn)) sb.append(" 【空函数!】");
            } else {
                sb.append(" 【无 onclick】");
            }
            sb.append('\n');
        }
        Matcher al = TAG_ADDLISTENER.matcher(html);
        int listeners = 0;
        StringBuilder alSb = new StringBuilder();
        while (al.find() && listeners < 15) {
            listeners++;
            if (alSb.length() > 0) alSb.append(", ");
            alSb.append(al.group(1));
        }
        if (listeners > 0) sb.append("· addEventListener: ").append(alSb).append('\n');
        if (!emptyFuncs.isEmpty()) {
            sb.append("· 空函数体: ");
            for (String n : emptyFuncs) sb.append(n).append(" ");
            sb.append('\n');
        }
        if (buttons == 0 && listeners == 0) sb.append("· (未发现按钮/事件绑定)\n");
        return sb.toString().trim();
    }

    // ==================== 功能自检清单解析 ====================

    public static class CheckItem {
        public final boolean real;      // true=✅真实现 false=⚠️演示
        public final String text;

        public CheckItem(boolean real, String text) {
            this.real = real;
            this.text = text;
        }
    }

    private static final Pattern CHECK_LINE = Pattern.compile(
            "^\\s*[-*•]?\\s*(✅|⚠️)\\s*[：:]?\\s*(.+?)\\s*$");

    /** 从 finish summary 提取自检清单行 (✅=真实现 ⚠️=演示); 无清单返回空表 */
    public static List<CheckItem> parseChecklist(String summary) {
        List<CheckItem> out = new ArrayList<>();
        if (summary == null) return out;
        for (String line : summary.split("\n")) {
            Matcher m = CHECK_LINE.matcher(line);
            if (m.find()) {
                out.add(new CheckItem("✅".equals(m.group(1)), m.group(2).trim()));
            }
        }
        return out;
    }

    /** 文本产物判定 (仅这些扩展名读内容喂评审) */
    public static boolean isTextFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".js")
                || n.endsWith(".py") || n.endsWith(".md") || n.endsWith(".css")
                || n.endsWith(".txt") || n.endsWith(".json") || n.endsWith(".xml")
                || n.endsWith(".sh") || n.endsWith(".yaml") || n.endsWith(".yml")
                || n.endsWith(".java") || n.endsWith(".server");
    }
}
