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
import com.hermes.android.model.ModelConfig;
import com.hermes.android.model.ModelPresets;
import com.hermes.android.model.ModelRegistry;

import java.util.List;

/**
 * 设置页: 多模型管理 + 语言 + 统计。
 * 模型列表 → 点击编辑 → 保存/测试/删除。
 * 厂商数据来自 ModelPresets，选中厂商自动填充 baseUrl / 默认模型。
 */
public class HermesSettingsActivity extends AppCompatActivity {

    private static final String[] ROLE_NAMES = {"通用", "产品", "技术", "数据", "自定义"};
    private static final String[] LANG_NAMES = {"中文", "English"};
    private static final String[] LANG_VALUES = {"zh", "en"};

    private ModelRegistry registry;
    private AiProviderConfig aiConfig;

    private LinearLayout modelListContainer;
    private LinearLayout editForm;
    private TextView tvEditTitle, tvProviderNote;
    private EditText etName, etBaseUrl, etApiKey;
    private AutoCompleteTextView etModel;
    private Spinner spinnerProvider, spinnerRole, spinnerLanguage;
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

        registry = new ModelRegistry(this);
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
        spinnerLanguage = findViewById(R.id.spinnerLanguage);

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
        spinnerLanguage.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, LANG_NAMES));

        // 语言
        String lang = aiConfig.getLanguage();
        for (int i = 0; i < LANG_VALUES.length; i++) {
            if (LANG_VALUES[i].equals(lang)) { spinnerLanguage.setSelection(i, false); break; }
        }
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                aiConfig.setLanguage(LANG_VALUES[pos]);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });

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

        refreshStatus();
        renderModelList();
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
