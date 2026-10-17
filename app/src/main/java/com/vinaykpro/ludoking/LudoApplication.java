package com.vinaykpro.ludoking;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;

import com.vinaykpro.ludoking.network.LudoHostManager;

/**
 * Custom Application class for Ludo King.
 * Owns application-scoped resources including the LudoHostManager foundation.
 */
public class LudoApplication extends Application {

    private static final String TAG = "LudoApplication";
    private static LudoApplication sInstance;
    private LudoHostManager hostManager;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "LudoApplication initialized");

        // Initialize application-scoped host manager and game bridge
        hostManager = LudoHostManager.getInstance(this);

        // Auto-start host server in background if enabled
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean autoStart = prefs.getBoolean("network_host_autostart", true);
        if (autoStart) {
            Log.i(TAG, "Auto-starting Ludo Host server on port " + LudoHostManager.DEFAULT_PORT);
            hostManager.startHost();
        }
    }

    @NonNull
    public static LudoApplication getInstance() {
        return sInstance;
    }

    @NonNull
    public LudoHostManager getHostManager() {
        if (hostManager == null) {
            hostManager = LudoHostManager.getInstance(this);
        }
        return hostManager;
    }
}
