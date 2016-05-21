package com.example.android.sunshine.app.sync;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.bumptech.glide.util.Util;
import com.example.android.sunshine.app.R;
import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class WearSyncService  extends WearableListenerService {
    private static final String TAG = "WearSyncService";


    private static final String GET_LATEST_DATA = "/latest_data";

    public static final String COUNT_PATH = "/count";

    public static final String WEATHER_ID ="weather_id";
    public static final String MAX_TEMPERATURE="max_temp";
    public static final String MINIMUM_TEMPERATURE="min_temp";
    public static final String DESCRIPTION ="description";
    private static final String IS_METRIC ="is_metric" ;


    GoogleApiClient mGoogleApiClient;

    private static final String[] FORECAST_COLUMNS = {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
    };
    // these indices must match the projection
    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_SHORT_DESC = 1;
    private static final int INDEX_MAX_TEMP = 2;
    private static final int INDEX_MIN_TEMP = 3;

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        LOGD(TAG, "onDataChanged: " + dataEvents);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        LOGD(TAG, "onMessageReceived: " + messageEvent);

        // Check to see if the message is to start an activity
        if (messageEvent.getPath().equals(GET_LATEST_DATA)) {
            sendLatestDataToDevice();
        }
    }

    private void sendLatestDataToDevice(){
        String location = Utility.getPreferredLocation(this);
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                location, System.currentTimeMillis());
        Cursor data = getContentResolver().query(weatherForLocationUri, FORECAST_COLUMNS, null,
                null, WeatherContract.WeatherEntry.COLUMN_DATE + " ASC");
        if (data == null) {
            return;
        }
        if (!data.moveToFirst()) {
            data.close();
            return;
        }

        int weatherId = data.getInt(INDEX_WEATHER_ID);
        String description = data.getString(INDEX_SHORT_DESC);
        double maxTemp = data.getDouble(INDEX_MAX_TEMP);
        double minTemp = data.getDouble(INDEX_MIN_TEMP);
        data.close();

        PutDataMapRequest dataMap = PutDataMapRequest.create("/weather");
        dataMap.getDataMap().putInt(WEATHER_ID,weatherId);
        dataMap.getDataMap().putString(DESCRIPTION,description);
        dataMap.getDataMap().putDouble(MAX_TEMPERATURE,maxTemp);
        dataMap.getDataMap().putDouble(MINIMUM_TEMPERATURE,minTemp);
        dataMap.getDataMap().putBoolean(IS_METRIC, Utility.isMetric(this));
        //dataMap.getDataMap().putLong("time", new Date().getTime());
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();
        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        LOGD(TAG, "Weather update have been sent " + dataItemResult.getStatus()
                                .isSuccess());
                    }
                });
    }



    public static void LOGD(final String tag, String message) {

     //   if (Log.isLoggable(tag, Log.DEBUG)) {
            Log.d(tag, message);
       // }
    }




    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent!=null && intent.getBooleanExtra("send_data",false)) {
            if (mGoogleApiClient.isConnected()) {
                sendLatestDataToDevice();
            } else {
                mGoogleApiClient.registerConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        sendLatestDataToDevice();
                    }

                    @Override
                    public void onConnectionSuspended(int i) {

                    }
                });
                mGoogleApiClient.connect();
            }
        }
        return START_STICKY;
    }


    public static void sendUpdatesToDevice(Context context) {
        Intent intent=new Intent(context,WearSyncService.class);
        intent.putExtra("send_data",true);
        context.startService(intent);
    }
}
