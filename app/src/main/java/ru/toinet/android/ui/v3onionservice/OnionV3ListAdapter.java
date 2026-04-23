package ru.toinet.android.ui.v3onionservice;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;

import ru.toinet.android.R;
import ru.toinet.android.service.db.OnionServiceColumns;

public class OnionV3ListAdapter extends CursorAdapter {

    private final LayoutInflater mLayoutInflater;

    OnionV3ListAdapter(Context context, Cursor cursor) {
        super(context, cursor, 0);
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mLayoutInflater.inflate(R.layout.layout_hs_list_item, parent, false);
    }

    @SuppressLint("Range")
    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        int id = cursor.getInt(cursor.getColumnIndex(OnionServiceColumns._ID));
        final String where = BaseColumns._ID + "=" + id;
        TextView localPort = view.findViewById(R.id.hs_port);
        localPort.setText(String.format("%s\n%s", context.getString(R.string.local_port),
                cursor.getString(cursor.getColumnIndex(OnionServiceColumns.PORT))));

        TextView onionPort = view.findViewById(R.id.onion_port);
        onionPort.setText(String.format("%s\n%s", context.getString(R.string.onion_port),
                cursor.getString(cursor.getColumnIndex(OnionServiceColumns.ONION_PORT))));

        TextView name = view.findViewById(R.id.hs_name);
        name.setText(cursor.getString(cursor.getColumnIndex(OnionServiceColumns.NAME)));
        TextView domain = view.findViewById(R.id.hs_onion);
        domain.setText(cursor.getString(cursor.getColumnIndex(OnionServiceColumns.DOMAIN)));

        SwitchCompat enabled = view.findViewById(R.id.hs_switch);
        enabled.setChecked(cursor.getInt(cursor.getColumnIndex(OnionServiceColumns.ENABLED)) == 1);
        enabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ContentResolver resolver = context.getContentResolver();
            ContentValues fields = new ContentValues();
            fields.put(OnionServiceColumns.ENABLED, isChecked);
            resolver.update(OnionServiceContentProvider.CONTENT_URI, fields, where, null);
            Toast.makeText(context, R.string.please_restart_Orbot_to_enable_the_changes, Toast.LENGTH_SHORT).show();
        });
    }
}
