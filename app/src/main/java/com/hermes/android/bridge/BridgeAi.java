package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.agent.AgentLoop;
import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;
import com.hermes.android.model.ModelRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * AI Bridge — 异步对话 + Council + 配置查询
 */
public class BridgeAi extends BaseBridge {

    private final AiProviderConfig aiConfig;
    private final ModelRegistry modelRegistry;
    private final ExecutorService aiExecutor;
    private final List<AiClient.Message> chatHistory;
    private static final int MAX_HISTORY = 10;

    public BridgeAi(HermesActivity activity) {
        super(activity);
        this.aiConfig = activity.getAiConfig();
        this.modelRegistry = activity.getModelRegistry();
        this.aiExecutor = activity.getAiExecutor();
        this.chatHistory = activity.getChatHistory();
    }

    public void aiChatAsync(String text, String callbackId) {
        if (text == null || text.trim().isEmpty()) {
            evalJs("window._hermesCb('" + callbackId + "',{\"ok\":false,\"content\":\"消息为空\"})");
            return;
        }
        if (text.length() > 4000) {
            evalJs("window._hermesCb('" + callbackId + "',{\"ok\":false,\"content\":\"消息过长\"})");
            return;
        }
        aiExecutor.execute(() -> {
            String resultJson;
            try {
                if (!aiConfig.isAiEnabled()) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 已关闭, 点右上角 ≡ 可启用。\"}";
                } else if (!aiConfig.isConfigured()) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。\"}";
                } else {
                    AiClient client = new AiClient(aiConfig);
                    List<AiClient.Message> history;
                    synchronized (chatHistory) {
                        history = new ArrayList<>(chatHistory);
                    }
                    AiClient.AiResponse resp = client.chat(text, history);
                    if (resp.success) {
                        synchronized (chatHistory) {
                            chatHistory.add(new AiClient.Message("user", text));
                            chatHistory.add(new AiClient.Message("assistant", resp.content));
                            while (chatHistory.size() > MAX_HISTORY * 2) chatHistory.remove(0);
                        }
                        activity.saveChatHistoryPublic();
                        resultJson = new JSONObject()
                                .put("ok", true)
                                .put("content", resp.content).toString();
                    } else {
                        resultJson = new JSONObject()
                                .put("ok", false)
                                .put("content", "AI 调用失败: " + resp.content).toString();
                    }
                }
            } catch (Exception e) {
                try {
                    resultJson = new JSONObject()
                            .put("ok", false)
                            .put("content", "AI 调用异常: " + e.getMessage()).toString();
                } catch (Exception ex) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 调用异常\"}";
                }
            }
            evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
        });
    }

    /** DESIGN_NEW_ROOM v2: 单聊房按房间绑定模型对话 (modelId 空/失效 → 注册表默认模型) */
    public void aiChatWithModel(String text, String modelId, String callbackId) {
        if (text == null || text.trim().isEmpty()) {
            evalJs("window._hermesCb('" + callbackId + "',{\"ok\":false,\"content\":\"消息为空\"})");
            return;
        }
        if (text.length() > 4000) {
            evalJs("window._hermesCb('" + callbackId + "',{\"ok\":false,\"content\":\"消息过长\"})");
            return;
        }
        aiExecutor.execute(() -> {
            String resultJson;
            try {
                com.hermes.android.model.ModelConfig mc = null;
                if (modelId != null && !modelId.isEmpty()) mc = modelRegistry.get(modelId);
                if (mc == null) mc = modelRegistry.getDefault();
                if (mc == null) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 尚未配置模型, 运行页添加后即可畅聊。\"}";
                } else {
                    AiClient client = new AiClient(mc);
                    List<AiClient.Message> history;
                    synchronized (chatHistory) {
                        history = new ArrayList<>(chatHistory);
                    }
                    AiClient.AiResponse resp = client.chat(text, history);
                    if (resp.success) {
                        synchronized (chatHistory) {
                            chatHistory.add(new AiClient.Message("user", text));
                            chatHistory.add(new AiClient.Message("assistant", resp.content));
                            while (chatHistory.size() > MAX_HISTORY * 2) chatHistory.remove(0);
                        }
                        activity.saveChatHistoryPublic();
                        resultJson = new JSONObject()
                                .put("ok", true)
                                .put("content", resp.content).toString();
                    } else {
                        resultJson = new JSONObject()
                                .put("ok", false)
                                .put("content", "AI 调用失败: " + resp.content).toString();
                    }
                }
            } catch (Exception e) {
                try {
                    resultJson = new JSONObject()
                            .put("ok", false)
                            .put("content", "AI 调用异常: " + e.getMessage()).toString();
                } catch (Exception ex) {
                    resultJson = "{\"ok\":false,\"content\":\"AI 调用异常\"}";
                }
            }
            evalJs("window._hermesCb('" + callbackId + "'," + resultJson + ")");
        });
    }

    // ==================== AgentLoop (DESIGN_AGENT_LOOP v1) ====================

    /** 排队中的任务 (全局单 loop, 第二个任务内部排队) */
    private String queuedGoal, queuedRoomId, queuedModelIds, queuedCbId;

    /** 启动 agentic 循环; 有活跃 loop 时排队并提示。modelIdsJson: 房间 AI 成员 (评审团候选) */
    public void agentStart(String goal, String roomId, String modelIdsJson, String callbackId) {
        if (goal == null || goal.trim().isEmpty()) {
            evalJs("window._hermesCb('" + callbackId + "',{\"ok\":false,\"error\":\"目标为空\"})");
            return;
        }
        AgentLoop.Brain brain = (sysPrompt, userText) -> {
            com.hermes.android.model.ModelConfig mc = modelRegistry.getDefault();
            if (mc == null) {
                return new AiClient.AiResponse(false, "未配置默认模型, 请在运行页添加");
            }
            return new AiClient(mc, sysPrompt).chat(userText);
        };
        AgentLoop.Tools tools = new AgentLoop.Tools() {
            private final com.hermes.android.StorageManager sm = activity.getStorageManager();

            @Override
            public String fileList(String roomId) {
                return sm.listRoomFiles(roomId, "files/work");
            }

            @Override
            public String fileRead(String roomId, String path) {
                try {
                    JSONObject r = new JSONObject(sm.readFile(roomId, "files/work/" + path));
                    return r.optBoolean("ok") ? r.optString("content") : "ERROR: " + r.optString("error");
                } catch (Exception e) {
                    return "ERROR: " + e.getMessage();
                }
            }

            @Override
            public String fileWrite(String roomId, String path, String content) {
                try {
                    /* 走 saveWorkFile: 覆盖已存在文件前先快照 (与 JS 用户保存路径一致) */
                    JSONObject r = new JSONObject(sm.saveWorkFile(roomId, path, content, "AI"));
                    return r.optBoolean("ok") ? "OK: 已写入" : "ERR: " + r.optString("error");
                } catch (Exception e) {
                    return "ERR: " + e.getMessage();
                }
            }

            @Override
            public String capabilityOf(String text) {
                try {
                    com.hermes.android.ParsedCommand cmd =
                            new com.hermes.android.IntentParser().parse(text);
                    return cmd == null || cmd.isError() ? "" : cmd.getCapability();
                } catch (Exception e) {
                    return "";
                }
            }

            @Override
            public String deviceCmd(String text) {
                try {
                    com.hermes.android.ParsedCommand cmd =
                            new com.hermes.android.IntentParser().parse(text);
                    com.hermes.android.CommandResult r =
                            activity.getCapabilityExecutor().execute(activity, cmd);
                    return r.getMessage();
                } catch (Exception e) {
                    return "执行失败: " + e.getMessage();
                }
            }

            @Override
            public String packageApk(String roomId, String path, String appName) {
                try {
                    com.hermes.android.packager.PackageBuilder.Result r =
                            com.hermes.android.packager.PackageBuilder.build(
                                    activity, sm, roomId, path, appName);
                    if (!r.ok) {
                        return new JSONObject().put("ok", false)
                                .put("error", r.error != null ? r.error : "打包失败").toString();
                    }
                    /* APK 落到房间产出目录, 文件列表可见; ".html" 纯扩展名 → 兜底命名 */
                    String base = new java.io.File(path).getName()
                            .replaceAll("\\.[^.]+$", "");
                    if (base.isEmpty()) base = "未命名应用";
                    String label = base + ".apk";
                    java.io.File workDir = new java.io.File(
                            sm.getRoomsDir(), roomId + "/files/work");
                    workDir.mkdirs();
                    java.io.File dst = new java.io.File(workDir, label).getCanonicalFile();
                    if (!dst.getPath().startsWith(workDir.getCanonicalFile().getPath()
                            + java.io.File.separator)) {
                        return "{\"ok\":false,\"error\":\"路径越界\"}";
                    }
                    java.nio.file.Files.copy(r.apkFile.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    String msg = label + " · " + (r.sizeBytes / 1024) + "KB · " + r.packageName
                            + " · 已放入房间文件, 点交付卡上的「安装」按钮完成安装";
                    if (r.warning != null) msg += "\n" + r.warning;
                    return new JSONObject().put("ok", true)
                            .put("file", label).put("msg", msg).toString();
                } catch (Exception e) {
                    try {
                        return new JSONObject().put("ok", false)
                                .put("error", String.valueOf(e.getMessage())).toString();
                    } catch (Exception ex) {
                        return "{\"ok\":false,\"error\":\"打包异常\"}";
                    }
                }
            }

            @Override
            public String shellExec(String cmd, int timeoutSec) {
                if (!com.hermes.android.linux.RootfsManager.isReady(activity)) {
                    return "ERROR: Linux 环境未就绪, 请先在设置页安装 (设置 → Linux 环境 → 安装),"
                            + " 就绪后 shell.exec 才能使用";
                }
                return com.hermes.android.linux.ProotRunner.exec(activity, cmd, timeoutSec).format();
            }

            @Override
            public String filePush(String roomId, String path) {
                return sm.pushToExchange(roomId, path);
            }

            @Override
            public String filePull(String roomId, String name) {
                return sm.pullFromExchange(roomId, name);
            }
        };
        AgentLoop.LogSink sink = log -> {
            try {
                evalJs("window._agentLog(" + log.toString() + ")");
            } catch (Exception ignored) {}
        };
        /* 工具注册表: 探测本机硬件/权限生成 (agent 与硬件控制的分割层);
           内嵌 Linux rootfs 就绪时追加 shell.exec/file.push/file.pull */
        java.util.Set<String> sharedPaths = new java.util.HashSet<>();
        com.hermes.android.agent.ToolRegistry registry = com.hermes.android.agent.ToolRegistry.build(
                tools, roomId, () -> sharedPaths,
                com.hermes.android.agent.ToolRegistry.DevicePolicy.probe(activity),
                com.hermes.android.linux.RootfsManager.isReady(activity));
        AgentLoop loop = AgentLoop.startNew(roomId, goal, brain, tools, registry,
                sharedPaths, buildReviewer(modelIdsJson), wrapSinkWithQueue(sink));
        if (loop == null) {
            queuedGoal = goal; queuedRoomId = roomId;
            queuedModelIds = modelIdsJson; queuedCbId = callbackId;
            evalJs("window._hermesCb('" + callbackId
                    + "',{\"ok\":true,\"queued\":true,\"note\":\"上一个任务还在执行, 已排队\"})");
            return;
        }
        evalJs("window._hermesCb('" + callbackId
                + "',{\"ok\":true,\"loopId\":\"" + loop.getLoopId() + "\"})");
    }

    /** v2: 由房间成员构建评审团 (排除大脑=默认模型; 空 → null 跳过评审点) */
    private AgentLoop.Reviewer buildReviewer(String modelIdsJson) {
        try {
            com.hermes.android.model.ModelConfig def = modelRegistry.getDefault();
            String defId = def != null ? def.id : null;
            java.util.List<com.hermes.android.model.ModelConfig> reviewers = new java.util.ArrayList<>();
            JSONArray arr = new JSONArray(modelIdsJson != null ? modelIdsJson : "[]");
            for (int i = 0; i < arr.length(); i++) {
                /* 兼容成员格式: 字符串 id 或 {"id":..} 对象 (房间 members.ai 两种来源) */
                Object item = arr.get(i);
                String mid = item instanceof JSONObject
                        ? ((JSONObject) item).optString("id") : String.valueOf(item);
                com.hermes.android.model.ModelConfig mc = modelRegistry.get(mid);
                /* 剔除不可用/假模型: 未配置 key, 或 baseUrl 指向 localhost 的测试模型
                   (mock-llm 是 ollama+127.0.0.1, 真实评审不许它进团) */
                boolean localhost = mc != null && mc.baseUrl != null
                        && (mc.baseUrl.contains("127.0.0.1") || mc.baseUrl.contains("localhost"));
                if (mc != null && mc.enabled && mc.isConfigured() && !localhost
                        && (defId == null || !defId.equals(mc.id))) {
                    reviewers.add(mc);
                }
            }
            return reviewers.isEmpty() ? null : new com.hermes.android.agent.AgentReview(
                    reviewers,
                    mc -> (sp, text) -> new AiClient(mc, sp).chat(text));
        } catch (Exception e) {
            return null;
        }
    }

    /** 在日志出口外包一层: 终态时自动启动排队任务 */
    private AgentLoop.LogSink wrapSinkWithQueue(AgentLoop.LogSink inner) {
        return log -> {
            inner.onLog(log);
            String type = log.optString("type");
            if ("deliver".equals(type) || "fail".equals(type) || "stopped".equals(type)) {
                maybeStartQueued();
            }
        };
    }

    private synchronized void maybeStartQueued() {
        if (queuedGoal == null) return;
        String g = queuedGoal, r = queuedRoomId, m = queuedModelIds, cb = queuedCbId;
        queuedGoal = null; queuedRoomId = null; queuedModelIds = null; queuedCbId = null;
        evalJs("window._agentLog({\"type\":\"note\",\"text\":\"开始执行排队任务\"})");
        agentStart(g, r, m, cb);
    }

    public void agentStop(String loopId) {
        AgentLoop l = AgentLoop.current();
        if (l != null && l.getLoopId().equals(loopId)) l.requestStop();
    }

    public void agentAnswer(String loopId, String text) {
        AgentLoop l = AgentLoop.current();
        if (l != null && l.getLoopId().equals(loopId)) l.answer(text);
    }

    public void agentPlanRespond(String loopId, boolean approved, String note) {
        AgentLoop l = AgentLoop.current();
        if (l != null && l.getLoopId().equals(loopId)) l.respondPlan(approved, note);
    }

    /** P1: 文件写入预览确认/驳回 (照 agentPlanRespond 模式) */
    public void agentFileWriteRespond(String loopId, boolean approved) {
        AgentLoop l = AgentLoop.current();
        if (l != null && l.getLoopId().equals(loopId)) l.respondFileWrite(approved);
    }

    /** 计划外 shell.exec 确认/驳回 (照 agentFileWriteRespond 模式) */
    public void agentShellRespond(String loopId, boolean approved) {
        AgentLoop l = AgentLoop.current();
        if (l != null && l.getLoopId().equals(loopId)) l.respondShell(approved);
    }

    public String aiChat(String text) {
        if (!aiConfig.isAiEnabled()) return "AI 已关闭, 点右上角 ≡ 可启用。";
        if (!aiConfig.isConfigured()) return "AI 尚未配置 API Key, 点右上角 ≡ 设置后即可畅聊。";
        try {
            AiClient client = new AiClient(aiConfig);
            AiClient.AiResponse resp = client.chat(text, new ArrayList<>());
            if (resp.success) return resp.content;
            return "AI 调用失败: " + resp.content;
        } catch (Exception e) {
            return "AI 调用异常: " + e.getMessage();
        }
    }

    public String getAiInfo() {
        try {
            return new JSONObject()
                    .put("enabled", aiConfig.isAiEnabled())
                    .put("configured", aiConfig.isConfigured())
                    .put("displayName", aiConfig.getProviderDisplayName().toUpperCase())
                    .put("model", aiConfig.getModel())
                    .put("summary", aiConfig.getStatusSummary())
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
