/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;


/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WearWatchFace extends CanvasWatchFaceService {
    public static final String WEATHER_ID = "weather_id";
    public static final String MAX_TEMPERATURE = "max_temp";
    public static final String MINIMUM_TEMPERATURE = "min_temp";
    public static final String DESCRIPTION = "description";
    private static final String GET_LATEST_DATA = "/latest_data";
    private static final String IS_METRIC = "is_metric";


    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);


    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = "SUNSHINE_WATCH_FACE";
    private GoogleApiClient mGoogleApiClient;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d(TAG, "Service started");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

    }

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private Collection<String> getNodes() {
        HashSet<String> results = new HashSet<>();
        NodeApi.GetConnectedNodesResult nodes =
                Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();

        for (Node node : nodes.getNodes()) {
            results.add(node.getId());
        }

        return results;
    }

    private String formatTemperature(boolean isMetric, double temperature) {
        // Data stored in Celsius by default.  If user prefers to see in Fahrenheit, convert
        // the values here.
        String suffix = "\u00B0";
        if (!isMetric) {
            temperature = (temperature * 1.8) + 32;
        }

        // For presentation, assume the user doesn't care about tenths of a degree.
        return String.format(getString(R.string.format_temperature), temperature);
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WearWatchFace.Engine> mWeakReference;

        public EngineHandler(WearWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WearWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        TextPaint mTimePaint;
        TextPaint mDatePaint;
        TextPaint mTemperaturePaint;
        int iconSize;


        double minTemp;
        double maxTemp;
        int weatherId;
        String weatherDescription;
        boolean dataLoaded;
        boolean isMetric;
        long requestTime;

        HashMap<Integer, Bitmap> iconsMap = new HashMap<>();
        boolean mAmbient;
        Calendar mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime = GregorianCalendar.getInstance();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WearWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = WearWatchFace.this.getResources();

            mYOffset = resources.getDimension(R.dimen.top_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));

            mTimePaint = new TextPaint();
            mTimePaint.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTimePaint.setColor(Color.WHITE);
            mTimePaint.setAntiAlias(true);

            mDatePaint = new TextPaint();
            mDatePaint.setAntiAlias(true);
            mDatePaint.setColor(resources.getColor(R.color.secondary_color));
            //createTextPaint(resources.getColor(R.color.secondary_color));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.date_text_size));
            mDatePaint.setTextAlign(Paint.Align.CENTER);
            mDatePaint.setStrokeWidth(getResources().getDisplayMetrics().density*1);


            mTemperaturePaint =new TextPaint();
            mTemperaturePaint.setTextSize(resources.getDimension(R.dimen.temperature_text_size));
            mTemperaturePaint.setColor(resources.getColor(R.color.secondary_color));
            mTemperaturePaint.setAntiAlias(true);
            mTemperaturePaint.setTextAlign(Paint.Align.CENTER);
            mTime = GregorianCalendar.getInstance();

            iconSize = resources.getDimensionPixelSize(R.dimen.icon_size);

            initDataLayerApi();
        }
        private void initDataLayerApi() {

            mGoogleApiClient = new GoogleApiClient.Builder(WearWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle bundle) {
                            Log.d(TAG, "Data API Connected");
                            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
                            initWeatherStats();

                        }

                        @Override
                        public void onConnectionSuspended(int i) {
                            Log.d(TAG, "Connection supended");
                        }
                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                            Log.e(TAG, connectionResult.getErrorMessage());
                        }
                    })
                    .build();
        }

        private void initWeatherStats() {
            Uri uri = new Uri.Builder()
                    .scheme("wear")
                    .path("/weather")
                    .build();
            Wearable.DataApi.getDataItems(mGoogleApiClient, uri)
                    .setResultCallback(
                            new ResultCallback<DataItemBuffer>() {
                                @Override
                                public void onResult(DataItemBuffer dataItems) {
                                    // Get Info
                                    for (DataItem item : dataItems) {
                                        updateWeatherFromDataItem(item);
                                    }
                                    if (!dataLoaded) {
                                        sendDataRequest();
                                    }
                                }
                            }
                    );

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            if (!mAmbient) {
                float density=getResources().getDisplayMetrics().density;
                float yOffset = mYOffset;
                mTime.setTimeInMillis(System.currentTimeMillis());

                String date = String.format("%ta,%tb %te %tY", mTime, mTime, mTime, mTime).toUpperCase();

                yOffset += drawTime(canvas)+mDatePaint.getFontMetrics().bottom+10*density;


                canvas.drawText(date, canvas.getWidth()/2, yOffset, mDatePaint);
                float centerX=canvas.getWidth()/2;
                canvas.drawLine(centerX-30*density,yOffset+15*density,
                        centerX+30*density,yOffset+15*density,mDatePaint);
                yOffset = yOffset + density * 30;

                if (dataLoaded) {
                    drawWeather(canvas,yOffset);
                } else {
                    yOffset = yOffset + density * 10;
                    canvas.drawText("waiting for data", canvas.getWidth()/2, yOffset, mTemperaturePaint);
                    sendDataRequest();
                }


            } else {

                String time = String.format("%tR", mTime);
                Rect textBounds = new Rect();
                mTimePaint.getTextBounds(time, 0, time.length(), textBounds);
                mXOffset = (canvas.getWidth() - textBounds.width()) / 2;
                float yOffset = (canvas.getHeight()) / 2 + mTimePaint.descent();
                canvas.drawText(time, mXOffset, yOffset, mTimePaint);

            }


        }
        private int drawTime(Canvas canvas){
            String time = String.format("%tT", mTime);
            Spannable wordtoSpan = new SpannableString(time);

            StyleSpan boldSpan = new StyleSpan(Typeface.BOLD);
            wordtoSpan.setSpan(boldSpan, 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            StaticLayout layout = new StaticLayout(wordtoSpan, mTimePaint, canvas.getWidth(), Layout.Alignment.ALIGN_CENTER, 1, 0, false);
            canvas.save();
            canvas.translate(0,mYOffset);
            layout.draw(canvas);
            canvas.restore();
            return layout.getHeight();
        }

        private int drawWeather(Canvas canvas,float yOffset){
            Spannable wordtoSpan = new SpannableString(String.format("      %s %s",formatTemperature(isMetric, maxTemp), formatTemperature(isMetric, minTemp)));
            CenteredImageSpan span=new CenteredImageSpan(WearWatchFace.this,getIconResourceForWeatherCondition(weatherId));

            wordtoSpan.setSpan(span, 0, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

            // Span to set text color to some RGB value
             ForegroundColorSpan fcs = new ForegroundColorSpan(Color.WHITE);
            // Span to make text bold
             StyleSpan bss = new StyleSpan(android.graphics.Typeface.BOLD);
            String m=formatTemperature(isMetric, maxTemp);
            wordtoSpan.setSpan(fcs, 6, 6+m.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            // make them also bold
            wordtoSpan.setSpan(bss, 6, 6+m.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);

            StaticLayout layout = new StaticLayout(wordtoSpan, mTemperaturePaint, canvas.getWidth(), Layout.Alignment.ALIGN_CENTER, 1, 0, false);
            canvas.save();
            canvas.translate(0,yOffset);
            layout.draw(canvas);
            canvas.restore();
            return layout.getHeight();
        }


        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WearWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WearWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WearWatchFace.this.getResources();
            boolean isRound = insets.isRound();

        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }



        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                Log.d(TAG, "visible");
                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(Calendar.ZONE_OFFSET);
                mTime.setTimeInMillis(System.currentTimeMillis());
            } else {
                Log.d(TAG, "invisible");
                unregisterReceiver();
                Wearable.DataApi.removeListener(mGoogleApiClient, this);
                mGoogleApiClient.disconnect();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private Bitmap getWeatherIcon(int weatherId) {
            if (!iconsMap.containsKey(weatherId)) {
                Bitmap bitmap=BitmapFactory.decodeResource(
                        getResources(),
                        getIconResourceForWeatherCondition(weatherId)
                );
                int height=getResources().getDimensionPixelSize(R.dimen.date_text_size);
                int width=bitmap.getWidth()/bitmap.getHeight()*height;
                Bitmap scaled=Bitmap.createScaledBitmap(bitmap,width,height,true);

                iconsMap.put(weatherId,
                        scaled);
            }
            return iconsMap.get(weatherId);
        }


        private int getIconResourceForWeatherCondition(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }


        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "Data changed");
            for (DataEvent event : dataEventBuffer) {
                Uri uri = event.getDataItem().getUri();
                String path = uri.getPath();
                Log.d(TAG, "Event " + path);
                if (path.equals("/weather")) {
                    updateWeatherFromDataItem(event.getDataItem());
                }
            }

        }

        private void updateWeatherFromDataItem(DataItem item) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            dataLoaded = true;
            weatherId = dataMap.getInt(WEATHER_ID);
            maxTemp = dataMap.getDouble(MAX_TEMPERATURE);
            minTemp = dataMap.getDouble(MINIMUM_TEMPERATURE);
            weatherDescription = dataMap.getString(DESCRIPTION);
            isMetric = dataMap.getBoolean(IS_METRIC);

        }


        private void sendDataRequest() {

            if ((System.currentTimeMillis() - requestTime) < 2000) return;
            if (!mGoogleApiClient.isConnected()) return;
            requestTime = System.currentTimeMillis();
            AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    for (String node : getNodes()) {
                        Log.d(TAG, "Sent update data request");
                        Wearable.MessageApi.sendMessage(
                                mGoogleApiClient, node, GET_LATEST_DATA, new byte[0]).setResultCallback(
                                new ResultCallback<MessageApi.SendMessageResult>() {
                                    @Override
                                    public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                        if (!sendMessageResult.getStatus().isSuccess()) {
                                            Log.e(TAG, "Failed to send message with status code: "
                                                    + sendMessageResult.getStatus().getStatusCode());
                                        }
                                    }
                                }
                        );
                    }
                    return null;
                }
            };
            task.execute();


        }
    }


}
