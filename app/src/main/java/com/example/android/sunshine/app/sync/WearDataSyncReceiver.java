package com.example.android.sunshine.app.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;

public class WearDataSyncReceiver extends BroadcastReceiver {
    public WearDataSyncReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        WearSyncService.sendUpdatesToDevice(context);
    }
}
