package ru.toinet.android.vpn.utility;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import ru.toinet.android.R;

public class VpnUtility {
    private static final String TAG = "VpnUtility";

    public static int exec(String[] cmd) {
        Process p = null;
        Thread errThread = null;
        Thread outThread = null;
        try {
            Log.d(TAG, "Executing: " + java.util.Arrays.toString(cmd));
            p = Runtime.getRuntime().exec(cmd);
            final Process proc = p;

            errThread = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.e(TAG, "STDERR: " + line);
                    }
                } catch (Exception ignored) {}
            }, "VpnUtility-stderr");
            errThread.setDaemon(true);
            errThread.start();

            outThread = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "STDOUT: " + line);
                    }
                } catch (Exception ignored) {}
            }, "VpnUtility-stdout");
            outThread.setDaemon(true);
            outThread.start();

            int ret = p.waitFor();
            Log.d(TAG, "Process exited with: " + ret);

            try {
                errThread.join(1000);
                outThread.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            return ret;
        } catch (Exception e) {
            Log.e(TAG, "exec failed", e);
            if (p != null) {
                try {
                    p.destroyForcibly();
                } catch (Exception ignored) {}
            }
            if (errThread != null) {
                errThread.interrupt();
            }
            if (outThread != null) {
                outThread.interrupt();
            }
            return -1;
        }
    }

    private static boolean isProcessMatching(int pid, String expectedSubstring) {
        if (pid <= 0) return false;
        File cmdlineFile = new File("/proc/" + pid + "/cmdline");
        if (cmdlineFile.exists()) {
            try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                byte[] buf = new byte[512];
                int len = fis.read(buf);
                if (len > 0) {
                    String cmdline = new String(buf, 0, len, StandardCharsets.UTF_8);
                    if (cmdline.contains(expectedSubstring)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }

        File commFile = new File("/proc/" + pid + "/comm");
        if (commFile.exists()) {
            try (FileInputStream fis = new FileInputStream(commFile)) {
                byte[] buf = new byte[64];
                int len = fis.read(buf);
                if (len > 0) {
                    String comm = new String(buf, 0, len, StandardCharsets.UTF_8).trim();
                    if (comm.contains(expectedSubstring)) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static String getExpectedBinaryName(String filePath) {
        if (filePath == null) return null;
        if (filePath.contains("tun2socks")) return "tun2socks";
        if (filePath.contains("pdnsd")) return "pdnsd";
        return null;
    }

    public static boolean isPidAlive(String f) {
        File file = new File(f);
        if (!file.exists()) return false;
        try (InputStream i = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int len = i.read(buf, 0, 512);
            if (len <= 0) return false;
            String str = new String(buf, 0, len, StandardCharsets.UTF_8).trim().replace("\n", "");
            int pid = Integer.parseInt(str);
            File procDir = new File("/proc/" + pid);
            if (!procDir.exists() || !procDir.isDirectory()) {
                return false;
            }
            String expected = getExpectedBinaryName(f);
            if (expected != null) {
                return isProcessMatching(pid, expected);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void killPidFile(String f) {
        File file = new File(f);
        if (!file.exists()) return;

        try (InputStream i = new FileInputStream(file)) {
            byte[] buf = new byte[512];
            int len;
            StringBuilder str = new StringBuilder();
            while ((len = i.read(buf, 0, 512)) > 0) {
                str.append(new String(buf, 0, len, StandardCharsets.UTF_8));
            }
            int pid = Integer.parseInt(str.toString().trim().replace("\n", ""));
            String expected = getExpectedBinaryName(f);
            boolean isTarget = (expected == null) || isProcessMatching(pid, expected);

            if (isTarget) {
                try {
                    android.os.Process.killProcess(pid);
                } catch (Exception e) {
                    try {
                        Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(pid)}).waitFor();
                    } catch (Exception ignored) {}
                }
            } else {
                Log.w(TAG, "PID " + pid + " from " + f + " does not match expected binary " + expected + ", skipping kill to protect unrelated process");
            }
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill pid file: " + f, e);
            try { file.delete(); } catch (Exception ignored) {}
        }
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        String dir = context.getFilesDir().getAbsolutePath();
        String dnsList = dns;
        if (dnsList == null || dnsList.trim().isEmpty() || dnsList.trim().equals("208.67.222.222") || dnsList.trim().equals("1.1.1.1")) {
            dnsList = "77.88.8.8";
        }
        if (port == 53 && !dnsList.contains(",")) {
            String trimmedDns = dnsList.trim();
            if (!trimmedDns.equals("77.88.8.8") && !trimmedDns.equals("77.88.8.1")) {
                dnsList = trimmedDns + ", 77.88.8.8, 77.88.8.1";
            } else if (trimmedDns.equals("77.88.8.8")) {
                dnsList = trimmedDns + ", 77.88.8.1, 8.8.8.8";
            } else {
                dnsList = trimmedDns + ", 77.88.8.8, 8.8.8.8";
            }
        }
        String conf = String.format(context.getString(R.string.pdnsd_conf), dir, dir, dnsList, port);

        File f = new File(dir + "/pdnsd.conf");
        if (f.exists()) f.delete();

        try (OutputStream out = new FileOutputStream(f)) {
            out.write(conf.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write pdnsd.conf", e);
        }

        File cache = new File(dir + "/pdnsd.cache");
        if (cache.exists()) {
            cache.delete();
        }
        try {
            cache.createNewFile();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create pdnsd.cache", e);
        }
    }
}
