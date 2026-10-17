package com.vinaykpro.ludoking.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles Network Service Discovery (NSD / mDNS) for the Ludo Host.
 *
 * Responsibilities:
 * - Advertises "LudoHost" service under "_ludo._tcp." on the local Wi-Fi or hotspot.
 * - Manages WifiManager.MulticastLock to permit mDNS packet delivery on Android devices.
 * - Thread-safe registration and unregistration preventing double-register or unregister crashes.
 * - Observable listener for discovery status.
 * - Local IP resolution for Wi-Fi and Portable Hotspot modes.
 */
public class LudoNsdHelper {
    private static final String TAG = "LudoNsdHelper";

    public interface NsdStatusListener {
        void onNsdRegistered(@NonNull String serviceName, int port);
        void onNsdUnregistered();
        void onNsdError(@NonNull String error, int errorCode);
    }

    private final Context appContext;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<NsdStatusListener> statusListeners = new CopyOnWriteArrayList<>();

    private WifiManager.MulticastLock multicastLock;
    private NsdManager.RegistrationListener registrationListener;
    private final AtomicBoolean isRegistered = new AtomicBoolean(false);
    private final AtomicBoolean isRegistering = new AtomicBoolean(false);
    private volatile String currentServiceName = LudoProtocol.NSD_SERVICE_NAME;
    private volatile int currentPort = LudoProtocol.DEFAULT_PORT;

    public LudoNsdHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Registers the NSD service on the local network.
     */
    public synchronized void registerService(int port) {
        if (isRegistered.get() || isRegistering.get()) {
            Log.d(TAG, "NSD service already registering or registered. Ignoring duplicate call.");
            return;
        }

        if (nsdManager == null) {
            Log.e(TAG, "NsdManager is null on this device. NSD cannot be started.");
            return;
        }

        isRegistering.set(true);
        currentPort = port;

        // Acquire MulticastLock to allow mDNS multicast traffic
        acquireMulticastLock();

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(LudoProtocol.NSD_SERVICE_NAME);
        serviceInfo.setServiceType(LudoProtocol.NSD_SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo registeredInfo) {
                isRegistering.set(false);
                isRegistered.set(true);
                currentServiceName = registeredInfo.getServiceName();
                Log.i(TAG, "NSD service successfully registered: name=" + currentServiceName + ", type=" + registeredInfo.getServiceType() + ", port=" + registeredInfo.getPort());
                notifyRegistered(currentServiceName, registeredInfo.getPort());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                isRegistering.set(false);
                isRegistered.set(false);
                Log.e(TAG, "NSD registration failed: errorCode=" + errorCode);
                releaseMulticastLock();
                notifyError("NSD registration failed with code " + errorCode, errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                isRegistered.set(false);
                isRegistering.set(false);
                Log.i(TAG, "NSD service unregistered: " + serviceInfo.getServiceName());
                releaseMulticastLock();
                notifyUnregistered();
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                isRegistered.set(false);
                isRegistering.set(false);
                Log.e(TAG, "NSD unregistration failed: errorCode=" + errorCode);
                releaseMulticastLock();
                notifyError("NSD unregistration failed with code " + errorCode, errorCode);
            }
        };

        try {
            Log.i(TAG, "Registering NSD service: type=" + LudoProtocol.NSD_SERVICE_TYPE + ", port=" + port);
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            isRegistering.set(false);
            isRegistered.set(false);
            releaseMulticastLock();
            Log.e(TAG, "Exception during NSD registerService: " + e.getMessage(), e);
            notifyError("NSD exception: " + e.getMessage(), -1);
        }
    }

    /**
     * Unregisters the NSD service safely.
     */
    public synchronized void unregisterService() {
        if (!isRegistered.get() && !isRegistering.get()) {
            Log.d(TAG, "NSD service not registered. Skipping unregister.");
            return;
        }

        if (nsdManager != null && registrationListener != null) {
            try {
                Log.i(TAG, "Unregistering NSD service...");
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Log.w(TAG, "Exception during unregisterService: " + e.getMessage());
            }
        }
        registrationListener = null;
        isRegistered.set(false);
        isRegistering.set(false);
        releaseMulticastLock();
        notifyUnregistered();
    }

    public boolean isRegistered() {
        return isRegistered.get();
    }

    @NonNull
    public String getCurrentServiceName() {
        return currentServiceName;
    }

    public int getCurrentPort() {
        return currentPort;
    }

    public void addListener(@NonNull NsdStatusListener listener) {
        statusListeners.add(listener);
    }

    public void removeListener(@NonNull NsdStatusListener listener) {
        statusListeners.remove(listener);
    }

    private void acquireMulticastLock() {
        if (wifiManager != null && multicastLock == null) {
            try {
                multicastLock = wifiManager.createMulticastLock("LudoHostMulticastLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
                Log.d(TAG, "MulticastLock acquired");
            } catch (Exception e) {
                Log.w(TAG, "Could not acquire MulticastLock: " + e.getMessage());
            }
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null) {
            try {
                if (multicastLock.isHeld()) {
                    multicastLock.release();
                    Log.d(TAG, "MulticastLock released");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MulticastLock: " + e.getMessage());
            }
            multicastLock = null;
        }
    }

    private void notifyRegistered(@NonNull String name, int port) {
        mainHandler.post(() -> {
            for (NsdStatusListener l : statusListeners) {
                try {
                    l.onNsdRegistered(name, port);
                } catch (Exception ignored) {}
            }
        });
    }

    private void notifyUnregistered() {
        mainHandler.post(() -> {
            for (NsdStatusListener l : statusListeners) {
                try {
                    l.onNsdUnregistered();
                } catch (Exception ignored) {}
            }
        });
    }

    private void notifyError(@NonNull String err, int code) {
        mainHandler.post(() -> {
            for (NsdStatusListener l : statusListeners) {
                try {
                    l.onNsdError(err, code);
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Resolves the primary local IPv4 address (e.g. Wi-Fi IP or Hotspot IP).
     */
    @NonNull
    public static String getLocalIpAddress(@NonNull Context context) {
        try {
            // First try network interfaces (covers both Wi-Fi and Wi-Fi Hotspot)
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (NetworkInterface intf : Collections.list(interfaces)) {
                    // Skip loopback and down interfaces
                    if (intf.isLoopback() || !intf.isUp()) continue;

                    Enumeration<InetAddress> addrs = intf.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            String host = addr.getHostAddress();
                            if (host != null && !host.startsWith("127.")) {
                                return host;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resolving IP from NetworkInterface: " + e.getMessage());
        }

        return "127.0.0.1";
    }
}
