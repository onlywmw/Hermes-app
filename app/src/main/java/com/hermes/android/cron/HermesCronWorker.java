package com.hermes.android.cron;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hermes.android.CapabilityExecutor;
import com.hermes.android.CommandResult;
import com.hermes.android.IntentParser;
import com.hermes.android.ParsedCommand;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * P1-6: WorkManager Worker, 执行 Cron 任务。
 * 从 SharedPreferences 读取任务配置, 执行设备指令, 更新状态。
 * 安全: Cron 只能执行白名单内的设备能力 (DESIGN_OPTIMIZE §5.5)。
 */
public class HermesCronWorker extends Worker {

    private static final String TAG = "MOVCronWorker";

    /** Cron 允许的 action 白名单 — 禁止 input/shell/call/screen/app/contacts/sms/location */
    private static final Set<String> ALLOWED_ACTIONS = new HashSet<>(Arrays.asList(
        "help", "torch.on", "torch.off",
        "battery.status", "system.info",
        "brightness.get", "brightness.set",
        "volume.get", "volume.set",
        "wifi.status", "vibrate",
        "tts.speak", "toast", "notification.post",
        "clipboard.get", "clipboard.set",
        "network.info", "process.list",
        "file.ls", "file.write", "file.read", "file.delete", "file.mkdir",
        "http.get"
    ));

    public HermesCronWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences("hermes_cron_jobs", Context.MODE_PRIVATE);

        try {
            String jobsJson = prefs.getString("jobs_json", "[]");
            JSONArray jobs = new JSONArray(jobsJson);

            // 从 tag 中提取 job id
            String jobId = null;
            for (String tag : getTags()) {
                if (tag.startsWith("job_")) {
                    jobId = tag.substring(4);
                    break;
                }
            }
            if (jobId == null) return Result.failure();

            // 找到对应任务
            JSONObject targetJob = null;
            int targetIdx = -1;
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject job = jobs.getJSONObject(i);
                if (jobId.equals(job.getString("id"))) {
                    targetJob = job;
                    targetIdx = i;
                    break;
                }
            }
            if (targetJob == null || !targetJob.getBoolean("enabled")) {
                return Result.success();
            }

            // 执行指令
            String command = targetJob.getString("command");
            IntentParser parser = new IntentParser();
            CapabilityExecutor executor = new CapabilityExecutor();
            executor.init(ctx);

            ParsedCommand cmd = parser.parse(command);
            String status;
            if (cmd == null) {
                status = "FAIL: 未识别指令";
            } else if (cmd.isError()) {
                status = "FAIL: " + cmd.getError();
            } else if (!ALLOWED_ACTIONS.contains(cmd.getCapability())) {
                status = "BLOCKED: Cron 不允许执行 " + cmd.getCapability() + " (白名单外)";
                Log.w(TAG, "cron blocked action: " + cmd.getCapability());
            } else {
                CommandResult result = executor.execute(ctx, cmd);
                status = result.isSuccess() ? "OK" : "FAIL: " + result.getMessage();
            }

            // 更新任务状态
            targetJob.put("lastRun", System.currentTimeMillis());
            targetJob.put("lastStatus", status);
            jobs.put(targetIdx, targetJob);
            prefs.edit().putString("jobs_json", jobs.toString()).apply();

            Log.i(TAG, "cron job " + jobId + " executed: " + status);

            // TELEMETRY: Cron 执行计数
            try {
                new com.hermes.android.StatsCollector(ctx).recordCronExecuted();
            } catch (Exception ignored) {}

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "cron worker error: " + e.getMessage());
            return Result.retry();
        }
    }
}
