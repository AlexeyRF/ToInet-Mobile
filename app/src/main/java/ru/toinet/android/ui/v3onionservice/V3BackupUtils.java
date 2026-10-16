package ru.toinet.android.ui.v3onionservice;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.widget.Toast;

import ru.toinet.android.R;
import ru.toinet.android.service.db.V3ClientAuthColumns;
import ru.toinet.android.ui.v3onionservice.clientauth.ClientAuthContentProvider;

import java.io.FileOutputStream;
import java.io.IOException;

public class V3BackupUtils {
    private final Context mContext;
    private final ContentResolver mResolver;

    public V3BackupUtils(Context context) {
        mContext = context;
        mResolver = mContext.getContentResolver();
    }

    public String createV3AuthBackup(String domain, String keyHash, Uri backupFile) {
        String fileText = V3ClientAuthColumns.buildV3ClientAuthFile(domain, keyHash);
        try {
            ParcelFileDescriptor pfd = mContext.getContentResolver().openFileDescriptor(backupFile, "w");
            assert pfd != null;
            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
            fos.write(fileText.getBytes());
            fos.close();
            pfd.close();
        } catch (IOException ioe) {
            return null;
        }
        return backupFile.getPath();
    }

    public void restoreClientAuthBackup(String authFileContents) {
        ContentValues fields = new ContentValues();
        String[] split = authFileContents.split(":");
        if (split.length != 4) {
            Toast.makeText(mContext, R.string.error, Toast.LENGTH_LONG).show();
            return;
        }
        fields.put(V3ClientAuthColumns.DOMAIN, split[0]);
        fields.put(V3ClientAuthColumns.HASH, split[3]);
        mResolver.insert(ClientAuthContentProvider.CONTENT_URI, fields);
        Toast.makeText(mContext, R.string.backup_restored, Toast.LENGTH_LONG).show();
    }
}
