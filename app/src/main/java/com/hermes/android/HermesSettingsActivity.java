package com.hermes.android;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hermes.android.ai.AiClient;
import com.hermes.android.ai.AiProviderConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI settings screen: provider / base URL / key / model / system prompt.
 */
public class HermesSettingsActivity extends AppCompatActivity {

    private AiProviderConfig config;
    private StatsCollector stats;
    private Spinner spinnerProvider;
    private Spinner spinnerLanguage;
    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etSystemPrompt;
    private Switch switchEnabled;
    private TextView tvStatus;
    private Button btnSave;
    private Button btnTest;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final String[] PROVIDER_VALUES = {
            AiProviderConfig.PROVIDER_DEEPSEEK,
            AiProviderConfig.PROVIDER_OPENAI,
            AiProviderConfig.PROVIDER_QWEN,
            AiProviderConfig.PROVIDER_OLLAMA
    };

    private static final String[] PROVIDER_LABELS = {
            "DeepSeek",
            "OpenAI 兼容",
            "通义千问 (Qwen)",
            "本地 Ollama"
    };

    private static final String[] LANG_VALUES = {"zh", "en"};
    private static final String[] LANG_LABELS = {"中文", "English"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes_settings);

        config = new AiProviderConfig(this);
        stats = new StatsCollector(this);

        spinnerProvider = findViewById(R.id.spinnerProvider);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        etSystemPrompt = findViewById(R.id.etSystemPrompt);
        switchEnabled = findViewById(R.id.switchEnabled);
        tvStatus = findViewById(R.id.tvSettingsStatus);
        btnSave = findViewById(R.id.btnSave);
        btnTest = findViewById(R.id.btnTest);
        Button btnBack = findViewById(R.id.btnBack);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PROVIDER_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProvider.setAdapter(adapter);

        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, LANG_LABELS);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);

        loadValues();

        spinnerProvider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                applyPresetToFields(PROVIDER_VALUES[position]);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        btnSave.setOnClickListener(v -> {
            saveValues();
            Toast.makeText(this, "已保存 AI 设置", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        btnTest.setOnClickListener(v -> testConnection());

        findViewById(R.id.btnClearKey).setOnClickListener(v -> {
            etApiKey.setText("");
            etApiKey.requestFocus();
        });

        // TELEMETRY: 统计开关 + 预览
        Switch switchStats = findViewById(R.id.switchStats);
        switchStats.setChecked(stats.isEnabled());
        switchStats.setOnCheckedChangeListener((v, checked) -> stats.setEnabled(checked));
        findViewById(R.id.btnStatsPreview).setOnClickListener(v -> {
            String json = stats.getPreviewJson();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("即将上报的数据")
                    .setMessage(json)
                    .setPositiveButton("关闭", null)
                    .show();
        });

        btnBack.setOnClickListener(v -> finish());

        refreshStatus();
    }

    private void loadValues() {
        int selected = 0;
        String provider = config.getProvider();
        for (int i = 0; i < PROVIDER_VALUES.length; i++) {
            if (PROVIDER_VALUES[i].equals(provider)) {
                selected = i;
                break;
            }
        }
        spinnerProvider.setSelection(selected, false);

        etBaseUrl.setText(readCustomOrNull("base_url"));
        etApiKey.setText(config.getApiKey());
        etModel.setText(readCustomOrNull("model"));
        etSystemPrompt.setText(config.getSystemPrompt());
        switchEnabled.setChecked(config.isAiEnabled());
        // 语言
        String lang = config.getLanguage();
        for (int i = 0; i < LANG_VALUES.length; i++) {
            if (LANG_VALUES[i].equals(lang)) { spinnerLanguage.setSelection(i, false); break; }
        }
    }

    private String readCustomOrNull(String key) {
        // AiProviderConfig returns defaults when custom is empty. For UI we want raw custom value.
        android.content.SharedPreferences prefs = getSharedPreferences("hermes_ai_prefs", MODE_PRIVATE);
        return prefs.getString(key, "");
    }

    private void applyPresetToFields(String provider) {
        if (etBaseUrl != null) etBaseUrl.setHint(defaultBaseUrl(provider));
        if (etModel != null) etModel.setHint(defaultModel(provider));
        boolean needsKey = !AiProviderConfig.PROVIDER_OLLAMA.equals(provider);
        etApiKey.setEnabled(true);
        etApiKey.setHint(needsKey ? "填写 API Key" : "Ollama 通常无需 Key");
    }

    private String defaultBaseUrl(String provider) {
        switch (provider) {
            case AiProviderConfig.PROVIDER_OPENAI:
                return "https://api.openai.com/v1";
            case AiProviderConfig.PROVIDER_QWEN:
                return "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case AiProviderConfig.PROVIDER_OLLAMA:
                return "http://192.168.1.100:11434/v1";
            case AiProviderConfig.PROVIDER_DEEPSEEK:
            default:
                return "https://api.deepseek.com/v1";
        }
    }

    private String defaultModel(String provider) {
        switch (provider) {
            case AiProviderConfig.PROVIDER_OPENAI:
                return "gpt-4o-mini";
            case AiProviderConfig.PROVIDER_QWEN:
                return "qwen-plus";
            case AiProviderConfig.PROVIDER_OLLAMA:
                return "llama3";
            case AiProviderConfig.PROVIDER_DEEPSEEK:
            default:
                return "deepseek-v4-flash";
        }
    }

    private void saveValues() {
        int pos = spinnerProvider.getSelectedItemPosition();
        if (pos < 0) pos = 0;
        config.setProvider(PROVIDER_VALUES[pos]);
        config.setBaseUrl(etBaseUrl.getText().toString());
        config.setApiKey(etApiKey.getText().toString());
        config.setModel(etModel.getText().toString());
        config.setSystemPrompt(etSystemPrompt.getText().toString());
        config.setAiEnabled(switchEnabled.isChecked());
        int langPos = spinnerLanguage.getSelectedItemPosition();
        config.setLanguage(LANG_VALUES[langPos < 0 ? 0 : langPos]);
    }

    private void refreshStatus() {
        tvStatus.setText(config.getStatusSummary());
    }

    private void testConnection() {
        saveValues();
        refreshStatus();

        if (!config.isConfigured()) {
            Toast.makeText(this, "请先填写 API Key / 地址", Toast.LENGTH_SHORT).show();
            return;
        }

        btnTest.setEnabled(false);
        tvStatus.setText("测试中…");

        executor.execute(() -> {
            AiClient client = new AiClient(config);
            AiClient.AiResponse resp = client.chat("请只回复：OK");
            uiHandler.post(() -> {
                btnTest.setEnabled(true);
                refreshStatus();
                if (resp.success) {
                    Toast.makeText(this, "✅ 连接成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "❌ " + resp.content, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
