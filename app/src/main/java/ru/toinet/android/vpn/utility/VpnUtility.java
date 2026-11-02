package ru.toinet.android.vpn.utility;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import ru.toinet.android.R;

public class VpnUtility {
    private static final String TAG = "VpnUtility";

    public static int exec(String[] cmd) {
        try {
            Log.d(TAG, "Executing: " + java.util.Arrays.toString(cmd));
            Process p = Runtime.getRuntime().exec(cmd);
            
            Thread errThread = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.e(TAG, "STDERR: " + line);
                    }
                } catch (Exception ignored) {}
            });
            errThread.start();

            Thread outThread = new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "STDOUT: " + line);
                    }
                } catch (Exception ignored) {}
            });
            outThread.start();
            
            int ret = p.waitFor();
            Log.d(TAG, "Process exited with: " + ret);
            return ret;
        } catch (Exception e) {
            Log.e(TAG, "exec failed", e);
            return -1;
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
                str.append(new String(buf, 0, len));
            }
            int pid = Integer.parseInt(str.toString().trim().replace("\n", ""));
            try {
                android.os.Process.killProcess(pid);
            } catch (Exception e) {
                Runtime.getRuntime().exec("kill " + pid).waitFor();
            }
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill pid file: " + f, e);
        }
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        String dir = context.getFilesDir().getAbsolutePath();
        String dnsList = dns;
        if (port == 53 && dns != null && !dns.contains(",")) {
            String trimmedDns = dns.trim();
            if (!trimmedDns.equals("77.88.8.8") && !trimmedDns.equals("1.1.1.1")) {
                dnsList = trimmedDns + ", 77.88.8.8, 1.1.1.1";
            } else if (trimmedDns.equals("77.88.8.8")) {
                dnsList = trimmedDns + ", 8.8.8.8, 1.1.1.1";
            } else {
                dnsList = trimmedDns + ", 77.88.8.8, 8.8.8.8";
            }
        }
        String conf = String.format(context.getString(R.string.pdnsd_conf), dir, dir, dnsList, port);
        
        File f = new File(dir + "/pdnsd.conf");
        if (f.exists()) f.delete();
        
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(conf.getBytes());
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

