/* Copyright (c) 2009-2011, Nathan Freitas, ToInet / The Guardian Project - https://guardianproject.info/apps/toinet */
/* See LICENSE for licensing information */

package ru.toinet.android.service;

import static ru.toinet.android.service.ToInetConstants.*;
import static org.torproject.jni.TorService.*;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import net.freehaven.tor.control.TorControlCommands;
import net.freehaven.tor.control.TorControlConnection;

import ru.toinet.android.R;
import ru.toinet.android.service.circumvention.SmartConnect;
import ru.toinet.android.service.tor.CustomTorResourceInstaller;
import ru.toinet.android.util.*;
import ru.toinet.android.service.tor.TorConfig;
import org.torproject.jni.TorService;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import kotlin.Unit;

public class ToInetService extends Service {

    static final int NOTIFY_ID = 1, ERROR_NOTIFY_ID = 3;
    public static int mPortSOCKS = -1, mPortHTTP = -1, mPortDns = -1, mPortTrans = -1;
    public static File appBinHome, appCacheHome;
    protected final ExecutorService mExecutor = Executors.newCachedThreadPool();
    ToInetRawEventListener mToInetRawEventListener;
    Handler mHandler = new Handler(android.os.Looper.getMainLooper());
    ActionBroadcastReceiver mActionBroadcastReceiver;
    protected String mCurrentStatus = STATUS_OFF;
    TorControlConnection conn = null;
    private ServiceConnection torServiceConnection;
    private boolean shouldUnbindTorService;
    private NotificationManager mNotificationManager = null;
    private NotificationCompat.Builder mNotifyBuilder;
    private static final String TAG = "ToInetService";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean isTorDesired() {
        return Prefs.isProviderActive("tor");
    }

    private final Runnable autoReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTorDesired() && STATUS_OFF.equals(mCurrentStatus)) {
                Log.d(TAG, "Auto-pressing 'Connect' button (Tor is desired and in STATUS_OFF)...");
                startTor();
            }
        }
    };

    private void scheduleAutoReconnectIfEnabled() {
        if (isTorDesired() && !STATUS_ON.equals(mCurrentStatus) && !STATUS_STARTING.equals(mCurrentStatus)) {
            if (mHandler != null) {
                mHandler.removeCallbacks(autoReconnectRunnable);
                mHandler.postDelayed(autoReconnectRunnable, 4000);
            }
        }
    }

    protected void showToolbarNotification(String notifyMsg, int notifyType, int icon) {
        if (ru.toinet.android.util.Prefs.isGlobalVpnEnabled() || !ru.toinet.android.util.Prefs.isPrimaryNotificationService("tor")) {
            return;
        }


        var intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        var pendIntent = PendingIntent.getActivity(ToInetService.this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notifications.createNotificationChannel(this);
        }

        boolean isDetailed = ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor");
        String channelId = Notifications.resolveTorNotificationChannel();
        int priority = Notifications.resolveTorNotificationPriority();

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotifyBuilder = new NotificationCompat
                .Builder(this, channelId)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setGroup("toinet_services")
                .setSmallIcon(R.drawable.ic_stat_tor)
                .setSilent(true)
                .setOngoing(true);

        String currentProvider = ru.toinet.android.util.Prefs.getNotificationLogProvider();
        String title = "ToInet";
        String content = null;

        if ("address_status".equals(currentProvider)) {
            title = "Состояние Адреса";
            content = ru.toinet.android.util.AddressStatusHelper.getAddressStatus(this);
        } else if ("vpn".equals(currentProvider)) {
            title = "ToInet";
            content = Notifications.getNotificationTitleForStatus(this, mCurrentStatus);
        } else {
            title = "ToInet";
            content = null;
        }

        mNotifyBuilder
                .setSmallIcon(R.drawable.ic_stat_tor)
                .setContentTitle(title)
                .setContentText(content)
                .setSubText(null)
                .setProgress(0, 0, false)
                .setPriority(priority)
                .setContentIntent(pendIntent);

        // Tor connection is active
        if (conn != null && mCurrentStatus.equals(STATUS_ON)) { // only add new identity action when there is a connection
            var i = new Intent(this, ToInetService.class);
            i.setAction(TorControlCommands.SIGNAL_NEWNYM);
            i.putExtra(ToInetConstants.EXTRA_NOT_SYSTEM, true);

            var pendingIntentNewNym = getServiceIntent(i);

            mNotifyBuilder.addAction(R.drawable.ic_refresh_white_24dp, getString(R.string.menu_new_identity), pendingIntentNewNym);
        } // Tor connection is off
        else if (mCurrentStatus.equals(STATUS_OFF)) {
            var i = new Intent(this, ToInetService.class);
            i.setAction(ACTION_START);
            i.putExtra(ToInetConstants.EXTRA_NOT_SYSTEM, true);

            var pendingIntentConnect = getServiceIntent(i);

            mNotifyBuilder
                    .addAction(R.drawable.ic_stat_tor, getString(R.string.connect_to_tor), pendingIntentConnect)
                    .setContentText(content)
                    .setSubText(null)
                    .setProgress(0, 0, false)
                    .setTicker(null);

            scheduleAutoReconnectIfEnabled();
        }
        var notification = mNotifyBuilder.build();
        try {
            ServiceCompat.startForeground(this, NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Exception e) {
            Log.w(TAG, "Failed to startForeground with specialUse, fallback to standard", e);
            try {
                ServiceCompat.startForeground(this, NOTIFY_ID, notification, 0);
            } catch (Exception e2) {
                Log.e(TAG, "Failed fallback startForeground", e2);
            }
        }
        if (mNotificationManager != null) {
            mNotificationManager.notify(NOTIFY_ID, notification);
        }
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) {
                Log.d(TAG, "Got null onStartCommand() intent");
                return Service.START_REDELIVER_INTENT;
            }

            String action = intent.getAction();
            boolean isStartAction = ACTION_START.equals(action);
            boolean isActuallyRunning = !mCurrentStatus.equals(STATUS_OFF);

            if (isStartAction || isActuallyRunning) {
                showToolbarNotification(getString(R.string.open_toinet_to_connect_to_tor), NOTIFY_ID, R.drawable.ic_stat_tor);
            } else {
                // Do not show notification or start foreground for pings or other actions when Tor is OFF
                Log.d(TAG, "Skipping foreground for action: " + action + " while Tor is OFF");
            }

            mExecutor.execute(new IncomingIntentRouter(intent));

        } catch (RuntimeException re) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: ToInet DoS via exported activity (High)
            Log.e(TAG, "error with ToInetService", re);
        }

        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(mActionBroadcastReceiver);
        } catch (Exception iae) {
            //not registered yet
        }
        if (mHandler != null) {
            mHandler.removeCallbacks(autoReconnectRunnable);
        }
        if (shouldUnbindTorService && torServiceConnection != null) {
            try {
                unbindService(torServiceConnection);
            } catch (Exception ignored) {}
            shouldUnbindTorService = false;
            torServiceConnection = null;
        }
        super.onDestroy();
    }

    private void stopTorAsync(boolean showNotification) {
        Log.d(TAG, "stopTorAsync");
        if (showNotification) sendCallbackLogMessage(getString(R.string.status_shutting_down));
        Prefs.getTransport().stop();
        stopTor();

        //stop the foreground priority and remove the persistent notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (mNotificationManager != null) {
            mNotificationManager.cancel(NOTIFY_ID);
        }
        if (showNotification) sendCallbackLogMessage(getString(R.string.status_disabled));

        mPortDns = -1;
        mPortSOCKS = -1;
        mPortHTTP = -1;
        mPortTrans = -1;

        if (mToInetRawEventListener != null) mToInetRawEventListener.getNodes().clear();

        //ensure service is destroyed and we get a clean instance of TorService
        stopSelf();
    }

    private void stopTorOnError(String message) {
        stopTorAsync(false);
        showToolbarNotification(getString(R.string.unable_to_start_tor) + ": " + message, ERROR_NOTIFY_ID, R.drawable.ic_stat_notifyerr);
        scheduleAutoReconnectIfEnabled();
    }

    // if someone stops during startup, we may have to wait for the conn port to be setup, so we can properly shutdown tor
    private void stopTor() {

        if (conn != null) {
            try {
                //make sure Tor shuts down now - don't wait for service cleanup
                conn.shutdownTor(TorControlCommands.SIGNAL_SHUTDOWN);
            } catch (Exception e) {
                Log.d(TAG, "error shutting down Tor from the control port");
            }
        }

        if (shouldUnbindTorService) {
            try {
                Log.d(TAG, "unbinding tor service");
                unbindService(torServiceConnection);
            } catch (Exception e) {
                Log.w(TAG, "Error unbinding tor service", e);
            } finally {
                shouldUnbindTorService = false;
                torServiceConnection = null;
                conn = null;
            }
        } else {
            sendLocalStatusOffBroadcast();
        }
        try {
            stopService(new Intent(this, TorService.class));
        } catch (Exception ignored) {}
    }

    private void requestTorRereadConfig() {
        try {
            if (conn == null) return;
            conn.signal(TorControlCommands.SIGNAL_RELOAD);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
    }

    protected void logNotice(String msg) {
        if (msg != null && !msg.trim().isEmpty()) {
            Log.d(TAG, msg);
            sendCallbackLogMessage(msg);
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void onCreate() {
        super.onCreate();
        configLanguage();

        try {
            try {
                mHandler = new Handler();
                appBinHome = getFilesDir();
                if (!appBinHome.exists()) appBinHome.mkdirs();

                appCacheHome = new File(getDataDir(), DIRECTORY_TOR_DATA);

                if (!appCacheHome.exists()) appCacheHome.mkdirs();


                if (mNotificationManager == null)
                    mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

                var filter = new IntentFilter(CMD_ACTIVE);
                filter.addAction(ACTION_STATUS);
                filter.addAction(ACTION_ERROR);

                mActionBroadcastReceiver = new ActionBroadcastReceiver();
                ContextCompat.registerReceiver(this, mActionBroadcastReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    Notifications.createNotificationChannel(this);

                var hasGeoip = new File(appBinHome, GEOIP_ASSET_KEY).exists();
                var hasGeoip6 = new File(appBinHome, GEOIP6_ASSET_KEY).exists();


                if (!hasGeoip || !hasGeoip6 || Prefs.isGeoIpReinstallNeeded()) {
                    try {
                        Log.d(TAG, "Installing geoip files...");
                        new CustomTorResourceInstaller(this, appBinHome).installGeoIP();
                        Prefs.setGeoIpReinstallNeeded(false);
                    } catch (IOException io) { // user has < 10MB free space on disk...
                        Log.e(TAG, "Error installing geoip files", io);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error setting up ToInet", e);
                logNotice(getString(R.string.couldn_t_start_tor_process_) + " " + e.getClass().getSimpleName());
            }
        } catch (RuntimeException re) {
            //catch this to avoid malicious launches as document Cure53 Audit: ORB-01-009 WP1/2: ToInet DoS via exported activity (High)
        }
    }

    private void configLanguage() {
        var config = getApplicationContext().getResources().getConfiguration();
        Prefs.setContext(getApplicationContext());
        var locale = new Locale(Prefs.getDefaultLocale());
        Locale.setDefault(locale);
        config.setLocale(locale);
        getBaseContext().getResources().updateConfiguration(config, getBaseContext().getResources().getDisplayMetrics());
    }

    @NonNull
    private File updateTorrcCustomFile() throws IOException {
        var conf = TorConfig.build(this, new File(appBinHome, GEOIP_ASSET_KEY),
                new File(appBinHome, GEOIP6_ASSET_KEY));

        logNotice(getString(R.string.log_notice_updating_torrc));
        Log.d(TAG, "torrc.custom=\n" + conf);

        var fileTorRcCustom = TorService.getTorrc(this);
        DiskUtils.flushTextToFile(fileTorRcCustom, conf, false);
        return fileTorRcCustom;
    }


    private void replyWithStatus(Intent startRequest) {
        ru.toinet.android.util.NetworkBlackoutMonitor.onTorStatus(mCurrentStatus);
        String packageName = startRequest.getStringExtra(EXTRA_PACKAGE_NAME);
        Intent reply = new Intent(ACTION_STATUS)
                .putExtra(EXTRA_STATUS, mCurrentStatus)
                .putExtra(EXTRA_SOCKS_PROXY, "socks://127.0.0.1:" + mPortSOCKS)
                .putExtra(EXTRA_SOCKS_PROXY_HOST, "127.0.0.1")
                .putExtra(EXTRA_SOCKS_PROXY_PORT, mPortSOCKS)
                .putExtra(EXTRA_HTTP_PROXY, "http://127.0.0.1:" + mPortHTTP)
                .putExtra(EXTRA_HTTP_PROXY_HOST, "127.0.0.1")
                .putExtra(EXTRA_HTTP_PROXY_PORT, mPortHTTP)
                .putExtra(EXTRA_DNS_PORT, mPortDns);

        if (packageName != null)
            sendBroadcast(reply.setPackage(packageName));

        sendBroadcast(reply.setAction(LOCAL_ACTION_STATUS).setPackage(getPackageName()));

        if (mPortSOCKS != -1 && mPortHTTP != -1)
            sendCallbackPorts(mPortSOCKS, mPortHTTP, mPortDns, mPortTrans);
    }

    private boolean showTorServiceErrorMsg = false;

    // The entire process for starting tor and related services is run from this method.
    private void startTor() {
        if (torServiceConnection != null && conn != null) {
            Log.d(TAG, "already started, ignoring start request");
            if (mNotifyBuilder != null) mNotifyBuilder.setProgress(0, 0, false);
            showToolbarNotification(getString(R.string.status_activated), NOTIFY_ID, R.drawable.ic_stat_tor);
            return;
        }
        if (mNotifyBuilder != null && ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) {
            mNotifyBuilder.setProgress(100, 0, false);
        }
        showToolbarNotification("", NOTIFY_ID, R.drawable.ic_stat_tor);

        SmartConnect.handle(this,
                /* startTor: () -> Exception? */
                () -> {
                    try {
                        startTorService();
                        showTorServiceErrorMsg = true;
                    } catch (Exception e) {
                        return e;
                    }

                    return null;
                },
                /* reconfigure: () -> Boolean */
                () -> {
                    if (conn == null) return false;

                    try {
                        conn.resetConf(Arrays.asList("UseBridges", "ClientTransportPlugin", "Bridge"));
                        conn.setConf(Prefs.getTransport().getTorConfig(this));
                    } catch (IOException e) {
                        logNotice(e.getLocalizedMessage());

                        return false;
                    }

                    return true;
                },
                /* stopTor: (e: Exception?) -> Unit */
                (Exception e) -> {
                    if (e != null) {
                        logNotice(getString(R.string.unable_to_start_tor) + " " + e.getLocalizedMessage());
                        stopTorOnError(e.getLocalizedMessage());
                    } else {
                        //     stopTorAsync(true);
                    }

                    return Unit.INSTANCE;
                },
                /* completed: () -> Unit)  */
                () -> Unit.INSTANCE);
    }


    private synchronized void startTorService() throws Exception {
        if (Prefs.getProxyEnabled()) {
            String upstream = Prefs.getTorUpstreamProvider();
            if (upstream == null || upstream.isEmpty() || "none".equals(upstream)) {
                var pair = Prefs.getOutboundProxy();
                if (pair.getFirst() != null && "127.0.0.1".equals(pair.getFirst().getHost())) {
                    int port = pair.getFirst().getPort();
                    if (port == Prefs.getEasyVpnProxyPort()) upstream = "easyvpn";
                    else if (port == 1780) upstream = "byedpi";
                }
            }
            if (upstream != null && !"none".equals(upstream) && !"custom".equals(upstream)) {
                ru.toinet.android.util.UpstreamProxyCoordinator.INSTANCE.ensureUpstreamStarted(this, upstream);
            }
        }

        DiskUtils.flushTextToFile(TorService.getDefaultsTorrc(this), """
                DNSPort 0
                TransPort 0
                DisableNetwork 1
                """, false);

        var fileTorrcCustom = updateTorrcCustomFile();
        if ((!fileTorrcCustom.exists()) || (!fileTorrcCustom.canRead())) return;

        sendCallbackLogMessage(getString(R.string.status_starting_up));

        if (torServiceConnection != null) {
            try {
                if (shouldUnbindTorService) {
                    unbindService(torServiceConnection);
                }
            } catch (Exception ignored) {}
            shouldUnbindTorService = false;
            torServiceConnection = null;
            conn = null;
        }

        torServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
                mExecutor.execute(() -> {
                    try {
                        TorService torService = ((TorService.LocalBinder) iBinder).getService();

                        int retries = 0;
                        while ((conn = torService.getTorControlConnection()) == null && retries < 60) {
                            try {
                                Thread.sleep(500);
                                retries++;
                            } catch (InterruptedException e) {
                                Log.e(TAG, e.toString());
                                break;
                            }
                        }

                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Log.e(TAG, e.toString());
                        }

                        mToInetRawEventListener = new ToInetRawEventListener(ToInetService.this);

                        if (conn == null) {
                            Log.e(TAG, "Timed out waiting for Tor control connection");
                            logNotice("Ошибка: таймаут подключения к Tor");
                            mHandler.post(() -> stopTorOnError("Таймаут подключения к Tor"));
                            return;
                        }
                        try {
                            initControlConnection();
                            if (conn == null)
                                return;

                            conn.addRawEventListener(mToInetRawEventListener);
                            logNotice(getString(R.string.status_connected_control_port));

                            var events = new ArrayList<>(Arrays.asList(TorControlCommands.EVENT_OR_CONN_STATUS, TorControlCommands.EVENT_CIRCUIT_STATUS, TorControlCommands.EVENT_NOTICE_MSG, TorControlCommands.EVENT_WARN_MSG, TorControlCommands.EVENT_ERR_MSG, TorControlCommands.EVENT_BANDWIDTH_USED, TorControlCommands.EVENT_NEW_DESC, TorControlCommands.EVENT_ADDRMAP));
                            if (Prefs.useDebugLogging())
                                events.addAll(Arrays.asList(TorControlCommands.EVENT_DEBUG_MSG, TorControlCommands.EVENT_INFO_MSG, TorControlCommands.EVENT_STREAM_STATUS));
                            conn.setEvents(events);
                        } catch (IOException e) {
                            Log.e(TAG, "initControlConnection error", e);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error in onServiceConnected background handler", t);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                Log.d(TAG, "TorService: onServiceDisconnected");
                if (shouldUnbindTorService && torServiceConnection != null) {
                    try {
                        unbindService(torServiceConnection);
                    } catch (Exception e) {
                        Log.w(TAG, "Error unbinding on onServiceDisconnected", e);
                    }
                }
                conn = null;
                torServiceConnection = null;
                shouldUnbindTorService = false;
                mCurrentStatus = STATUS_OFF;
                sendLocalStatusOffBroadcast();
                scheduleAutoReconnectIfEnabled();
            }

            @Override
            public void onBindingDied(ComponentName componentName) {
                Log.w(TAG, "TorService: onBindingDied");
                if (shouldUnbindTorService && torServiceConnection != null) {
                    try {
                        unbindService(torServiceConnection);
                    } catch (Exception e) {
                        Log.w(TAG, "Error unbinding on onBindingDied", e);
                    }
                }
                conn = null;
                torServiceConnection = null;
                shouldUnbindTorService = false;
                mCurrentStatus = STATUS_OFF;
                sendLocalStatusOffBroadcast();
                scheduleAutoReconnectIfEnabled();
            }
        };

        var serviceIntent = new Intent(this, TorService.class);
        Log.d(TAG, "starting & binding tor service");
        try {
            startService(serviceIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed startService for TorService", e);
        }
        int bindFlags = Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            shouldUnbindTorService = bindService(serviceIntent, torServiceConnection, bindFlags);
        else
            shouldUnbindTorService = bindService(serviceIntent, bindFlags, mExecutor, torServiceConnection);
    }

    private void sendLocalStatusOffBroadcast() {
        sendBroadcast(new Intent(LOCAL_ACTION_STATUS)
                .putExtra(EXTRA_STATUS, STATUS_OFF)
                .setPackage(getPackageName()));
    }

    private static int parseListenerPort(String raw) {
        if (raw == null) return 0;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return 0;
        StringTokenizer st = new StringTokenizer(trimmed, " ");
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1).trim();
            } else {
                if (token.startsWith("\"")) token = token.substring(1);
                if (token.endsWith("\"")) token = token.substring(0, token.length() - 1);
            }
            if (token.startsWith("unix:")) {
                continue;
            }
            int lastColon = token.lastIndexOf(':');
            String portStr = (lastColon != -1) ? token.substring(lastColon + 1) : token;
            try {
                int port = Integer.parseInt(portStr.trim());
                if (port > 0) return port;
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private void initControlConnection() {
        if (conn == null) return;
        try {
            var confSocks = conn.getInfo("net/listeners/socks");
            mPortSOCKS = parseListenerPort(confSocks);

            var confHttp = conn.getInfo("net/listeners/httptunnel");
            mPortHTTP = parseListenerPort(confHttp);

            var confDns = conn.getInfo("net/listeners/dns");
            mPortDns = parseListenerPort(confDns);
            if (mPortDns > 0) {
                Prefs.setTorDnsPortResolved(mPortDns);
            }

            var confTrans = conn.getInfo("net/listeners/trans");
            mPortTrans = parseListenerPort(confTrans);

            sendCallbackPorts(mPortSOCKS, mPortHTTP, mPortDns, mPortTrans);

        } catch (Throwable e) {
            Log.e(TAG, "initControlConnection error", e);
            stopTorOnError(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString());
            conn = null;
        }
    }

    public void sendSignalActive() {
        if (conn != null && mCurrentStatus.equals(STATUS_ON)) {
            try {
                conn.signal("ACTIVE");
            } catch (IOException e) {
                Log.d(TAG, "error send active: " + e.getLocalizedMessage());
            }
        }
    }

    public void newIdentity() {
        if (conn == null) return;
        new Thread() {
            @Override
            public void run() {
                try {
                    if (conn != null && mCurrentStatus.equals(STATUS_ON)) {
                        if (mNotifyBuilder != null) mNotifyBuilder.setSubText(null);
                        showToolbarNotification(getString(R.string.newnym), NOTIFY_ID, R.drawable.ic_stat_tor);
                        conn.signal(TorControlCommands.SIGNAL_NEWNYM);
                    }
                } catch (Exception ioe) {
                    Log.d(TAG, "error requesting newnym: " + ioe.getLocalizedMessage());
                }
            }
        }.start();
    }

    private void sendCallbackLogMessage(final String logMessage) {
        var notificationMessage = logMessage;
        ru.toinet.android.util.NotificationLogger.addLog("[tor] " + logMessage);
        ru.toinet.android.util.NetworkBlackoutMonitor.onTorLog(logMessage);

        var localIntent = new Intent(LOCAL_ACTION_LOG)
                .putExtra(LOCAL_EXTRA_LOG, logMessage)
                .setPackage(getPackageName());

        int headerIdx = logMessage.indexOf(LOG_NOTICE_HEADER);
        if (headerIdx != -1) {
            notificationMessage = notificationMessage.substring(headerIdx + LOG_NOTICE_HEADER.length());
            int bootIdx = notificationMessage.indexOf(LOG_NOTICE_BOOTSTRAPPED);
            if (bootIdx != -1) {
                try {
                    String percent = notificationMessage.substring(bootIdx + LOG_NOTICE_BOOTSTRAPPED.length());
                    int pctIdx = percent.indexOf('%');
                    if (pctIdx != -1) {
                        percent = percent.substring(0, pctIdx).trim();
                        localIntent.putExtra(LOCAL_EXTRA_BOOTSTRAP_PERCENT, percent);
                        var prog = Integer.parseInt(percent);
                        SmartConnect.updateProgress(prog);
                        ru.toinet.android.util.NetworkBlackoutMonitor.onTorProgress(prog);
                        if (mNotifyBuilder != null && ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) {
                            mNotifyBuilder.setProgress(100, prog, false);
                        }
                    }
                    int colonIdx = notificationMessage.indexOf(':', bootIdx);
                    if (colonIdx != -1 && colonIdx + 1 < notificationMessage.length()) {
                        notificationMessage = notificationMessage.substring(colonIdx + 1).trim();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed parsing bootstrap progress", e);
                }
            }
        }
        if (ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) {
            showToolbarNotification(notificationMessage, NOTIFY_ID, R.drawable.ic_stat_tor);
        }
        if (mHandler != null) {
            mHandler.post(() -> sendBroadcast(localIntent));
        } else {
            sendBroadcast(localIntent);
        }
    }

    private void sendCallbackPorts(int socksPort, int httpPort, int dnsPort, int transPort) {
        var intent = new Intent(LOCAL_ACTION_PORTS)
                .putExtra(EXTRA_SOCKS_PROXY_PORT, socksPort)
                .putExtra(EXTRA_HTTP_PROXY_PORT, httpPort)
                .putExtra(EXTRA_DNS_PORT, dnsPort)
                .putExtra(EXTRA_TRANS_PORT, transPort)
                .setPackage(getPackageName());

        sendBroadcast(intent);

    }

    void showBandwidthNotification(String message, boolean isActiveTransfer) {
        if (!mCurrentStatus.equals(STATUS_ON)) return;
        if (!ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) return;
        var icon = !isActiveTransfer ? R.drawable.ic_stat_tor : R.drawable.ic_stat_tor_xfer;
        showToolbarNotification(message, NOTIFY_ID, icon);
    }

    public void setNotificationSubtext(String message) {
        if (!ru.toinet.android.util.Prefs.shouldShowDetailedNotification("tor")) return;
        if (mNotifyBuilder != null)
            mNotifyBuilder.setSubText(message);
    }

    private void setExitNode(String newExits) {
        if (TextUtils.isEmpty(newExits)) {
            Prefs.setExitNodes("");

            if (conn == null) return;
            try {
                var resetBuffer = new ArrayList<String>();
                resetBuffer.add("ExitNodes");
                resetBuffer.add("StrictNodes");
                conn.resetConf(resetBuffer);
                conn.setConf("DisableNetwork", "1");
                conn.setConf("DisableNetwork", "0");

            } catch (Exception ioe) {
                Log.e(TAG, "Connection exception occurred resetting exits", ioe);
            }
        } else {
            Prefs.setExitNodes("{" + newExits + "}");
            if (conn == null) return;
            try {
                conn.setConf("GeoIPFile", new File(appBinHome, GEOIP_ASSET_KEY).getCanonicalPath());
                conn.setConf("GeoIPv6File", new File(appBinHome, GEOIP6_ASSET_KEY).getCanonicalPath());
                conn.setConf("ExitNodes", Prefs.getExitNodes());
                conn.setConf("StrictNodes", "1");
                conn.setConf("DisableNetwork", "1");
                conn.setConf("DisableNetwork", "0");
            } catch (Exception ioe) {
                Log.e(TAG, "Connection exception occurred resetting exits", ioe);
            }
        }
    }

    private PendingIntent getServiceIntent(Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        return PendingIntent.getService(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private class IncomingIntentRouter implements Runnable {
        final Intent mIntent;

        public IncomingIntentRouter(Intent intent) {
            mIntent = intent;
        }

        public void run() {
            var action = mIntent.getAction();
            if (TextUtils.isEmpty(action)) return;
            switch (action) {
                case ACTION_START -> {
                    var transport = Prefs.getTransport();
                    transport.start(ToInetService.this);
                    startTor();
                    replyWithStatus(mIntent);
                }
                case ACTION_STOP -> {
                    if (mHandler != null) mHandler.removeCallbacks(autoReconnectRunnable);
                    var userIsQuittingToInet = mIntent.getBooleanExtra(ACTION_STOP_FOREGROUND_TASK, false);
                    // When user cancels connecting, make sure, the SmartConnect timer is also cancelled.
                    SmartConnect.cancel();
                    stopTorAsync(!userIsQuittingToInet);
                }
                case ACTION_STOP_FOREGROUND_TASK -> stopForeground(true);
                case ACTION_STATUS -> {
                    replyWithStatus(mIntent);
                }
                case TorControlCommands.SIGNAL_RELOAD -> requestTorRereadConfig();
                case TorControlCommands.SIGNAL_NEWNYM -> newIdentity();
                case CMD_ACTIVE -> {
                    sendSignalActive();
                    if (!STATUS_OFF.equals(mCurrentStatus)) {
                        showToolbarNotification("", NOTIFY_ID, R.drawable.ic_stat_tor);
                    }
                    replyWithStatus(mIntent);
                }
                case CMD_SET_EXIT -> setExitNode(mIntent.getStringExtra("exit"));
                case ACTION_LOCAL_LOCALE_SET -> configLanguage();
                default -> Log.w(TAG, "unhandled ToInetService Intent: " + action);
            }
        }
    }

    private class ActionBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            var action = intent.getAction();
            if (action == null) return;
            switch (action) {
                case CMD_ACTIVE -> {
                    sendSignalActive();
                    if (!STATUS_OFF.equals(mCurrentStatus)) {
                        showToolbarNotification("", NOTIFY_ID, R.drawable.ic_stat_tor);
                    }
                }
                case ACTION_ERROR -> {
                    if (showTorServiceErrorMsg) {
                        if (mHandler != null) {
                            mHandler.post(() -> {
                                try {
                                    Toast.makeText(ToInetService.this, getString(R.string.toinet_config_invalid), Toast.LENGTH_LONG).show();
                                } catch (Exception ignored) {}
                            });
                        }
                        showTorServiceErrorMsg = false;
                    }
                    stopTor();
                    scheduleAutoReconnectIfEnabled();
                }
                case ACTION_STATUS -> {
                    // hack for https://github.com/guardianproject/tor-android/issues/73 remove when fixed
                    var newStatus = intent.getStringExtra(EXTRA_STATUS);
                    if (STATUS_OFF.equals(mCurrentStatus) && STATUS_STOPPING.equals(newStatus))
                        break;
                    mCurrentStatus = newStatus;
                    ru.toinet.android.util.NetworkBlackoutMonitor.onTorStatus(mCurrentStatus);

                    if (STATUS_OFF.equals(mCurrentStatus)) {
                        scheduleAutoReconnectIfEnabled();
                    } else if (STATUS_ON.equals(mCurrentStatus)) {
                        if (mHandler != null) mHandler.removeCallbacks(autoReconnectRunnable);
                    }

                    // Make sure, Smart Connect finishes successfully, even when, for some reason,
                    // progress isn't received up to 100.
                    if (STATUS_ON.equals(mCurrentStatus)) {
                        SmartConnect.updateProgress(100);
                    }

                    sendBroadcast(new Intent(LOCAL_ACTION_STATUS)
                            .putExtra(EXTRA_STATUS, mCurrentStatus)
                            .setPackage(getPackageName())); // update the activity with what's new
                }
            }
        }
    }
}
