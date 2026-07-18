package com.hermes.bridge;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JSON-RPC server that listens on localhost TCP and dispatches Android tool
 * calls from the Hermes Python process.
 */
public class HermesSocketServer {

    private static final String LOG_TAG = "HermesSocketServer";

    private final Context mContext;
    private final AndroidToolBridge mBridge;
    private final AtomicBoolean mRunning = new AtomicBoolean(false);
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private Thread mListenerThread;
    private ServerSocket mServerSocket;
    private String mSocketPath;

    public HermesSocketServer(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mBridge = new AndroidToolBridge(mContext);
    }

    /**
     * Start the server. First tries a Unix domain socket at the configured path;
     * falls back to localhost TCP if Unix sockets are unavailable on this Android
     * version or fail to bind.
     */
    public void start() {
        if (mRunning.getAndSet(true)) return;

        mListenerThread = new Thread(this::listenLoop, "HermesSocketServer");
        mListenerThread.start();
    }

    public void stop() {
        mRunning.set(false);
        ServerSocket socket = mServerSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        if (mListenerThread != null) {
            mListenerThread.interrupt();
            mListenerThread = null;
        }
        mExecutor.shutdownNow();
    }

    public String getSocketPath() {
        return mSocketPath;
    }

    private void listenLoop() {
        // Use localhost TCP for maximum compatibility. Termux Python can connect to
        // 127.0.0.1 without dealing with Android abstract Unix socket namespaces.
        try {
            mServerSocket = new ServerSocket(18081);
            mSocketPath = "tcp:127.0.0.1:18081";
            Log.i(LOG_TAG, "Listening on TCP: " + mSocketPath);
            while (mRunning.get()) {
                Socket client = mServerSocket.accept();
                mExecutor.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (mRunning.get()) {
                Log.e(LOG_TAG, "TCP server failed", e);
            }
        } finally {
            mServerSocket = null;
        }
    }

    private void handleClient(Socket client) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String response = handleRequest(line);
                writer.println(response);
            }
        } catch (IOException e) {
            Log.w(LOG_TAG, "Client disconnected", e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private String handleRequest(String line) {
        JSONObject response = new JSONObject();
        try {
            JSONObject request = new JSONObject(line);
            Object id = request.opt("id");
            response.put("jsonrpc", "2.0");
            if (id != null) response.put("id", id);

            String method = request.optString("method", "");
            JSONObject params = request.optJSONObject("params");
            if (params == null) params = new JSONObject();

            JSONObject result = mBridge.handle(method, params);
            response.put("result", result);
        } catch (JSONException e) {
            try {
                response.put("error", new JSONObject()
                    .put("code", -32700)
                    .put("message", "Parse error: " + e.getMessage()));
            } catch (JSONException ignored) {
            }
        } catch (Exception e) {
            try {
                response.put("error", new JSONObject()
                    .put("code", -32603)
                    .put("message", "Internal error: " + e.getMessage()));
            } catch (JSONException ignored) {
            }
        }
        return response.toString();
    }
}
