package com.hermes.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.net.Uri;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hermes.service.HermesService;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher dashboard for the Hermes + Termux integration.
 */
public class HermesActivity extends AppCompatActivity implements ServiceConnection {

    private static final int REQUEST_HERMES_PERMISSIONS = 3000;
    private static final int REQUEST_DISPLAY_OVERLAY = 3001;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 3002;
    private static final int REQUEST_WRITE_SETTINGS = 3003;

    private static final String[] HERMES_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.POST_NOTIFICATIONS
    };

    private HermesService mHermesService;
    private boolean mIsBound = false;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUiUpdater = new Runnable() {
        @Override
        public void run() {
            updateUi();
            mUiHandler.postDelayed(this, 1000);
        }
    };

    private TextView mStatusText;
    private TextView mSshAddress;
    private TextView mSshFingerprint;
    private Button mStartButton;
    private Button mStopButton;
    private Button mTerminalButton;
    private Button mAccessibilityButton;
    private Button mDeviceAdminButton;
    private Button mSettingsButton;
    private RecyclerView mChatRecycler;
    private ChatAdapter mChatAdapter;
    private EditText mChatInput;
    private Button mSendButton;
    private File mChatHistoryFile;

    private final ExecutorService mHttpExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hermes);

        mStatusText = findViewById(R.id.hermes_status_text);
        mSshAddress = findViewById(R.id.hermes_ssh_address);
        mSshFingerprint = findViewById(R.id.hermes_ssh_fingerprint);
        mStartButton = findViewById(R.id.hermes_start_button);
        mStopButton = findViewById(R.id.hermes_stop_button);
        mTerminalButton = findViewById(R.id.hermes_terminal_button);
        mAccessibilityButton = findViewById(R.id.hermes_accessibility_button);
        mDeviceAdminButton = findViewById(R.id.hermes_device_admin_button);
        mSettingsButton = findViewById(R.id.hermes_settings_button);

        mChatRecycler = findViewById(R.id.hermes_chat_recycler);
        mChatInput = findViewById(R.id.hermes_chat_input);
        mSendButton = findViewById(R.id.hermes_send_button);

        mChatAdapter = new ChatAdapter();
        mChatRecycler.setLayoutManager(new LinearLayoutManager(this));
        mChatRecycler.setAdapter(mChatAdapter);

        // 聊天记录持久化：进程被杀/界面重建后重新加载
        mChatHistoryFile = new File(getFilesDir(), "chat_history.jsonl");
        loadChatHistory();

        mStartButton.setOnClickListener(v -> startHermesService());
        mStopButton.setOnClickListener(v -> stopHermesService());
        mTerminalButton.setOnClickListener(v -> openTerminal());
        mAccessibilityButton.setOnClickListener(v -> openAccessibilitySettings());
        mDeviceAdminButton.setOnClickListener(v -> openDeviceAdminSettings());
        mSettingsButton.setOnClickListener(v -> showSettingsDialog());
        mSendButton.setOnClickListener(v -> sendChatMessage());
        mChatInput.setOnEditorActionListener((v, actionId, event) -> {
            sendChatMessage();
            return true;
        });

        requestSpecialPermissions();
        requestHermesPermissions();

        // Ensure Termux bootstrap is extracted before starting the Hermes service.
        TermuxInstaller.setupBootstrapIfNeeded(this, this::startHermesService);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindHermesService();
        mUiHandler.post(mUiUpdater);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mUiHandler.removeCallbacks(mUiUpdater);
        if (mIsBound) {
            unbindService(this);
            mIsBound = false;
            mHermesService = null;
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mHermesService = ((HermesService.LocalBinder) service).getService();
        mIsBound = true;
        updateUi();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mHermesService = null;
        mIsBound = false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_HERMES_PERMISSIONS) {
            requestHermesPermissions();
        }
    }

    private void requestHermesPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PermissionUtils.requestPermissions(this, HERMES_PERMISSIONS, REQUEST_HERMES_PERMISSIONS);
    }

    private void requestSpecialPermissions() {
        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
            PermissionUtils.requestDisplayOverOtherAppsPermission(this, REQUEST_DISPLAY_OVERLAY);
        }
        if (!PermissionUtils.checkIfBatteryOptimizationsDisabled(this)) {
            PermissionUtils.requestDisableBatteryOptimizations(this, REQUEST_BATTERY_OPTIMIZATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_WRITE_SETTINGS);
        }
    }

    private void startHermesService() {
        Intent intent = new Intent(this, HermesService.class);
        intent.setAction(HermesService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindHermesService();
    }

    private void stopHermesService() {
        Intent intent = new Intent(this, HermesService.class);
        intent.setAction(HermesService.ACTION_STOP);
        startService(intent);
    }

    private void bindHermesService() {
        if (mIsBound) return;
        Intent intent = new Intent(this, HermesService.class);
        bindService(intent, this, Context.BIND_AUTO_CREATE);
    }

    private void openTerminal() {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    private void openDeviceAdminSettings() {
        Intent intent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
        startActivity(intent);
    }

    private void updateUi() {
        if (mHermesService != null && mHermesService.isAgentRunning()) {
            mStatusText.setText(getString(R.string.hermes_status_format,
                getString(R.string.hermes_status_running)));
            mSshAddress.setText(getString(R.string.hermes_ssh_address_format,
                mHermesService.getSshAddress()));
            mSshFingerprint.setText(getString(R.string.hermes_ssh_fingerprint_format,
                mHermesService.getSshFingerprint()));
        } else {
            mStatusText.setText(getString(R.string.hermes_status_format,
                getString(R.string.hermes_status_not_running)));
            mSshAddress.setText(getString(R.string.hermes_ssh_address_format,
                getString(R.string.hermes_ssh_not_available)));
            mSshFingerprint.setText("-");
        }
    }

    private void sendChatMessage() {
        String text = mChatInput.getText().toString().trim();
        if (text.isEmpty()) return;

        addChatMessage("你", text);
        mChatInput.setText("");
        hideKeyboard();

        mHttpExecutor.execute(() -> {
            String reply = callAgentChat(text);
            runOnUiThread(() -> addChatMessage("Hermes", reply));
        });
    }

    private void addChatMessage(String sender, String text) {
        mChatAdapter.addMessage(new ChatAdapter.Message(sender, text));
        mChatRecycler.scrollToPosition(mChatAdapter.getItemCount() - 1);
        appendChatHistory(sender, text);
    }

    private void loadChatHistory() {
        if (mChatHistoryFile == null || !mChatHistoryFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(mChatHistoryFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject json = new JSONObject(line);
                mChatAdapter.addMessage(new ChatAdapter.Message(
                    json.optString("sender"), json.optString("text")));
            }
            if (mChatAdapter.getItemCount() > 0) {
                mChatRecycler.scrollToPosition(mChatAdapter.getItemCount() - 1);
            }
        } catch (IOException | JSONException e) {
            Log.e("HermesActivity", "Failed to load chat history", e);
        }
    }

    private void appendChatHistory(String sender, String text) {
        if (mChatHistoryFile == null) return;
        try (FileOutputStream fos = new FileOutputStream(mChatHistoryFile, true)) {
            JSONObject json = new JSONObject();
            json.put("sender", sender);
            json.put("text", text);
            fos.write((json.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Log.e("HermesActivity", "Failed to save chat history", e);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private String callAgentChat(String message) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:18080/chat");
            conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);

            JSONObject payload = new JSONObject();
            payload.put("message", message);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();

            if (code >= 200 && code < 300) {
                JSONObject json = new JSONObject(sb.toString());
                return json.optString("reply", sb.toString());
            } else {
                return "请求失败 (" + code + "): " + sb.toString().trim();
            }
        } catch (IOException | JSONException e) {
            return "调用智能体失败：" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showSettingsDialog() {
        File envFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/.env");
        EnvValues current = readEnvValues(envFile);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        String[] providerIds = getResources().getStringArray(R.array.hermes_provider_ids);
        String[] providerNames = getResources().getStringArray(R.array.hermes_provider_names);

        Spinner providerSpinner = new Spinner(this);
        ArrayAdapter<String> providerAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, providerNames);
        providerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(providerAdapter);

        int selectedIndex = 0;
        for (int i = 0; i < providerIds.length; i++) {
            if (providerIds[i].equals(current.provider)) {
                selectedIndex = i;
                break;
            }
        }
        providerSpinner.setSelection(selectedIndex);
        layout.addView(providerSpinner);

        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint(R.string.hermes_api_key_hint);
        apiKeyInput.setText(current.apiKey);
        layout.addView(apiKeyInput);

        EditText modelInput = new EditText(this);
        modelInput.setHint(R.string.hermes_model_hint);
        modelInput.setText(current.model);
        layout.addView(modelInput);

        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                // 如果模型输入框为空或仍是某个厂商的默认值，则自动填入当前厂商默认模型
                String currentModel = modelInput.getText().toString().trim();
                String defaultModel = getProviderDefaultModel(providerIds[position]);
                if (currentModel.isEmpty() || isKnownDefaultModel(currentModel)) {
                    modelInput.setText(defaultModel);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        new AlertDialog.Builder(this)
            .setTitle(R.string.hermes_settings_title)
            .setView(layout)
            .setPositiveButton(R.string.hermes_save, (dialog, which) -> {
                int pos = providerSpinner.getSelectedItemPosition();
                saveEnvValues(envFile,
                    providerIds[pos],
                    apiKeyInput.getText().toString().trim(),
                    modelInput.getText().toString().trim());
                restartHermesService();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private String getProviderDefaultModel(String providerId) {
        switch (providerId) {
            case "deepseek": return "deepseek-v4-flash";
            case "kimi": return "moonshot-v1-8k";
            case "qwen": return "qwen-plus";
            case "zhipu": return "glm-4-flash";
            case "doubao": return "doubao-pro-32k";
            case "yi": return "yi-lightning";
            case "openrouter": return "openai/gpt-4o-mini";
            default: return "";
        }
    }

    private boolean isKnownDefaultModel(String model) {
        return model.equals("deepseek-v4-flash")
            || model.equals("moonshot-v1-8k")
            || model.equals("qwen-plus")
            || model.equals("glm-4-flash")
            || model.equals("doubao-pro-32k")
            || model.equals("yi-lightning")
            || model.equals("openai/gpt-4o-mini")
            || model.equals("deepseek-chat")
            || model.equals("your_key_here");
    }

    private static class EnvValues {
        String provider = "";
        String apiKey = "";
        String model = "";
    }

    private EnvValues readEnvValues(File envFile) {
        EnvValues values = new EnvValues();
        if (!envFile.exists()) return values;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(envFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("PROVIDER=")) {
                    values.provider = line.substring("PROVIDER=".length());
                } else if (line.startsWith("API_KEY=")) {
                    values.apiKey = line.substring("API_KEY=".length());
                } else if (line.startsWith("MODEL=")) {
                    values.model = line.substring("MODEL=".length());
                } else if (line.startsWith("OPENROUTER_API_KEY=") && values.apiKey.isEmpty()) {
                    values.apiKey = line.substring("OPENROUTER_API_KEY=".length());
                } else if (line.startsWith("OPENROUTER_MODEL=") && values.model.isEmpty()) {
                    values.model = line.substring("OPENROUTER_MODEL=".length());
                }
            }
        } catch (IOException e) {
            Log.e("HermesActivity", "Failed to read .env", e);
        }
        if (values.provider.isEmpty() && !values.apiKey.isEmpty()) {
            values.provider = "openrouter";
        }
        return values;
    }

    private void saveEnvValues(File envFile, String provider, String apiKey, String model) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean hasProvider = false;
        boolean hasApiKey = false;
        boolean hasModel = false;
        if (envFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(envFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("PROVIDER=")) {
                        lines.add("PROVIDER=" + provider);
                        hasProvider = true;
                    } else if (line.startsWith("API_KEY=")) {
                        lines.add("API_KEY=" + apiKey);
                        hasApiKey = true;
                    } else if (line.startsWith("MODEL=")) {
                        lines.add("MODEL=" + model);
                        hasModel = true;
                    } else if (line.startsWith("OPENROUTER_API_KEY=")
                            || line.startsWith("OPENROUTER_MODEL=")
                            || line.startsWith("OPENROUTER_BASE_URL=")) {
                        // 旧版配置不再保留，由新版接管
                    } else {
                        lines.add(line);
                    }
                }
            } catch (IOException e) {
                Log.e("HermesActivity", "Failed to read .env for update", e);
            }
        }
        if (!hasProvider) lines.add("PROVIDER=" + provider);
        if (!hasApiKey) lines.add("API_KEY=" + apiKey);
        if (!hasModel) lines.add("MODEL=" + model);
        try (FileOutputStream fos = new FileOutputStream(envFile)) {
            for (String line : lines) {
                fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.e("HermesActivity", "Failed to write .env", e);
        }
    }

    private void restartHermesService() {
        stopHermesService();
        mUiHandler.postDelayed(this::startHermesService, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHttpExecutor.shutdown();
    }
}
