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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WearableService extends CanvasWatchFaceService {
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

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WearableService.Engine> mWeakReference;

        public EngineHandler(WearableService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WearableService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        Paint mTemperaturePaint;

        float mYOffsetTime;
        float mYOffsetDate;
        float mYOffsetSeparator;
        float mYOffsetTemperature;
        float mYOffsetWeatherIcon;
        float mTextSpacer;
        float mSeparatorLength;

        boolean mAmbient;
        Calendar mCalendar;

        String highTemperature = "102";
        String lowTemperature = "85";
        int weatherId = 800;

        boolean mLowBitAmbient;
        private GoogleApiClient googleApiClient;

        boolean mRegisteredTimeZoneReceiver = false;

        final Handler mUpdateTimeHandler = new EngineHandler(this);

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WearableService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = WearableService.this.getResources();
            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
            mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            mYOffsetSeparator = resources.getDimension(R.dimen.digital_y_offset_separator);
            mYOffsetTemperature = resources.getDimension(R.dimen.digital_y_offset_temperature);
            mYOffsetWeatherIcon = resources.getDimension(R.dimen.digital_y_offset_weather_icon);
            mTextSpacer = resources.getDimension(R.dimen.digital_text_temperature_spacer);
            mSeparatorLength = resources.getDimension(R.dimen.separator_length);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text_time));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text_date));

            mTemperaturePaint = new Paint();
            mTemperaturePaint = createTextPaint(resources.getColor(R.color.digital_text_temperature_high));

            mCalendar = Calendar.getInstance();

            //For Weather Update
            googleApiClient = new GoogleApiClient.Builder(WearableService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            // Connect for receiving message from mobile
            if (googleApiClient != null && !googleApiClient.isConnected()) {
                googleApiClient.connect();
            }

            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WearableService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WearableService.this.unregisterReceiver(mTimeZoneReceiver);

            // Disconnect client which was used for receiving message from mobile
            if (googleApiClient != null && googleApiClient.isConnected()) {
                googleApiClient.disconnect();
            }
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            Resources resources = WearableService.this.getResources();
            mTimePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_time));
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_date));
            mTemperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_text_size_temperature));
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
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mTemperaturePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            // build and paint the time
            String formatString = getApplicationContext().getString(R.string.format_wear_friendly_time);
            String timeString = String.format(formatString, mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE));
            float widthOfTime = mTimePaint.measureText(timeString);
            float timeXOffset = bounds.centerX() - (widthOfTime / 2);
            canvas.drawText(timeString, timeXOffset, mYOffsetTime, mTimePaint);

            // build and paint the date
            Date today = new Date(mCalendar.getTimeInMillis());
            formatString = getApplicationContext().getString(R.string.format_wear_friendly_date);
            String dateSting = String.format(formatString, today, today, today, today);
            float widthOfDate = mDatePaint.measureText(dateSting);
            float dateXOffset = bounds.centerX() - (widthOfDate / 2);
            canvas.drawText(dateSting, dateXOffset, mYOffsetDate, mDatePaint);

            // paint the separator
            canvas.drawLine(bounds.centerX() - (mSeparatorLength / 2), mYOffsetSeparator, bounds.centerX() + (mSeparatorLength / 2), mYOffsetSeparator, mDatePaint);

            // build the temperature string but don't paint it yet
            String temperatureString = String.format("%s\u00b0 %s\u00b0", highTemperature, lowTemperature);
            float widthOfTemperature = mTemperaturePaint.measureText(temperatureString);

            // build and paint the weather icon (maybe)
            float iconXOffset = 0;
            float iconWidth = 0;
            if (!isInAmbientMode()) {
                Bitmap weatherIcon = BitmapFactory.decodeResource(getResources(), getIconResourceForWeatherCondition(weatherId));
                iconWidth = weatherIcon.getWidth() + mTextSpacer;
                iconXOffset = bounds.centerX() - (iconWidth / 2) - (widthOfTemperature / 2);
                canvas.drawBitmap(weatherIcon, iconXOffset, mYOffsetWeatherIcon, mTemperaturePaint);
            }

            // calculate the x-offset and paint the temperature
            float temperatureXOffset;
            if (iconXOffset == 0) {
                temperatureXOffset = bounds.centerX() - (widthOfTemperature / 2);
            } else {
                temperatureXOffset = iconXOffset + iconWidth - (mTextSpacer / 2);
            }
            canvas.drawText(temperatureString, temperatureXOffset, mYOffsetTemperature, mTemperaturePaint);
        }

        /**
         * Helper method to provide the icon resource id according to the weather condition id returned
         * by the OpenWeatherMap call.
         * @param weatherId from OpenWeatherMap API response
         * @return resource id for the corresponding icon. -1 if no relation is found.
         */
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
            return R.drawable.ic_status;
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

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    processConfigurationFor(item);
                }
            }

            dataEvents.release();
            invalidate();
        }

        private void processConfigurationFor(DataItem item) {
            if ("/weather_data".equals(item.getUri().getPath())) {
                DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                if (dataMap.containsKey("HIGH_TEMP"))
                    highTemperature = dataMap.getString("HIGH_TEMP");
                if (dataMap.containsKey("LOW_TEMP"))
                    lowTemperature = dataMap.getString("LOW_TEMP");
                if (dataMap.containsKey("WEATHER_ID"))
                    weatherId = dataMap.getInt("WEATHER_ID");
            }
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("Connection","Suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("Connection","Fail" + connectionResult.getErrorMessage());
        }
    }
}
