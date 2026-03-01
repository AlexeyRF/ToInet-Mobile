package ru.toinet.android.ui.v3onionservice.clientauth;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import ru.toinet.android.R;
import ru.toinet.android.service.db.V3ClientAuthColumns;

public class ClientAuthListAdapter extends CursorAdapter {
    private final LayoutInflater mLayoutInflater;

    ClientAuthListAdapter(Context context, Cursor cursor) {
        super(context, cursor, 0);
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mLayoutInflater.inflate(R.layout.layout_client_cookie_list_item, null);
    }

    @SuppressLint("Range")
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndex(V3ClientAuthColumns._ID));
        final String where = V3ClientAuthColumns._ID + "=" + id;
        TextView domain = view.findViewById(R.id.cookie_onion);
        String url = cursor.getString(cursor.getColumnIndex(V3ClientAuthColumns.DOMAIN));
        if (url.length() > 10)
            url = url.substring(0, 10) + "…" + "  .onion";
        domain.setText(url);
        SwitchCompat enabled = view.findViewById(R.id.cookie_switch);
        enabled.setChecked(cursor.getInt(cursor.getColumnIndex(V3ClientAuthColumns.ENABLED)) == 1);
        enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ContentResolver resolver = context.getContentResolver();
            ContentValues fields = new ContentValues();
            fields.put(V3ClientAuthColumns.ENABLED, isChecked);
            resolver.update(ClientAuthContentProvider.CONTENT_URI, fields, where, null);
            Toast.makeText(context, R.string.please_restart_Orbot_to_enable_the_changes, Toast.LENGTH_LONG).show();
        });
    }
}

