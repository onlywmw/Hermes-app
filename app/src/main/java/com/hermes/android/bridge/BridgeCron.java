package com.hermes.android.bridge;

import com.hermes.android.HermesActivity;
import com.hermes.android.cron.CronManager;

/**
 * 定时任务 Bridge
 */
public class BridgeCron extends BaseBridge {

    private final CronManager cron;

    public BridgeCron(HermesActivity activity) {
        super(activity);
        this.cron = activity.getCronManager();
    }

    public String listCronJobs() { return cron.listJobsJson(); }
    public String createCronJob(String name, String cronExpr, String command) { return cron.createJob(name, cronExpr, command); }
    public String toggleCronJob(String jobId, boolean enabled) { return cron.toggleJob(jobId, enabled); }
    public String deleteCronJob(String jobId) { return cron.deleteJob(jobId); }
}
