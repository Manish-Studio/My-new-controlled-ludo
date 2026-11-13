package com.vinaykpro.ludoking.network;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import com.example.R;

import java.util.List;

/**
 * Material 3 Dialog for managing Host Network & Controller status.
 *
 * Controls & Displays:
 * - Live Server Status (LISTENING, STOPPED, STARTING, STOPPING, ERROR).
 * - Host IP Address, Port, and NSD discovery service details.
 * - Connected Controller clients count and IDs.
 * - Active pending dice commands queued by the Controller.
 * - Server Start, Stop, and Restart actions.
 * - Auto-start preference toggle.
 * - Unsubscribes all listeners on dismiss to prevent memory leaks.
 */
public class NetworkSettingsDialog extends Dialog {

    private final Context context;
    private final LudoHostManager hostManager;
    private final LudoGameBridge gameBridge;
    private final SharedPreferences prefs;

    private View statusDot;
    private TextView statusText;
    private TextView hostIpText;
    private TextView nsdInfoText;
    private TextView clientCountText;
    private TextView clientDetailsText;
    private TextView pendingNumbersText;
    private SwitchCompat autostartSwitch;
    private Button startBtn;
    private Button stopBtn;
    private Button restartBtn;
    private Button clearPendingBtn;
    private ImageView closeBtn;

    private final LudoHostManager.StateListener stateListener = new LudoHostManager.StateListener() {
        @Override
        public void onStateChanged(@NonNull LudoHostManager.ServerState newState, @Nullable String message) {
            updateUi();
        }
    };

    private final LudoHostManager.ClientListener clientListener = new LudoHostManager.ClientListener() {
        @Override
        public void onClientCountChanged(int activeCount, @NonNull List<String> clientIds) {
            updateUi();
        }
    };

    private final PendingNumberStore.OnPendingChangeListener pendingListener = new PendingNumberStore.OnPendingChangeListener() {
        @Override
        public void onPendingChanged(int totalPending, @NonNull List<PendingNumberStore.PendingCommand> currentCommands) {
            updateUi();
        }
    };

    public NetworkSettingsDialog(@NonNull Context context) {
        super(context);
        this.context = context;
        this.hostManager = LudoHostManager.getInstance(context);
        this.gameBridge = LudoGameBridge.getInstance(context);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_network_settings);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(
                    (int) (context.getResources().getDisplayMetrics().widthPixels * 0.90),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        bindViews();
        setupListeners();
        updateUi();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.networkStatusDot);
        statusText = findViewById(R.id.networkStatusText);
        hostIpText = findViewById(R.id.networkHostIpText);
        nsdInfoText = findViewById(R.id.networkNsdInfoText);
        clientCountText = findViewById(R.id.networkClientCountText);
        clientDetailsText = findViewById(R.id.networkClientDetailsText);
        pendingNumbersText = findViewById(R.id.networkPendingNumbersText);
        autostartSwitch = findViewById(R.id.networkAutostartSwitch);
        startBtn = findViewById(R.id.networkStartBtn);
        stopBtn = findViewById(R.id.networkStopBtn);
        restartBtn = findViewById(R.id.networkRestartBtn);
        clearPendingBtn = findViewById(R.id.networkClearPendingBtn);
        closeBtn = findViewById(R.id.dialogCloseBtn);

        boolean autostart = prefs.getBoolean("network_host_autostart", true);
        autostartSwitch.setChecked(autostart);
    }

    private void setupListeners() {
        closeBtn.setOnClickListener(v -> dismiss());

        startBtn.setOnClickListener(v -> {
            hostManager.startHost();
            updateUi();
        });

        stopBtn.setOnClickListener(v -> {
            hostManager.stopHost();
            updateUi();
        });

        restartBtn.setOnClickListener(v -> {
            hostManager.restartHost();
            updateUi();
        });

        clearPendingBtn.setOnClickListener(v -> {
            gameBridge.getPendingNumberStore().clear();
            updateUi();
        });

        autostartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean("network_host_autostart", isChecked).apply();
        });

        hostManager.registerStateListener(stateListener);
        hostManager.registerClientListener(clientListener);
        gameBridge.getPendingNumberStore().addListener(pendingListener);
    }

    private void updateUi() {
        if (statusText == null) return;

        LudoHostManager.ServerState state = hostManager.getState();
        boolean cheatingBlocked = com.vinaykpro.ludoking.cheating.PlayerCheatingManager.getInstance().isAnyPlayerSelected();
        statusText.setText(cheatingBlocked ? state.name() + " (Cheating Active)" : state.name());

        switch (state) {
            case LISTENING:
                statusDot.setBackgroundColor(Color.parseColor("#4CAF50"));
                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                restartBtn.setEnabled(!cheatingBlocked);
                break;
            case STOPPED:
                statusDot.setBackgroundColor(Color.parseColor("#9E9E9E"));
                startBtn.setEnabled(!cheatingBlocked);
                stopBtn.setEnabled(false);
                restartBtn.setEnabled(!cheatingBlocked);
                break;
            case STARTING:
            case STOPPING:
                statusDot.setBackgroundColor(Color.parseColor("#FFA000"));
                startBtn.setEnabled(false);
                stopBtn.setEnabled(false);
                restartBtn.setEnabled(false);
                break;
            case ERROR:
                statusDot.setBackgroundColor(Color.parseColor("#F44336"));
                startBtn.setEnabled(!cheatingBlocked);
                stopBtn.setEnabled(false);
                restartBtn.setEnabled(!cheatingBlocked);
                break;
        }

        String ip = LudoNsdHelper.getLocalIpAddress(context);
        hostIpText.setText("Host IP: " + ip + ":" + hostManager.getConfiguredPort());

        boolean nsdActive = hostManager.getNsdHelper().isRegistered();
        nsdInfoText.setText("NSD: " + LudoProtocol.NSD_SERVICE_NAME + " (" + LudoProtocol.NSD_SERVICE_TYPE + ")" + (nsdActive ? " [Active]" : ""));

        int clientCount = hostManager.getClientCount();
        clientCountText.setText("Connected Controllers: " + clientCount);

        if (clientCount > 0) {
            List<String> clients = hostManager.getConnectedClientIds();
            StringBuilder sb = new StringBuilder("Active: ");
            for (int i = 0; i < clients.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(clients.get(i));
            }
            clientDetailsText.setText(sb.toString());
        } else {
            clientDetailsText.setText("Waiting for Controller on local Wi-Fi or Hotspot...");
        }

        List<PendingNumberStore.PendingCommand> pending = gameBridge.getPendingNumberStore().getAllPending();
        if (pending.isEmpty()) {
            pendingNumbersText.setText("No pending numbers (natural dice rolls active)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (PendingNumberStore.PendingCommand cmd : pending) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("• ").append(cmd.getBoxId())
                  .append(" (Ch ").append(cmd.getChannel()).append("): ")
                  .append("Value ").append(cmd.getValue());
            }
            pendingNumbersText.setText(sb.toString());
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        hostManager.unregisterStateListener(stateListener);
        hostManager.unregisterClientListener(clientListener);
        gameBridge.getPendingNumberStore().removeListener(pendingListener);
    }
}
