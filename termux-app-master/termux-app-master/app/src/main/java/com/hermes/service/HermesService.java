package com.hermes.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hermes.receiver.AlarmReceiver;
import com.hermes.bridge.HermesSocketServer;

import org.json.JSONObject;
import com.hermes.ssh.SshManager;
import com.hermes.ui.HermesActivity;
import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Foreground service that bootstraps the Hermes environment in Termux and keeps SSH running.
 */
public class HermesService extends Service {

    public static final String ACTION_START = "com.hermes.service.ACTION_START";
    public static final String ACTION_STOP = "com.hermes.service.ACTION_STOP";

    private static final String LOG_TAG = "HermesService";
    private static final int NOTIFICATION_ID = 1338;
    private static final String CHANNEL_ID = "hermes_service_channel";
    private static final String CHANNEL_NAME = "Hermes Agent";
    /** 完整版 agent 源码+依赖的版本标记；变更会触发设备端重新释放/安装。 */
    private static final String FULL_AGENT_VERSION = "2.0.0";

    private final IBinder mBinder = new LocalBinder();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mIsRunning = false;
    private volatile String mStatusText = "Idle";
    private volatile String mSshAddress = "-";
    private volatile String mSshFingerprint = "-";
    private Thread mSetupThread;
    private SshManager mSshManager;
    private HermesSocketServer mSocketServer;
    private Process mHermesAgentProcess;

    public class LocalBinder extends Binder {
        public HermesService getService() {
            return HermesService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAgent();
        } else {
            startAgent();
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        stopAgent();
        super.onDestroy();
    }

    public void startAgent() {
        synchronized (this) {
            if (mIsRunning) return;
            mIsRunning = true;
            mSetupThread = new Thread(this::runSetup, "HermesSetup");
            mSetupThread.start();
        }
    }

    public void stopAgent() {
        synchronized (this) {
            mIsRunning = false;
            if (mSshManager != null) {
                mSshManager.stop();
                mSshManager = null;
            }
            if (mSocketServer != null) {
                mSocketServer.stop();
                mSocketServer = null;
            }
            stopHermesAgentProcess();
            if (mSetupThread != null) {
                mSetupThread.interrupt();
                mSetupThread = null;
            }
        }
        setStatus(getString(R.string.hermes_status_not_running));
        stopForeground(true);
        stopSelf();
    }

    public boolean isAgentRunning() {
        return mIsRunning;
    }

    /** 进程内直调桥工具（UI 斜杠命令用），绕过 socket 和 LLM。 */
    @NonNull
    public JSONObject callTool(@NonNull String method, @NonNull JSONObject params) {
        try {
            if (mSocketServer == null) {
                return new JSONObject().put("success", false)
                    .put("error", "桥未就绪，请先启动服务");
            }
            return mSocketServer.getBridge().handle(method, params);
        } catch (Exception e) {
            try {
                return new JSONObject().put("success", false)
                    .put("error", e.getMessage());
            } catch (Exception ignored) {
                return new JSONObject();
            }
        }
    }

    public String getStatusText() {
        return mStatusText;
    }

    public String getSshAddress() {
        return mSshAddress;
    }

    public String getSshFingerprint() {
        return mSshFingerprint;
    }

    private void runSetup() {
        try {
            setStatus(getString(R.string.hermes_setup_bootstrap));
            if (!isBootstrapInstalled()) {
                setStatus(getString(R.string.hermes_bootstrap_missing));
                mIsRunning = false;
                updateNotification();
                return;
            }

            setStatus(getString(R.string.hermes_setup_installing));
            ensureHermesConfig();

            // Start the Android bridge socket server early so it is ready when the agent starts.
            mSocketServer = new HermesSocketServer(this);
            mSocketServer.start();

            // 重排重启前未触发的 Hermes 自管闹钟
            AlarmReceiver.rescheduleAll(this);

            // Install the Termux packages needed for the agents and SSH.
            // python3.13/rust/clang 等是完整版 agent 的依赖（轻量 agent 只用 python）。
            if (!runShell("Update packages and install dependencies",
                "pkg update && pkg install -y python openssh python3.13 unzip ripgrep "
                    + "rust clang make cmake pkg-config libffi openssl "
                    + "libjpeg-turbo zlib libpng libtiff libwebp openjpeg littlecms "
                    + "freetype libimagequant libxcb")) {
                Log.w(LOG_TAG, "Package install step failed; continuing anyway");
            }

            // Deploy the lightweight Chinese Hermes agent (fallback mode).
            copyAssetToFile("hermes_agent.py",
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/agent.py");
            // Deploy the full-agent HTTP adapter and its source bundle.
            copyAssetToFile("android_server.py",
                TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes/android_server.py");
            deployFullAgentSource();
            installFullAgentDeps();

            setStatus(getString(R.string.hermes_setup_sshd));
            mSshManager = new SshManager(this);
            mSshManager.start();

            mSshAddress = mSshManager.getAddress();
            mSshFingerprint = mSshManager.getFingerprint();

            setStatus(getString(R.string.hermes_setup_starting_agent));
            startLightAgent();

            setStatus(getString(R.string.hermes_setup_done));
            updateNotification();
        } catch (Exception e) {
            Log.e(LOG_TAG, "Setup failed", e);
            setStatus(getString(R.string.hermes_setup_failed));
        }
    }

    private void startLightAgent() {
        String socketPath = mSocketServer != null ? mSocketServer.getSocketPath() : "";
        String homeDir = TermuxConstants.TERMUX_HOME_DIR_PATH;

        // 完整版依赖就绪则用完整 agent，否则回退轻量 agent。
        boolean fullReady = FULL_AGENT_VERSION.equals(
                readMarker(homeDir + "/.hermes/.pip_install_version"))
            && new File(homeDir + "/.hermes/android_server.py").exists();
        String agentPath = fullReady
            ? homeDir + "/.hermes/android_server.py"
            : homeDir + "/.hermes/agent.py";
        String python = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
            + (fullReady ? "/python3.13" : "/python");
        if (!new File(agentPath).exists()) {
            Log.w(LOG_TAG, "Agent script not found, skipping agent start");
            return;
        }
        Log.i(LOG_TAG, "Starting agent in " + (fullReady ? "FULL" : "LIGHT") + " mode");

        // Start the agent directly with ProcessBuilder so that it is not tied to
        // an AppShell lifecycle that would destroy the process group on exit.
        String logPath = homeDir + "/.hermes/service_agent.log";
        String bashCommand = "exec " + python + " -u " + agentPath + " > " + logPath + " 2>&1";

        ProcessBuilder pb = new ProcessBuilder(
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash", "-c", bashCommand);
        pb.directory(new File(homeDir));
        java.util.Map<String, String> env = pb.environment();
        env.put("HERMES_ANDROID_BRIDGE_SOCKET", socketPath);
        env.put("HERMES_PLUGINS_DEBUG", "1");
        env.put("HOME", homeDir);
        env.put("TMPDIR", TermuxConstants.TERMUX_FILES_DIR_PATH + "/usr/tmp");
        env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + env.get("PATH"));

        try {
            mHermesAgentProcess = pb.start();
            Log.i(LOG_TAG, "Hermes agent process started");
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to start Hermes agent", e);
        }
    }

    private void stopHermesAgentProcess() {
        if (mHermesAgentProcess != null) {
            try {
                mHermesAgentProcess.destroy();
            } catch (Exception e) {
                Log.w(LOG_TAG, "Failed to destroy Hermes agent process", e);
            }
            mHermesAgentProcess = null;
        }
    }

    private boolean isBootstrapInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash").exists();
    }

    /** 释放完整版 agent 源码（zip 打包在 assets，版本不变则跳过）。 */
    private void deployFullAgentSource() {
        String hermesDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes";
        if (FULL_AGENT_VERSION.equals(readMarker(hermesDir + "/.agent_src_version"))
            && new File(hermesDir, "hermes-agent/run_agent.py").exists()) {
            return;
        }
        setStatus("正在释放完整智能体源码…");
        copyAssetToFile("hermes_agent_full.zip", hermesDir + "/hermes_agent_full.zip");
        if (runShell("Extract full agent source",
            "cd " + hermesDir
                + " && rm -rf hermes-agent"
                + " && unzip -q hermes_agent_full.zip"
                + " && rm hermes_agent_full.zip"
                + " && echo " + FULL_AGENT_VERSION + " > .agent_src_version")) {
            Log.i(LOG_TAG, "Full agent source deployed");
        } else {
            Log.w(LOG_TAG, "Full agent source deploy failed; light agent remains available");
        }
    }

    /** 首启安装完整版 agent 的 Python 依赖（耗时较长，仅版本变化时执行）。 */
    private void installFullAgentDeps() {
        String hermesDir = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes";
        if (!new File(hermesDir, "hermes-agent/pyproject.toml").exists()) return;
        if (FULL_AGENT_VERSION.equals(readMarker(hermesDir + "/.pip_install_version"))) return;
        setStatus("首次安装智能体依赖（约 10-30 分钟，请保持网络畅通）…");
        String prefix = TermuxConstants.TERMUX_FILES_DIR_PATH + "/usr";
        // psutil 官方拒绝 Android 平台，先跑上游自带的兼容 shim（给 sdist 打补丁后编译）
        boolean ok = runShell("Install full agent dependencies",
            "export TMPDIR=" + prefix + "/tmp"
                + " && cd " + hermesDir + "/hermes-agent"
                + " && " + prefix + "/bin/python3.13 -m pip install -q setuptools wheel"
                + " -i https://pypi.tuna.tsinghua.edu.cn/simple"
                + " && " + prefix + "/bin/python3.13 scripts/install_psutil_android.py"
                + " && " + prefix + "/bin/python3.13 -m pip install -e '.[termux]'"
                + " -c constraints-termux.txt"
                + " -i https://pypi.tuna.tsinghua.edu.cn/simple"
                + " && echo " + FULL_AGENT_VERSION + " > " + hermesDir + "/.pip_install_version");
        if (ok) {
            Log.i(LOG_TAG, "Full agent dependencies installed");
        } else {
            Log.w(LOG_TAG, "Full agent dependency install failed; light agent remains available");
        }
    }

    private String readMarker(String path) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private void ensureHermesConfig() {
        File hermesDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.hermes");
        //noinspection ResultOfMethodCallIgnored
        hermesDir.mkdirs();
        File configFile = new File(hermesDir, "config.yaml");
        if (!configFile.exists()) {
            copyAssetToFile("hermes_config.yaml", configFile.getAbsolutePath());
        }
        File envFile = new File(hermesDir, ".env");
        if (!envFile.exists()) {
            copyAssetToFile("hermes_env.example", envFile.getAbsolutePath());
        }
        File pluginsDir = new File(hermesDir, "plugins");
        copyAssetDirectory("plugins", pluginsDir.getAbsolutePath());
    }

    private void copyAssetToFile(String assetName, String destPath) {
        File dest = new File(destPath);
        //noinspection ResultOfMethodCallIgnored
        dest.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to copy asset " + assetName, e);
        }
    }

    private void copyAssetDirectory(String assetDir, String destDir) {
        File dest = new File(destDir);
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        try {
            String[] entries = getAssets().list(assetDir);
            if (entries == null) return;
            for (String entry : entries) {
                String assetPath = assetDir + "/" + entry;
                String destPath = destDir + "/" + entry;
                try {
                    String[] subEntries = getAssets().list(assetPath);
                    if (subEntries != null && subEntries.length > 0) {
                        copyAssetDirectory(assetPath, destPath);
                    } else {
                        copyAssetToFile(assetPath, destPath);
                    }
                } catch (IOException e) {
                    copyAssetToFile(assetPath, destPath);
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to copy asset directory " + assetDir, e);
        }
    }

    private boolean runShell(String label, String command) {
        ResultData result = runShellForResult(label, command);
        if (result == null || result.exitCode == null || result.exitCode != 0) {
            if (result != null) {
                Log.e(LOG_TAG, label + " failed: exit=" + result.exitCode
                    + " stdout=" + result.stdout.toString()
                    + " stderr=" + result.stderr.toString());
            } else {
                Log.e(LOG_TAG, label + " failed: AppShell returned null (check bootstrap and executable)");
            }
            return false;
        }
        return true;
    }

    @Nullable
    private ResultData runShellForResult(String label, String command) {
        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());
        executionCommand.executable = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        executionCommand.arguments = new String[]{"-c", command};
        executionCommand.workingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        executionCommand.commandLabel = label;
        executionCommand.setShellCommandShellEnvironment = true;

        AppShell appShell = AppShell.execute(this, executionCommand, null,
            new TermuxShellEnvironment(), null, true);
        if (appShell == null) {
            Log.e(LOG_TAG, "Failed to execute: " + label);
            return null;
        }
        return executionCommand.resultData;
    }

    private void setStatus(final String status) {
        mStatusText = status;
        mHandler.post(this::updateNotification);
    }

    private void startForegroundNotification() {
        setupNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, HermesActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = mIsRunning ? mStatusText : getString(R.string.hermes_status_not_running);

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            CHANNEL_ID, Notification.PRIORITY_LOW,
            getString(R.string.hermes_notification_title), text, null,
            contentIntent, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return new Notification.Builder(this).build();

        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setOngoing(true);

        Intent stopIntent = new Intent(this, HermesService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(android.R.drawable.ic_delete, getString(R.string.hermes_notification_action_stop), stopPendingIntent);

        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationUtils.setupNotificationChannel(this, CHANNEL_ID, CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW);
    }
}
