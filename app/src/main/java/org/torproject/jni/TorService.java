package org.torproject.jni;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import net.freehaven.tor.control.RawEventListener;
import net.freehaven.tor.control.TorControlCommands;
import net.freehaven.tor.control.TorControlConnection;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.StringTokenizer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Robust overlay of {@link Service} that runs Tor.
 */
@SuppressWarnings("deprecation")
public class TorService extends Service {

    public static final String TAG = "TorService";

    public static final String VERSION_NAME = ru.toinet.android.BuildConfig.VERSION_NAME;

    public static final String ACTION_START = "org.torproject.android.intent.action.START";
    public static final String ACTION_STOP = "org.torproject.android.intent.action.STOP";
    public static final String ACTION_STATUS = "org.torproject.android.intent.action.STATUS";
    public static final String ACTION_ERROR = "org.torproject.android.intent.action.ERROR";

    public static final String EXTRA_STATUS = "org.torproject.android.intent.extra.STATUS";
    public static final String EXTRA_PACKAGE_NAME = "org.torproject.android.intent.extra.PACKAGE_NAME";
    public static final String EXTRA_SERVICE_PACKAGE_NAME = "org.torproject.android.intent.extra.SERVICE_PACKAGE_NAME";

    public static final String STATUS_OFF = "OFF";
    public static final String STATUS_ON = "ON";
    public static final String STATUS_STARTING = "STARTING";
    public static final String STATUS_STOPPING = "STOPPING";

    public static File getTorrc(Context context) {
        return new File(getAppTorServiceDir(context), "torrc");
    }

    public static File getDefaultsTorrc(Context context) {
        return new File(getAppTorServiceDir(context), "torrc-defaults");
    }

    public static String getBroadcastPackageName(Context context) {
        if (broadcastPackageName.equals(UNINITIALIZED)) {
            broadcastPackageName = context.getPackageName();
        }
        return broadcastPackageName;
    }

    public static void setBroadcastPackageName(String packageName) {
        TorService.broadcastPackageName = packageName;
    }

    private static File getControlSocket(Context context) {
        if (controlSocket == null) {
            controlSocket = new File(getAppTorServiceDataDir(context), CONTROL_SOCKET_NAME);
        }
        return controlSocket;
    }

    private static File getAppTorServiceDir(Context context) {
        if (appTorServiceDir == null) {
            appTorServiceDir = context.getDir(TorService.class.getSimpleName(), MODE_PRIVATE);
        }
        return appTorServiceDir;
    }

    private static File getAppTorServiceDataDir(Context context) {
        File dir = new File(getAppTorServiceDir(context), "data");
        dir.mkdir();
        if (!(dir.setReadable(true, true) && dir.setWritable(true, true) && dir.setExecutable(true, true))) {
            throw new IllegalStateException("Cannot create " + dir);
        }
        return dir;
    }

    static {
        System.loadLibrary("tor");
    }

    volatile static String currentStatus = STATUS_OFF;

    private static File appTorServiceDir = null;
    private static File controlSocket = null;
    private static final String CONTROL_SOCKET_NAME = "ControlSocket";
    private static final String UNINITIALIZED = "UNINITIALIZED";
    private static String broadcastPackageName = UNINITIALIZED;

    public static int socksPort = -1;
    public static int httpTunnelPort = -1;

    @SuppressWarnings({"FieldMayBeFinal", "unused"})
    private long torConfiguration = -1;
    @SuppressWarnings({"FieldMayBeFinal"," unused"})
    private int torControlFd = -1;

    private volatile TorControlConnection torControlConnection;

    private static final ReentrantLock runLock = new ReentrantLock();

    private native boolean createTorConfiguration();
    private native void mainConfigurationFree();
    private native static FileDescriptor prepareFileDescriptor(String path);
    private native boolean mainConfigurationSetCommandLine(String[] args);
    private native boolean mainConfigurationSetupControlSocket();
    private native int runMain();

    public class LocalBinder extends Binder {
        public TorService getService() {
            return TorService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        broadcastStatus(this, STATUS_STARTING);
        startTorServiceThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        sendBroadcastStatusIntent(this);
        return super.onStartCommand(intent, flags, startId);
    }

    private final RawEventListener startedEventListener = new RawEventListener() {
        @Override
        public void onEvent(String keyword, String data) {
            if (TorService.STATUS_STARTING.equals(TorService.currentStatus)
                    && TorControlCommands.EVENT_CIRCUIT_STATUS.equals(keyword)
                    && data != null && !data.isEmpty()) {
                String[] tokenArray = data.split(" ");
                if (tokenArray.length > 1 && TorControlCommands.CIRC_EVENT_BUILT.equals(tokenArray[1])) {
                    TorService.broadcastStatus(TorService.this, TorService.STATUS_ON);
                }
            }
        }
    };

    private final Thread controlPortThread = new Thread(CONTROL_SOCKET_NAME) {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                final CountDownLatch countDownLatch = new CountDownLatch(1);
                final String observeDir = getAppTorServiceDataDir(TorService.this).getAbsolutePath();
                FileObserver controlPortFileObserver = new FileObserver(observeDir) {
                    @Override
                    public void onEvent(int event, @Nullable String name) {
                        if ((event & FileObserver.CREATE) > 0 && CONTROL_SOCKET_NAME.equals(name)) {
                            countDownLatch.countDown();
                        }
                    }
                };
                controlPortFileObserver.startWatching();
                if (controlPortThreadStarted != null) {
                    controlPortThreadStarted.countDown();
                }
                countDownLatch.await(10, TimeUnit.SECONDS);
                controlPortFileObserver.stopWatching();
                File controlSocket = new File(observeDir, CONTROL_SOCKET_NAME);
                if (!controlSocket.canRead()) {
                    throw new IOException("cannot read " + controlSocket);
                }

                FileDescriptor controlSocketFd = prepareFileDescriptor(getControlSocket(TorService.this).getAbsolutePath());
                InputStream is = new FileInputStream(controlSocketFd);
                OutputStream os = new FileOutputStream(controlSocketFd);
                torControlConnection = new TorControlConnection(is, os);
                torControlConnection.launchThread(true);
                torControlConnection.authenticate(new byte[0]);
                torControlConnection.addRawEventListener(startedEventListener);
                torControlConnection.setEvents(Collections.singletonList(TorControlCommands.EVENT_CIRCUIT_STATUS));

                socksPort = getPortFromGetInfo("net/listeners/socks");
                httpTunnelPort = getPortFromGetInfo("net/listeners/httptunnel");

            } catch (Throwable e) {
                Log.e(TAG, "Error in controlPortThread", e);
                broadcastError(TorService.this, e);
                broadcastStatus(TorService.this, STATUS_STOPPING);
                stopSelf();
            }
        }
    };

    private volatile CountDownLatch controlPortThreadStarted;

    private final Thread torThread = new Thread("tor") {
        @Override
        public void run() {
            final Context context = getApplicationContext();
            runLock.lock();
            try {
                createTorConfiguration();
                setDefaultProxyPorts();

                ArrayList<String> lines = new ArrayList<>(Arrays.asList("tor", "--verify-config",
                        "--RunAsDaemon", "0",
                        "-f", getTorrc(context).getAbsolutePath(),
                        "--defaults-torrc", getDefaultsTorrc(context).getAbsolutePath(),
                        "--ignore-missing-torrc",
                        "--SyslogIdentityTag", TAG,
                        "--CacheDirectory", new File(getCacheDir(), TAG).getAbsolutePath(),
                        "--DataDirectory", getAppTorServiceDataDir(context).getAbsolutePath(),
                        "--ControlSocket", getControlSocket(context).getAbsolutePath(),
                        "--CookieAuthentication", "0",
                        "--LogMessageDomains", "1",
                        "--TruncateLogFile", "1"
                ));
                String[] verifyLines = lines.toArray(new String[0]);
                if (!mainConfigurationSetCommandLine(verifyLines)) {
                    throw new IllegalArgumentException("Setting command line failed: " + Arrays.toString(verifyLines));
                }
                int result = runMain(); // run verify
                if (result != 0) {
                    throw new IllegalArgumentException("Bad command flags: " + Arrays.toString(verifyLines));
                }

                controlPortThreadStarted = new CountDownLatch(1);
                controlPortThread.start();
                controlPortThreadStarted.await();

                String[] runLines = new String[lines.size() - 1];
                runLines[0] = "tor";
                for (int i = 2; i < lines.size(); i++) {
                    runLines[i - 1] = lines.get(i);
                }
                if (!mainConfigurationSetCommandLine(runLines)) {
                    throw new IllegalArgumentException("Setting command line failed: " + Arrays.toString(runLines));
                }
                if (!mainConfigurationSetupControlSocket()) {
                    throw new IllegalStateException("Setting up ControlPort failed!");
                }
                if (runMain() != 0) {
                    throw new IllegalStateException("Tor could not start!");
                }

            } catch (Throwable e) {
                Log.e(TAG, "Error in torThread", e);
                broadcastError(context, e);
            } finally {
                broadcastStatus(context, STATUS_STOPPING);
                try {
                    mainConfigurationFree();
                } catch (Throwable t) {
                    Log.w(TAG, "Error in mainConfigurationFree", t);
                }
                if (runLock.isHeldByCurrentThread()) {
                    runLock.unlock();
                }
                TorService.this.stopSelf();
            }
        }
    };

    private void setDefaultProxyPorts() {
        String socksPort = "auto";
        if (isPortAvailable(9050)) {
            socksPort = Integer.toString(9050);
        }
        String httpTunnelPort = "auto";
        if (isPortAvailable(8118)) {
            httpTunnelPort = Integer.toString(8118);
        }

        String defaults = "SOCKSPort " + socksPort;
        defaults += "\nHTTPTunnelPort " + httpTunnelPort + "\n";

        try {
            PrintWriter pw = new PrintWriter(new FileWriter(getDefaultsTorrc(this), false));
            pw.append(defaults);
            pw.flush();
            pw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getPortFromGetInfo(String key) {
        try {
            final String value = getInfo(key);
            if (value == null || value.trim().isEmpty()) return 0; // port is disabled
            String trimmed = value.trim();
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
                } catch (Exception ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get/parse port for key: " + key, t);
        }
        return 0;
    }

    private void startTorServiceThread() {
        Log.i(TAG, "Starting torThread");
        torThread.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (torControlConnection != null) {
            try {
                torControlConnection.removeRawEventListener(startedEventListener);
            } catch (Exception ignored) {}
        }
        if (runLock.isHeldByCurrentThread()) {
            Log.i(TAG, "Releasing lock in onDestroy");
            runLock.unlock();
        }
        shutdownTor();
        broadcastStatus(TorService.this, STATUS_OFF);
        // Clean process termination for :tor process on stop/error to avoid native libtor.so SIGSEGV/SIGABRT.
        // Because TorService runs in an isolated process (:tor), terminating here cleanly resets
        // libtor.so native C state for the next start without affecting the main application process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public int getSocksPort() {
        return socksPort;
    }

    public int getHttpTunnelPort() {
        return httpTunnelPort;
    }

    public String getInfo(String key) {
        try {
            if (torControlConnection != null) {
                return torControlConnection.getInfo(key);
            }
        } catch (Throwable e) {
            Log.w(TAG, "getInfo failed for key: " + key, e);
        }
        return null;
    }

    private void shutdownTor() {
        try {
            if (torControlConnection != null) {
                torControlConnection.shutdownTor(TorControlCommands.SIGNAL_SHUTDOWN);
            }
        } catch (Throwable e) {
            Log.w(TAG, "shutdownTor error", e);
        }
    }

    public TorControlConnection getTorControlConnection() {
        return torControlConnection;
    }

    static void sendBroadcastStatusIntent(Context context) {
        Intent intent = getBroadcastIntent(context, currentStatus);
        context.sendBroadcast(intent);
    }

    static void broadcastStatus(Context context, String currentStatus) {
        TorService.currentStatus = currentStatus;
        Intent intent = getBroadcastIntent(context, currentStatus);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        context.sendBroadcast(intent);
    }

    static void broadcastError(Context context, Throwable e) {
        Intent intent = new Intent(ACTION_ERROR);
        if (e != null) {
            intent.putExtra(Intent.EXTRA_TEXT, e.getLocalizedMessage());
        }
        intent.setPackage(getBroadcastPackageName(context));
        intent.putExtra(EXTRA_SERVICE_PACKAGE_NAME, context.getPackageName());
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        context.sendBroadcast(intent);
    }

    private static Intent getBroadcastIntent(Context context, String currentStatus) {
        Intent intent = new Intent(TorService.ACTION_STATUS);
        intent.putExtra(EXTRA_SERVICE_PACKAGE_NAME, context.getPackageName());
        intent.setPackage(getBroadcastPackageName(context));
        intent.putExtra(EXTRA_STATUS, currentStatus);
        return intent;
    }

    private static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
