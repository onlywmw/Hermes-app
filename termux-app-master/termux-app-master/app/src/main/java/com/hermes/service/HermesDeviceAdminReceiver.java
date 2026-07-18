package com.hermes.service;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Device admin receiver that allows Hermes to perform device-administrator
 * actions such as locking the screen or wiping data (when explicitly enabled
 * by the user in Android Settings).
 */
public class HermesDeviceAdminReceiver extends DeviceAdminReceiver {

    private static final String LOG_TAG = "HermesDeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(LOG_TAG, "Device admin enabled");
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(LOG_TAG, "Device admin disabled");
    }
}
