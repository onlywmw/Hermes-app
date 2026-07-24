package com.hermes.android.linux;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * HermesInstaller — 把 Hermes agent 装进内嵌 Ubuntu (M2)。
 *
 * 前置: RootfsManager READY。流程:
 *   ① assets/hermes-agent.zip → linux-exchange/hermes-agent (guest 里挂 /exchange)
 *   ② guest: python3 -m venv ~/.hermes-venv
 *   ③ guest: pip install -e '/exchange/hermes-agent[termux]' -c constraints-termux.txt
 *      (真实 app 域有网; 依赖约 40 个, 多数有 aarch64 wheel)
 *   ④ 判定: ~/.hermes-venv/bin/hermes --version
 *   ⑤ 注入模型配置 (~/.hermes/.env + config.yaml, 见 writeModelConfig)
 *
 * 状态持久化: filesDir/linux/hermes-state.json {"state":"READY","version":"..."}
 */
public class HermesInstaller {

    public enum State { NOT_INSTALLED, INSTALLING, READY, ERROR }

    public interface Listener {
        void onState(State state, String stage, String msg);
    }

    private static final String TAG = "HermesInstaller";
    private static final String VENV_HERMES = "~/.hermes-venv/bin/hermes";

    private final Context ctx;
    private final RootfsManager rootfs;
    private volatile State state = State.NOT_INSTALLED;
    private volatile String version = "";
    private volatile String errorMsg = "";
    private volatile Listener listener;

    public HermesInstaller(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.rootfs = new RootfsManager(ctx);
        loadState();
    }

    public State getState() { return state; }
    public String getVersion() { return version; }
    public String getErrorMsg() { return errorMsg; }
    public void setListener(Listener l) { listener = l; }

    public boolean isReady() {
        return state == State.READY
                && new File(rootfs.rootfsDir(), "root/.hermes-venv/bin/hermes").isFile();
    }

    public static boolean isReady(Context ctx) {
        return new HermesInstaller(ctx).isReady();
    }

    private File stateFile() { return new File(ctx.getFilesDir(), "linux/hermes-state.json"); }
    private File srcDir() { return new File(rootfs.exchangeDir(), "hermes-agent"); }

    // ==================== 安装 ====================

    public synchronized void install() {
        if (state == State.INSTALLING) return;
        new Thread(this::doInstall, "hermes-install").start();
    }

    private void doInstall() {
        try {
            if (!rootfs.isReady()) {
                setState(State.ERROR, "", "先安装 Linux 环境 (rootfs 未就绪)");
                return;
            }
            /* ① 解 zip (先清旧源码, 防版本混杂) */
            setState(State.INSTALLING, "解压源码", "");
            String clearErr = RootfsManager.clearDirectory(srcDir());
            if (clearErr != null) {
                setState(State.ERROR, "", "清空旧源码失败: " + clearErr);
                return;
            }
            srcDir().mkdirs();
            unzip(ctx.getAssets().open("hermes-agent.zip"), srcDir());
            if (!new File(srcDir(), "pyproject.toml").isFile()) {
                setState(State.ERROR, "", "zip 内容不对 (缺 pyproject.toml)");
                return;
            }
            /* ② venv */
            setState(State.INSTALLING, "创建 venv", "");
            ProotRunner.ExecResult venv = ProotRunner.exec(ctx,
                    "python3 -m venv ~/.hermes-venv", 300);
            if (venv.exitCode != 0) {
                setState(State.ERROR, "", "venv 失败: " + tail(venv.stderr));
                return;
            }
            /* ③ pip 安装 (重活: 下载约 40 个依赖; 失败续跑一轮) */
            setState(State.INSTALLING, "pip 安装依赖 (需几分钟)", "");
            String pipCmd = "~/.hermes-venv/bin/pip install --no-input"
                    + " -e '/exchange/hermes-agent[termux]'"
                    + " -c /exchange/hermes-agent/constraints-termux.txt";
            ProotRunner.ExecResult pip = ProotRunner.exec(ctx, pipCmd, 600);
            Log.i(TAG, "pip 一轮 exit=" + pip.exitCode + " timeout=" + pip.timeout
                    + " tail=" + tail(pip.stdout + pip.stderr));
            if (pip.timeout || pip.exitCode != 0) {
                Log.i(TAG, "pip 续跑第二轮");
                ProotRunner.ExecResult pip2 = ProotRunner.exec(ctx, pipCmd, 600);
                Log.i(TAG, "pip 二轮 exit=" + pip2.exitCode + " timeout=" + pip2.timeout
                        + " tail=" + tail(pip2.stdout + pip2.stderr));
                if (pip2.timeout) {
                    setState(State.ERROR, "", "pip 超时 (>20 分钟), 检查网络后重装");
                    return;
                }
            }
            /* ④ 验活 */
            setState(State.INSTALLING, "验证", "");
            ProotRunner.ExecResult check = ProotRunner.exec(ctx,
                    VENV_HERMES + " --version", 120);
            Log.i(TAG, "hermes --version exit=" + check.exitCode + " out=" + tail(check.stdout));
            if (check.exitCode != 0) {
                setState(State.ERROR, "", "hermes 验活失败: " + tail(check.stdout + check.stderr));
                return;
            }
            version = check.stdout.trim().split("\n")[0];
            /* ⑤ 注入模型配置 */
            writeModelConfig(ctx);
            setState(State.READY, "", "");
        } catch (Exception e) {
            setState(State.ERROR, "", e.getMessage() != null ? e.getMessage() : "安装异常");
        }
    }

    // ==================== 模型配置注入 ====================

    /**
     * MOV 默认模型 → guest ~/.hermes/.env + config.yaml。
     * Hermes 配置解析 (源码确认): model.default/model.provider/model.base_url,
     * .env 的 OPENAI_API_KEY/OPENAI_BASE_URL 兜底; provider=custom 走自定义端点。
     * 安装后与每次模型变更时都可调用 (幂等)。key 只写文件, 不进日志。
     */
    public static boolean writeModelConfig(Context ctx) {
        try {
            com.hermes.android.model.ModelConfig mc =
                    com.hermes.android.model.ModelRegistry.getInstance(ctx).getDefault();
            String baseUrl = "", apiKey = "", model = "";
            if (mc != null && !mc.getEffectiveModel().isEmpty()) {
                baseUrl = mc.baseUrl != null ? mc.baseUrl.trim() : "";
                apiKey = mc.apiKey != null ? mc.apiKey.trim() : "";
                model = mc.getEffectiveModel();
            }
            /* 兜底: 注册表无默认/缺 key 时用旧版单模型配置 (AiProviderConfig,
               设备上仅存真实 key 的场景 — key 只写文件, 不进日志) */
            if (apiKey.isEmpty() || baseUrl.isEmpty() || model.isEmpty()) {
                com.hermes.android.ai.AiProviderConfig legacy =
                        new com.hermes.android.ai.AiProviderConfig(ctx);
                if (legacy.isConfigured()) {
                    if (baseUrl.isEmpty()) baseUrl = legacy.getBaseUrl().trim();
                    if (apiKey.isEmpty()) apiKey = legacy.getApiKey().trim();
                    if (model.isEmpty()) model = legacy.getModel().trim();
                }
            }
            if (model.isEmpty()) return false;
            File hermesHome = new File(new RootfsManager(ctx).rootfsDir(), "root/.hermes");
            hermesHome.mkdirs();
            writeFile(new File(hermesHome, ".env"), buildEnvContent(baseUrl, apiKey));
            writeFile(new File(hermesHome, "config.yaml"), buildConfigYaml(model, baseUrl));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "writeModelConfig: " + e.getMessage());
            return false;
        }
    }

    /** .env 内容 (纯函数, 单测用): key 与自定义端点 */
    static String buildEnvContent(String baseUrl, String apiKey) {
        StringBuilder env = new StringBuilder();
        if (apiKey != null && !apiKey.isEmpty()) env.append("OPENAI_API_KEY=").append(apiKey).append('\n');
        if (baseUrl != null && !baseUrl.isEmpty()) env.append("OPENAI_BASE_URL=").append(baseUrl).append('\n');
        return env.toString();
    }

    /** config.yaml 最小模型段 (纯函数, 单测用)。
     *  自定义 OpenAI 兼容端点必须走 named custom provider (providers 块注册
     *  base_url + key_env; provider=custom 会被当内建别名解析失败 401, 实踩)。
     *  key 不写进 config.yaml, 只经 key_env 引用 .env 的 OPENAI_API_KEY。 */
    static String buildConfigYaml(String model, String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "model:\n  default: " + yamlQuote(model) + "\n";
        }
        return "model:\n"
                + "  default: " + yamlQuote(model) + "\n"
                + "  provider: \"mov-custom\"\n"
                + "providers:\n"
                + "  mov-custom:\n"
                + "    base_url: " + yamlQuote(baseUrl) + "\n"
                + "    key_env: \"OPENAI_API_KEY\"\n";
    }

    private static String yamlQuote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ==================== 内部 ====================

    /** 解 zip 到目标目录 (zip-slip 防护; package-private 供单测) */
    static void unzip(InputStream in, File destDir) throws Exception {
        String destPath = destDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                File f = new File(destDir, e.getName()).getCanonicalFile();
                /* zip slip 防护: 条目必须落在目标目录内 */
                if (!f.getPath().startsWith(destPath + File.separator)
                        && !f.getPath().equals(destPath)) continue;
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(f)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) out.write(buf, 0, n);
                }
            }
        }
    }

    private void setState(State s, String stage, String msg) {
        state = s;
        errorMsg = msg != null ? msg : "";
        Log.i(TAG, "state=" + s + " stage=" + stage
                + (errorMsg.isEmpty() ? "" : " msg=" + errorMsg));
        if (s != State.INSTALLING) saveState();
        Listener l = listener;
        if (l != null) {
            try { l.onState(s, stage, errorMsg); } catch (Exception ignored) {}
        }
    }

    private void saveState() {
        try {
            JSONObject o = new JSONObject()
                    .put("state", state.name())
                    .put("version", version)
                    .put("msg", errorMsg);
            try (FileWriter fw = new FileWriter(stateFile())) {
                fw.write(o.toString());
            }
        } catch (Exception ignored) {}
    }

    private void loadState() {
        try {
            File f = stateFile();
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath())));
            State s = State.valueOf(o.optString("state", "NOT_INSTALLED"));
            /* 进程死在 INSTALLING → 回落 NOT_INSTALLED (同 RootfsManager 语义) */
            state = (s == State.INSTALLING) ? State.NOT_INSTALLED : s;
            version = o.optString("version", "");
            errorMsg = o.optString("msg", "");
        } catch (Exception ignored) {}
    }

    private static void writeFile(File f, String content) throws Exception {
        f.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(content);
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 200 ? "…" + t.substring(t.length() - 200) : t;
    }
}
