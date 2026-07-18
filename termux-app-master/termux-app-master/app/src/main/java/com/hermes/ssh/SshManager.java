package com.hermes.ssh;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.result.ResultData;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

/**
 * Manages the Termux OpenSSH server on port 8022.
 */
public class SshManager {

    private static final String LOG_TAG = "SshManager";
    private static final int SSH_PORT = 8022;
    private static final long MONITOR_INTERVAL_MS = 10000L;

    private final Context mContext;
    private final String mHomeDir;
    private final String mSshDir;
    private final String mConfigPath;
    private final Object mLock = new Object();

    private volatile boolean mRunning = false;
    private volatile String mAddress = "-";
    private volatile String mFingerprint = "-";
    private Thread mMonitorThread;

    public SshManager(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mHomeDir = TermuxConstants.TERMUX_HOME_DIR_PATH;
        mSshDir = mHomeDir + "/.ssh";
        mConfigPath = mSshDir + "/sshd_config";
    }

    /**
     * Generates host keys if missing, writes sshd_config and starts sshd.
     * This method blocks until the initial start attempt completes.
     */
    public void start() {
        synchronized (mLock) {
            mRunning = true;
        }

        ensureSshDirectory();
        generateHostKeysIfNeeded();
        writeSshdConfig();
        startSshd();
        updateAddressAndFingerprint();

        if (mMonitorThread == null || !mMonitorThread.isAlive()) {
            mMonitorThread = new Thread(this::monitorLoop, "HermesSshMonitor");
            mMonitorThread.start();
        }
    }

    /**
     * Stops the monitor thread. The sshd process itself is left running so that remote
     * sessions are not interrupted unless the whole service is destroyed.
     */
    public void stop() {
        synchronized (mLock) {
            mRunning = false;
        }
        if (mMonitorThread != null) {
            mMonitorThread.interrupt();
            mMonitorThread = null;
        }
    }

    public boolean isRunning() {
        return mRunning;
    }

    public String getAddress() {
        return mAddress;
    }

    public String getFingerprint() {
        return mFingerprint;
    }

    private void ensureSshDirectory() {
        File dir = new File(mSshDir);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        chmod(dir.getAbsolutePath(), 0700);
    }

    private void generateHostKeysIfNeeded() {
        String ed25519Key = mSshDir + "/ssh_host_ed25519_key";
        if (!new File(ed25519Key).exists()) {
            runShell("Generate ED25519 host key",
                "ssh-keygen -t ed25519 -f '" + ed25519Key + "' -N '' -C hermes");
        }
        String rsaKey = mSshDir + "/ssh_host_rsa_key";
        if (!new File(rsaKey).exists()) {
            runShell("Generate RSA host key",
                "ssh-keygen -t rsa -b 4096 -f '" + rsaKey + "' -N '' -C hermes");
        }
    }

    private void writeSshdConfig() {
        String config = "Port " + SSH_PORT + "\n" +
            "PasswordAuthentication no\n" +
            "PubkeyAuthentication yes\n" +
            "PermitRootLogin no\n" +
            "HostKey " + mSshDir + "/ssh_host_ed25519_key\n" +
            "HostKey " + mSshDir + "/ssh_host_rsa_key\n" +
            "PidFile " + mSshDir + "/sshd.pid\n";
        try {
            File file = new File(mConfigPath);
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(config.getBytes(StandardCharsets.UTF_8));
            }
            chmod(file.getAbsolutePath(), 0600);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to write sshd_config", e);
        }
    }

    private void startSshd() {
        if (isSshdRunning()) {
            Log.i(LOG_TAG, "sshd is already running");
            return;
        }
        runShell("Start sshd",
            "sshd -p " + SSH_PORT + " -f '" + mConfigPath + "'");
    }

    private void monitorLoop() {
        while (true) {
            synchronized (mLock) {
                if (!mRunning) break;
            }
            try {
                Thread.sleep(MONITOR_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!isSshdRunning()) {
                Log.w(LOG_TAG, "sshd not running, restarting");
                startSshd();
            }
            updateAddressAndFingerprint();
        }
    }

    private boolean isSshdRunning() {
        ResultData result = runShellForResult("Check sshd",
            "pgrep -x sshd");
        return result != null && result.exitCode != null && result.exitCode == 0
            && !result.stdout.toString().trim().isEmpty();
    }

    private void updateAddressAndFingerprint() {
        String ip = getDeviceIp();
        if (ip != null && !ip.isEmpty()) {
            mAddress = ip + ":" + SSH_PORT;
        } else {
            mAddress = "0.0.0.0:" + SSH_PORT;
        }

        String pubKey = mSshDir + "/ssh_host_ed25519_key.pub";
        ResultData result = runShellForResult("Get SSH fingerprint",
            "ssh-keygen -lf '" + pubKey + "'");
        if (result != null && result.exitCode != null && result.exitCode == 0) {
            String line = result.stdout.toString().trim();
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                mFingerprint = parts[1];
            }
        }
    }

    @Nullable
    private String getDeviceIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to get device IP", e);
        }
        return null;
    }

    private boolean runShell(String label, String command) {
        ResultData result = runShellForResult(label, command);
        if (result == null || result.exitCode == null || result.exitCode != 0) {
            if (result != null) {
                Log.e(LOG_TAG, label + " failed: exit=" + result.exitCode
                    + " stdout=" + result.stdout.toString()
                    + " stderr=" + result.stderr.toString());
            } else {
                Log.e(LOG_TAG, label + " failed: AppShell returned null");
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
        executionCommand.workingDirectory = mHomeDir;
        executionCommand.commandLabel = label;
        executionCommand.setShellCommandShellEnvironment = true;

        AppShell appShell = AppShell.execute(mContext, executionCommand, null,
            new TermuxShellEnvironment(), null, true);
        if (appShell == null) {
            Log.e(LOG_TAG, "Failed to execute: " + label);
            return null;
        }
        return executionCommand.resultData;
    }

    private void chmod(String path, int mode) {
        try {
            Os.chmod(path, mode);
        } catch (ErrnoException e) {
            Log.w(LOG_TAG, "Failed to chmod " + path + ": " + e.getMessage());
        }
    }
}
