package ru.toinet.android.byedpi.utility;

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
            
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = br.readLine()) != null) {
                Log.e(TAG, "STDERR: " + line);
            }
            
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
            Runtime.getRuntime().exec("kill " + pid).waitFor();
            file.delete();
        } catch (Exception e) {
            Log.e(TAG, "Failed to kill pid file: " + f, e);
        }
    }

    public static void makePdnsdConf(Context context, String dns, int port) {
        String dir = context.getFilesDir().getAbsolutePath();
        String conf = String.format(context.getString(R.string.pdnsd_conf), dir, dir, dns, port);
        
        File f = new File(dir + "/pdnsd.conf");
        if (f.exists()) f.delete();
        
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(conf.getBytes());
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write pdnsd.conf", e);
        }
        
        File cache = new File(dir + "/pdnsd.cache");
        if (!cache.exists()) {
            try {
                cache.createNewFile();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create pdnsd.cache", e);
            }
        }
    }
}
