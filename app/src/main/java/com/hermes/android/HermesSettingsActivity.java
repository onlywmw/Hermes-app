package com.hermes.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hermes.android.ai.AiProviderConfig;
import com.hermes.android.linux.DeployConfig;
import com.hermes.android.linux.HermesInstaller;
import com.hermes.android.linux.Proot;
import com.hermes.android.linux.ProotRunner;
import com.hermes.android.linux.RootfsManager;

import com.hermes.android.model.ModelConfig;
import com.hermes.android.model.ModelPresets;
import com.hermes.android.model.ModelRegistry;

import java.util.List;

/**
 * 设置页: 多模型管理 + 统计。
 * 模型列表 → 点击编辑 → 保存/测试/删除。
 * 厂商数据来自 ModelPresets，选中厂商自动填充 baseUrl / 默认模型。
 */
public class HermesSettingsActivity extends AppCompatActivity {

    private static final String[] ROLE_NAMES = {"通用", "产品", "技术", "数据", "自定义"};

    private ModelRegistry registry;
    private AiProviderConfig aiConfig;

    private LinearLayout modelListContainer;
    private LinearLayout editForm;
    private TextView tvEditTitle, tvProviderNote;
    private EditText etName, etBaseUrl, etApiKey;
    private AutoCompleteTextView etModel;
    private Spinner spinnerProvider, spinnerRole;
    private Button btnGetApiKey;

    /** 全部厂商预设，Spinner 下标与其一一对应 */
    private ModelPresets.Preset[] presets;
    /**
     * 已同步的厂商下标。setSelection 的回调是异步派发的，
     * 用位置比对代替布尔标记：回调位置与已同步位置一致时忽略，
     * 避免程序化选中（如打开编辑表单）覆盖用户已填内容。
     */
    private int syncedProviderPos = -1;

    /** 当前编辑的模型 ID, null = 新建 */
    private String editingId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_settings);

        registry = ModelRegistry.getInstance(this);
        aiConfig = new AiProviderConfig(this);

        modelListContainer = findViewById(R.id.modelListContainer);
        editForm = findViewById(R.id.editForm);
        tvEditTitle = findViewById(R.id.tvEditTitle);
        tvProviderNote = findViewById(R.id.tvProviderNote);
        etName = findViewById(R.id.etName);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        btnGetApiKey = findViewById(R.id.btnGetApiKey);
        spinnerProvider = findViewById(R.id.spinnerProvider);
        spinnerRole = findViewById(R.id.spinnerRole);

        // 厂商 Spinner: 数据源为 ModelPresets
        presets = ModelPresets.all();
        String[] providerNames = new String[presets.length];
        for (int i = 0; i < presets.length; i++) providerNames[i] = presets[i].displayName;
        spinnerProvider.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, providerNames));
        spinnerProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos == syncedProviderPos) return;
                syncedProviderPos = pos;
                applyPreset(pos);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

        // 模型名: 点击弹出推荐模型候选
        etModel.setThreshold(0);
        etModel.setOnClickListener(v -> etModel.showDropDown());

        spinnerRole.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ROLE_NAMES));

        // 获取 API Key: 打开当前厂商的 Key 控制台页面
        btnGetApiKey.setOnClickListener(v -> {
            ModelPresets.Preset preset = currentPreset();
            if (preset == null) return;
            if (preset.keyConsoleUrl == null || preset.keyConsoleUrl.isEmpty()) {
                Toast.makeText(this, "本地模型无需 Key", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(preset.keyConsoleUrl)));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开页面: " + preset.keyConsoleUrl, Toast.LENGTH_SHORT).show();
            }
        });

        // 添加模型
        findViewById(R.id.btnAddModel).setOnClickListener(v -> openEditForm(null));

        // 编辑表单按钮
        findViewById(R.id.btnSaveModel).setOnClickListener(v -> saveModel());
        findViewById(R.id.btnTestModel).setOnClickListener(v -> testModel());
        findViewById(R.id.btnCancelEdit).setOnClickListener(v -> closeEditForm());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        buildLinuxSection();
        buildDeploySection();
        refreshStatus();
        renderModelList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* 从「所有文件访问」授权页返回时刷新 Linux 区状态 */
        if (linuxRootfs != null) renderLinuxSection();
    }

    /* ── Linux 环境 (内嵌 Ubuntu + proot) ── */

    private RootfsManager linuxRootfs;
    private HermesInstaller hermesInstaller;
    private TextView tvLinuxProbe, tvLinuxStatus, tvHermesStatus;
    private Button btnLinuxInstall, btnHermesInstall;

    private void buildLinuxSection() {
        linuxRootfs = new RootfsManager(this);
        linuxRootfs.setListener((s, prog, msg) -> runOnUiThread(this::renderLinuxSection));
        hermesInstaller = new HermesInstaller(this);
        hermesInstaller.setListener((s, stage, msg) -> runOnUiThread(this::renderLinuxSection));

        LinearLayout container = findViewById(R.id.linuxContainer);
        TextView title = new TextView(this);
        title.setText("Linux 环境");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(20), 0, 0);
        container.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_input));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        /* proot 探测: M1 生死验证 — app 域能否 exec nativeLibraryDir 里的 proot */
        tvLinuxProbe = new TextView(this);
        tvLinuxProbe.setText("proot: 探测中…");
        tvLinuxProbe.setTextColor(getColor(R.color.text_secondary));
        tvLinuxProbe.setTextSize(12);
        card.addView(tvLinuxProbe);
        new Thread(() -> {
            String probe = Proot.probe(this);
            runOnUiThread(() -> {
                boolean ok = !probe.startsWith("ERROR:");
                tvLinuxProbe.setText("proot: " + probe);
                tvLinuxProbe.setTextColor(getColor(ok
                        ? R.color.accent_light : R.color.text_secondary));
            });
        }).start();

        tvLinuxStatus = new TextView(this);
        tvLinuxStatus.setTextColor(getColor(R.color.text_secondary));
        tvLinuxStatus.setTextSize(12);
        tvLinuxStatus.setPadding(0, dp(4), 0, 0);
        card.addView(tvLinuxStatus);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        btnLinuxInstall = makeSmallBtn("安装", v -> {
            linuxRootfs.install();
            renderLinuxSection();
        });
        row.addView(btnLinuxInstall);
        row.addView(makeSmallBtn("授权文件访问", v -> {
            /* /sdcard 挂载与本地包优先都依赖 all-files 权限 */
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }));
        row.addView(makeSmallBtn("卸载", v -> {
            linuxRootfs.uninstall();
            renderLinuxSection();
            Toast.makeText(this, "Linux 环境已卸载", Toast.LENGTH_SHORT).show();
        }));
        /* M3: 交互终端入口 */
        row.addView(makeSmallBtn("打开终端", v ->
                startActivity(new Intent(this, TerminalActivity.class))));
        card.addView(row);

        /* Hermes agent (M2): 内嵌进 Ubuntu 的第二个 agent, rootfs READY 后可装 */
        TextView hermesTitle = new TextView(this);
        hermesTitle.setText("Hermes agent (内嵌委派)");
        hermesTitle.setTextColor(getColor(R.color.text_primary));
        hermesTitle.setTextSize(13);
        hermesTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        hermesTitle.setPadding(0, dp(10), 0, 0);
        card.addView(hermesTitle);

        tvHermesStatus = new TextView(this);
        tvHermesStatus.setTextColor(getColor(R.color.text_secondary));
        tvHermesStatus.setTextSize(12);
        tvHermesStatus.setPadding(0, dp(4), 0, 0);
        card.addView(tvHermesStatus);

        LinearLayout hrow = new LinearLayout(this);
        hrow.setOrientation(LinearLayout.HORIZONTAL);
        hrow.setPadding(0, dp(6), 0, 0);
        btnHermesInstall = makeSmallBtn("安装 Hermes", v -> {
            hermesInstaller.install();
            renderLinuxSection();
        });
        hrow.addView(btnHermesInstall);
        card.addView(hrow);
        container.addView(card);
        renderLinuxSection();
    }

    private void renderLinuxSection() {
        if (linuxRootfs == null || tvLinuxStatus == null) return;
        RootfsManager.State s = linuxRootfs.getState();
        String text;
        boolean busy = false;
        switch (s) {
            case READY:
                text = "状态: 已就绪 (Ubuntu 24.04) · 占用 "
                        + (linuxRootfs.diskUsage() / 1024 / 1024) + " MB";
                break;
            case DOWNLOADING:
                text = "状态: 下载 rootfs 中 " + linuxRootfs.getProgress() + "%";
                busy = true;
                break;
            case EXTRACTING:
                text = "状态: 解压 rootfs 中…";
                busy = true;
                break;
            case BOOTSTRAPPING:
                text = "状态: 首次初始化装包中 (python3/git, 需几分钟)…";
                busy = true;
                break;
            case ERROR:
                text = "状态: 出错 — " + linuxRootfs.getErrorMsg();
                break;
            default:
                text = "状态: 未安装 (agent 的 shell.exec 需要先安装)";
        }
        tvLinuxStatus.setText(text);
        if (btnLinuxInstall != null) {
            btnLinuxInstall.setEnabled(!busy);
            btnLinuxInstall.setAlpha(busy ? 0.4f : 1f);
            btnLinuxInstall.setText(s == RootfsManager.State.READY ? "重装" : "安装");
        }
        renderHermesStatus(s == RootfsManager.State.READY);
    }

    private void renderHermesStatus(boolean rootfsReady) {
        if (hermesInstaller == null || tvHermesStatus == null) return;
        HermesInstaller.State s = hermesInstaller.getState();
        String text;
        boolean busy = false;
        switch (s) {
            case READY:
                text = "Hermes: 已就绪 · " + hermesInstaller.getVersion();
                break;
            case INSTALLING:
                text = "Hermes: 安装中… (pip 装依赖, 需几分钟)";
                busy = true;
                break;
            case ERROR:
                text = "Hermes: 出错 — " + hermesInstaller.getErrorMsg();
                break;
            default:
                text = rootfsReady ? "Hermes: 未安装 (agent 重任务委派)"
                        : "Hermes: 需先安装 Linux 环境";
        }
        tvHermesStatus.setText(text);
        if (btnHermesInstall != null) {
            boolean enabled = rootfsReady && !busy;
            btnHermesInstall.setEnabled(enabled);
            btnHermesInstall.setAlpha(enabled ? 1f : 0.4f);
            btnHermesInstall.setText(s == HermesInstaller.State.READY ? "重装 Hermes" : "安装 Hermes");
        }
    }

    /* ── 部署服务器 (M4: SSH 部署目标, 配置加密存储 + 注入 rootfs) ── */

    private DeployConfig deployConfig;
    private EditText editDeployHost, editDeployPort, editDeployUser, editDeploySecret;
    private Button btnDeployAuth;
    private TextView tvDeployStatus;
    private boolean deployTesting = false;

    private void buildDeploySection() {
        deployConfig = new DeployConfig(this);
        LinearLayout container = findViewById(R.id.linuxContainer);

        TextView title = new TextView(this);
        title.setText("部署服务器");
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, dp(20), 0, 0);
        container.addView(title);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_input));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        TextView hint = new TextView(this);
        hint.setText("agent 写后端后可 ssh 部署到此服务器 (别名 mov-deploy); 密钥加密存储");
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setTextSize(11);
        card.addView(hint);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        editDeployHost = deployField("主机 (IP/域名)", 1f);
        editDeployHost.setText(deployConfig.host);
        row1.addView(editDeployHost);
        editDeployPort = deployField("端口", 0.4f);
        editDeployPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        editDeployPort.setText(String.valueOf(deployConfig.port));
        row1.addView(editDeployPort);
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        editDeployUser = deployField("用户名", 1f);
        editDeployUser.setText(deployConfig.user);
        row2.addView(editDeployUser);
        btnDeployAuth = makeSmallBtn(deployConfig.authType.equals(DeployConfig.AUTH_KEY)
                ? "私钥认证" : "密码认证", v -> {
            deployConfig.authType = deployConfig.authType.equals(DeployConfig.AUTH_KEY)
                    ? DeployConfig.AUTH_PASSWORD : DeployConfig.AUTH_KEY;
            btnDeployAuth.setText(deployConfig.authType.equals(DeployConfig.AUTH_KEY)
                    ? "私钥认证" : "密码认证");
            editDeploySecret.setHint(deployConfig.authType.equals(DeployConfig.AUTH_KEY)
                    ? "粘贴私钥 (BEGIN OPENSSH PRIVATE KEY)" : "SSH 密码");
        });
        row2.addView(btnDeployAuth);
        card.addView(row2);

        editDeploySecret = deployField(deployConfig.authType.equals(DeployConfig.AUTH_KEY)
                ? "粘贴私钥 (BEGIN OPENSSH PRIVATE KEY)" : "SSH 密码", 1f);
        editDeploySecret.setText(deployConfig.secret);
        editDeploySecret.setSingleLine(false);
        editDeploySecret.setMinLines(2);
        card.addView(editDeploySecret);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(6), 0, 0);
        row3.addView(makeSmallBtn("保存", v -> saveDeploy()));
        row3.addView(makeSmallBtn("测试连接", v -> testDeploy()));
        card.addView(row3);

        tvDeployStatus = new TextView(this);
        tvDeployStatus.setTextColor(getColor(R.color.text_secondary));
        tvDeployStatus.setTextSize(12);
        tvDeployStatus.setPadding(0, dp(6), 0, 0);
        tvDeployStatus.setText(deployConfig.isConfigured()
                ? "已配置: " + deployConfig.user + "@" + deployConfig.host + ":" + deployConfig.port
                : "未配置 — agent 部署后端前会先引导你在这里填写");
        card.addView(tvDeployStatus);
        container.addView(card);
    }

    private EditText deployField(String hint, float weight) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(getColor(R.color.text_secondary));
        e.setTextColor(getColor(R.color.text_primary));
        e.setTextSize(13);
        e.setBackground(getDrawable(R.drawable.bg_chip));
        e.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(40), weight);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    private void saveDeploy() {
        deployConfig.host = editDeployHost.getText().toString().trim();
        try {
            deployConfig.port = Integer.parseInt(editDeployPort.getText().toString().trim());
        } catch (Exception e) {
            deployConfig.port = 22;
        }
        deployConfig.user = editDeployUser.getText().toString().trim();
        deployConfig.secret = editDeploySecret.getText().toString();
        deployConfig.save(this);   // 加密存储 + 注入 rootfs (ssh config/movssh/movscp)
        tvDeployStatus.setText(deployConfig.isConfigured()
                ? "已保存: " + deployConfig.user + "@" + deployConfig.host + ":" + deployConfig.port
                : "已保存 (信息不完整, 部署时 agent 会再引导)");
        Toast.makeText(this, "部署服务器配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void testDeploy() {
        if (deployTesting) return;
        if (!deployConfig.isConfigured()) {
            tvDeployStatus.setText("先填写并保存完整配置");
            return;
        }
        if (!RootfsManager.isReady(this)) {
            tvDeployStatus.setText("Linux 环境未就绪, 先安装");
            return;
        }
        deployTesting = true;
        tvDeployStatus.setText("测试连接中 (ssh " + deployConfig.host + ")…");
        new Thread(() -> {
            ProotRunner.ExecResult r = ProotRunner.exec(this, DeployConfig.buildTestCmd(), 30);
            String msg = (r.exitCode == 0 && r.stdout.contains("MOV_SSH_OK"))
                    ? "✓ 连接成功: " + deployConfig.user + "@" + deployConfig.host
                    : "✗ 连接失败 exit=" + r.exitCode + " " + tailText(r.stderr.isEmpty()
                            ? r.stdout : r.stderr);
            runOnUiThread(() -> {
                tvDeployStatus.setText(msg);
                deployTesting = false;
            });
        }).start();
    }

    private static String tailText(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 120 ? "…" + t.substring(t.length() - 120) : t;
    }

    /* ── 厂商预设联动 ── */

    private ModelPresets.Preset currentPreset() {
        int pos = spinnerProvider.getSelectedItemPosition();
        if (presets == null || pos < 0 || pos >= presets.length) return null;
        return presets[pos];
    }

    /** 选中厂商后: 自动填充 baseUrl / 默认模型 / 备注 / 推荐模型候选 / Key 按钮状态 */
    private void applyPreset(int pos) {
        if (presets == null || pos < 0 || pos >= presets.length) return;
        ModelPresets.Preset preset = presets[pos];

        etBaseUrl.setText(preset.baseUrl);
        etModel.setText(preset.defaultModel);
        etModel.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, preset.models));

        if (preset.note != null && !preset.note.isEmpty()) {
            tvProviderNote.setText(preset.note);
            tvProviderNote.setVisibility(View.VISIBLE);
        } else {
            tvProviderNote.setVisibility(View.GONE);
        }

        boolean hasConsole = preset.keyConsoleUrl != null && !preset.keyConsoleUrl.isEmpty();
        btnGetApiKey.setEnabled(hasConsole);
        btnGetApiKey.setAlpha(hasConsole ? 1f : 0.4f);
    }

    /** 程序化选中厂商（不触发自动填充），并同步备注 / Key 按钮等 UI 状态 */
    private void selectProvider(String providerKey) {
        int index = 0;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i].key.equals(providerKey)) { index = i; break; }
        }
        syncedProviderPos = index;
        spinnerProvider.setSelection(index, false);

        // 只更新辅助 UI，不覆盖 baseUrl / model 输入框
        ModelPresets.Preset preset = presets[index];
        etModel.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, preset.models));
        if (preset.note != null && !preset.note.isEmpty()) {
            tvProviderNote.setText(preset.note);
            tvProviderNote.setVisibility(View.VISIBLE);
        } else {
            tvProviderNote.setVisibility(View.GONE);
        }
        boolean hasConsole = preset.keyConsoleUrl != null && !preset.keyConsoleUrl.isEmpty();
        btnGetApiKey.setEnabled(hasConsole);
        btnGetApiKey.setAlpha(hasConsole ? 1f : 0.4f);
    }

    /* ── 模型列表渲染 ── */

    private void renderModelList() {
        modelListContainer.removeAllViews();
        List<ModelConfig> models = registry.list();
        for (ModelConfig m : models) {
            modelListContainer.addView(buildModelCard(m));
        }
    }

    private View buildModelCard(ModelConfig m) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(getDrawable(R.drawable.bg_input));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        // 第一行: 名称 + 默认标签
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(m.name.isEmpty() ? m.getProviderDisplayName() : m.name);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        row1.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (m.isDefault) {
            TextView def = new TextView(this);
            def.setText("默认");
            def.setTextColor(getColor(R.color.accent_light));
            def.setTextSize(11);
            def.setBackground(getDrawable(R.drawable.bg_chip));
            def.setPadding(dp(8), dp(2), dp(8), dp(2));
            row1.addView(def);
        }
        card.addView(row1);

        // 第二行: model · 状态
        TextView sub = new TextView(this);
        String status = m.isConfigured()
                ? m.getEffectiveModel() + " · 已配置"
                : "未配置 Key";
        sub.setText(m.getProviderDisplayName() + " · " + status + " · 角色: " + m.role);
        sub.setTextColor(getColor(R.color.text_secondary));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, 0);
        card.addView(sub);

        // 第三行: 操作按钮
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(0, dp(8), 0, 0);

        row3.addView(makeSmallBtn("编辑", v -> openEditForm(m.id)));
        row3.addView(makeSmallBtn("测试", v -> {
            Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                com.hermes.android.ai.AiClient client =
                        new com.hermes.android.ai.AiClient(m, "回复 OK");
                com.hermes.android.ai.AiClient.AiResponse resp = client.chat("ping");
                runOnUiThread(() -> Toast.makeText(this,
                        resp.success ? "连接正常" : "失败: " + resp.content,
                        Toast.LENGTH_SHORT).show());
            }).start();
        }));
        if (!m.isDefault) {
            row3.addView(makeSmallBtn("设为默认", v -> {
                registry.setDefault(m.id);
                renderModelList();
                refreshStatus();
            }));
        }
        if (registry.list().size() > 1) {
            row3.addView(makeSmallBtn("删除", v -> {
                registry.delete(m.id);
                renderModelList();
                refreshStatus();
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
            }));
        }
        card.addView(row3);
        return card;
    }

    private Button makeSmallBtn(String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(getColor(R.color.text_secondary));
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setBackground(getDrawable(R.drawable.bg_chip));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setPadding(dp(10), 0, dp(10), 0);
        btn.setOnClickListener(listener);
        return btn;
    }

    /* ── 编辑表单 ── */

    private void openEditForm(String modelId) {
        editingId = modelId;
        if (modelId != null) {
            ModelConfig m = registry.get(modelId);
            if (m == null) return;
            tvEditTitle.setText("编辑模型");
            etName.setText(m.name);
            etBaseUrl.setText(m.baseUrl);
            etApiKey.setText(m.apiKey);
            etModel.setText(m.model);
            // 编辑已有模型: 只定位厂商，不做自动填充
            selectProvider(m.provider);
            for (int i = 0; i < ROLE_NAMES.length; i++) {
                if (ROLE_NAMES[i].equals(m.role)) { spinnerRole.setSelection(i, false); break; }
            }
        } else {
            tvEditTitle.setText("添加模型");
            etName.setText("");
            etBaseUrl.setText("");
            etApiKey.setText("");
            etModel.setText("");
            selectProvider(presets[0].key);
            // 新建表单默认选中第一个厂商，并触发一次自动填充
            applyPreset(0);
            spinnerRole.setSelection(0, false);
        }
        editForm.setVisibility(View.VISIBLE);
        editForm.requestFocus();
    }

    private void closeEditForm() {
        editForm.setVisibility(View.GONE);
        editingId = null;
    }

    private void saveModel() {
        ModelConfig m = editingId != null ? registry.get(editingId) : new ModelConfig();
        if (m == null) return;

        ModelPresets.Preset preset = currentPreset();
        if (preset == null) return;

        m.name = etName.getText().toString().trim();
        m.provider = preset.key;
        m.baseUrl = etBaseUrl.getText().toString().trim();
        m.apiKey = etApiKey.getText().toString().trim();
        m.model = etModel.getText().toString().trim();
        m.role = ROLE_NAMES[spinnerRole.getSelectedItemPosition()];

        if (m.name.isEmpty()) m.name = m.getProviderDisplayName();

        if (editingId != null) {
            registry.update(m);
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        } else {
            registry.add(m);
            Toast.makeText(this, "已添加: " + m.name, Toast.LENGTH_SHORT).show();
        }
        closeEditForm();
        renderModelList();
        refreshStatus();
    }

    private void testModel() {
        ModelPresets.Preset preset = currentPreset();
        if (preset == null) return;

        ModelConfig m = new ModelConfig();
        m.provider = preset.key;
        m.baseUrl = etBaseUrl.getText().toString().trim();
        m.apiKey = etApiKey.getText().toString().trim();
        m.model = etModel.getText().toString().trim();

        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            com.hermes.android.ai.AiClient client =
                    new com.hermes.android.ai.AiClient(m, "回复 OK");
            long start = System.currentTimeMillis();
            com.hermes.android.ai.AiClient.AiResponse resp = client.chat("ping");
            long ms = System.currentTimeMillis() - start;
            runOnUiThread(() -> Toast.makeText(this,
                    resp.success ? "连接正常 · " + ms + "ms" : "失败: " + resp.content,
                    Toast.LENGTH_SHORT).show());
        }).start();
    }

    /* ── 状态 ── */

    private void refreshStatus() {
        List<ModelConfig> models = registry.list();
        ModelConfig def = registry.getDefault();
        String status = models.size() + " 个模型";
        if (def != null) status += " · 默认: " + (def.name.isEmpty() ? def.getProviderDisplayName() : def.name);
        ((TextView) findViewById(R.id.tvSettingsStatus)).setText(status);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
