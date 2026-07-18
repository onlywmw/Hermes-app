package com.hermes.worker;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.hermes.service.HermesService;

/**
 * WorkManager worker that restarts the Hermes service if it has been killed.
 * This is a stub for Milestone 1; actual keep-alive logic will be expanded later.
 */
public class KeepAliveWorker extends Worker {

    public KeepAliveWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Intent intent = new Intent(context, HermesService.class);
        intent.setAction(HermesService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        return Result.success();
    }
}
