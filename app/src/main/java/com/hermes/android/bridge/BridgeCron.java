package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.cron.CronManager;

/**
 * P1-1: Cron Bridge — 定时任务管理
 */
public class BridgeCron extends BaseBridge {

    private final CronManager cronManager;

    public BridgeCron(HermesActivity activity, CronManager cronManager) {
        super(activity);
        this.cronManager = cronManager;
    }

    public String listCronJobs() { return cronManager.listJobsJson(); }
    public String createCronJob(String name, String cronExpr, String command) {
        return cronManager.createJob(name, cronExpr, command);
    }
    public String toggleCronJob(String jobId, boolean enabled) {
        return cronManager.toggleJob(jobId, enabled);
    }
    public String deleteCronJob(String jobId) { return cronManager.deleteJob(jobId); }
}
