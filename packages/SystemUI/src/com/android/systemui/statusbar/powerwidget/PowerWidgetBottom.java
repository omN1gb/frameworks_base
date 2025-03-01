/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.powerwidget;

import com.android.systemui.statusbar.StatusBarService;
import com.android.systemui.statusbar.powerwidget.PowerWidget;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.view.ViewGroup;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashMap;

import android.util.Slog;

public class PowerWidgetBottom extends FrameLayout {
    protected static final String TAG = "PowerWidget";

    public static final String BUTTON_DELIMITER = "|";

    protected static final String BUTTONS_DEFAULT = PowerButtonBottom.BUTTON_WIFI
                             + BUTTON_DELIMITER + PowerButtonBottom.BUTTON_BLUETOOTH
                             + BUTTON_DELIMITER + PowerButtonBottom.BUTTON_GPS
                             + BUTTON_DELIMITER + PowerButtonBottom.BUTTON_SOUND;

    protected static final FrameLayout.LayoutParams WIDGET_LAYOUT_PARAMS = new FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, // width = match_parent
                                        ViewGroup.LayoutParams.WRAP_CONTENT  // height = wrap_content
                                        );

    protected static final LinearLayout.LayoutParams BUTTON_LAYOUT_PARAMS = new LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT, // width = wrap_content
                                        ViewGroup.LayoutParams.MATCH_PARENT, // height = match_parent
                                        1.0f                                    // weight = 1
                                        );

    protected static final int LAYOUT_SCROLL_BUTTON_THRESHOLD = 6;

    protected PowerWidget mPowerWidget;
    protected Context mContext;
    protected LayoutInflater mInflater;
    protected WidgetBroadcastReceiver mBroadcastReceiver = null;
    protected WidgetSettingsObserver mObserver = null;

    public PowerWidgetBottom(Context context, AttributeSet attrs) {
        super(context, attrs);

        mContext = context;
        mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // get an initial width
        updateButtonLayoutWidth();
    }

    public void setupWidget() {
        Log.i(TAG, "Clearing any old widget stuffs");
        // remove all views from the layout
        removeAllViews();

        // unregister our content receiver
        if(mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
        // unobserve our content
        if(mObserver != null) {
            mObserver.unobserve();
        }

        // clear the button instances
        PowerButtonBottom.unloadAllButtons();

        Log.i(TAG, "Setting up widget");

        String buttons = Settings.System.getString(mContext.getContentResolver(), Settings.System.WIDGET_BUTTONS);
        if(buttons == null) {
            Log.i(TAG, "Default buttons being loaded");
            buttons = BUTTONS_DEFAULT;
        }
        Log.i(TAG, "Button list: " + buttons);

        // create a linearlayout to hold our buttons
        LinearLayout ll = new LinearLayout(mContext);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);

        int buttonCount = 0;
        for(String button : buttons.split("\\|")) {
            Log.i(TAG, "Setting up button: " + button);
            // inflate our button, we don't add it to a parent and don't do any layout shit yet
            View buttonView = mInflater.inflate(R.layout.power_widget_button, null, false);

            if(PowerButtonBottom.loadButton(button, buttonView)) {
                // add the button here
                ll.addView(buttonView, BUTTON_LAYOUT_PARAMS);
                buttonCount++;
            } else {
                Log.e(TAG, "Error setting up button: " + button);
            }
        }

        // we determine if we're using a horizontal scroll view based on a threshold of button counts
        if(buttonCount > LAYOUT_SCROLL_BUTTON_THRESHOLD) {
            // we need our horizontal scroll view to wrap the linear layout
            HorizontalScrollView hsv = new HorizontalScrollView(mContext);
            // make the fading edge the size of a button (makes it more noticible that we can scroll
            hsv.setFadingEdgeLength(mContext.getResources().getDisplayMetrics().widthPixels / LAYOUT_SCROLL_BUTTON_THRESHOLD);
            hsv.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            // set the padding on the linear layout to the size of our scrollbar, so we don't have them overlap
            ll.setPadding(ll.getPaddingLeft(), ll.getPaddingTop(), ll.getPaddingRight(), hsv.getVerticalScrollbarWidth());
            hsv.addView(ll, WIDGET_LAYOUT_PARAMS);
            addView(hsv, WIDGET_LAYOUT_PARAMS);
        } else {
            // not needed, just add the linear layout
            addView(ll, WIDGET_LAYOUT_PARAMS);
        }

        // set up a broadcast receiver for our intents, based off of what our power buttons have been loaded
        setupBroadcastReceiver();
        IntentFilter filter = PowerButtonBottom.getAllBroadcastIntentFilters();
        // we add this so we can update views and such if the settings for our widget change
        filter.addAction(Settings.SETTINGS_CHANGED);
        // we need to re-setup our widget on boot complete to make sure it is visible if need be
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        // we need to detect orientation changes and update the static button width value appropriately
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        // register the receiver
        mContext.registerReceiver(mBroadcastReceiver, filter);
        // register our observer
        if(mObserver != null) {
            mObserver.observe();
        }
    }

    public void updateWidget() {
        PowerButtonBottom.updateAllButtons();
    }

    public void setupSettingsObserver(Handler handler) {
        if(mObserver == null) {
            mObserver = new WidgetSettingsObserver(handler);
        }
    }

    public void setGlobalButtonOnClickListener(View.OnClickListener listener) {
        PowerButtonBottom.setGlobalOnClickListener(listener);
    }

    public void setGlobalButtonOnLongClickListener(View.OnLongClickListener listener) {
        PowerButtonBottom.setGlobalOnLongClickListener(listener);
    }

    protected void setupBroadcastReceiver() {
        if(mBroadcastReceiver == null) {
            mBroadcastReceiver = new WidgetBroadcastReceiver();
        }
    }

    protected void updateButtonLayoutWidth() {
        // use our context to set a valid button width
        BUTTON_LAYOUT_PARAMS.width = mContext.getResources().getDisplayMetrics().widthPixels / LAYOUT_SCROLL_BUTTON_THRESHOLD;
    }

    public void visibilityToggled() {
        if (this.getVisibility() == View.VISIBLE) {
           this.setVisibility(View.GONE);
        } else {
            this.setVisibility(View.VISIBLE);
	}
    }

    public void updateVisibility() {
        Slog.d(TAG, "Updating Widget Visibility");
        // now check if we need to display the widget still
        boolean displayPowerWidget = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.EXPANDED_VIEW_WIDGET, 1) == 2;
        if (!displayPowerWidget) {
            this.setVisibility(View.GONE);
        } else {
            this.setVisibility(View.VISIBLE);
        }
    }

    // our own broadcast receiver :D
    protected class WidgetBroadcastReceiver extends BroadcastReceiver {
        public void onReceive(Context context, Intent intent) {
            if(intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                setupWidget();
                updateVisibility();
            } else if(intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                updateButtonLayoutWidth();
                setupWidget();
            } else {
                // handle the intent through our power buttons
                PowerButtonBottom.handleOnReceive(context, intent);
            }

            // update our widget
            updateWidget();
        }
    };

    // our own settings observer :D
    protected class WidgetSettingsObserver extends ContentObserver {
        public WidgetSettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            ContentResolver resolver = mContext.getContentResolver();

            // watch for display widget
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET),
                            false, this);

            // watch for changes in buttons
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS),
                            false, this);

            // watch for changes in color
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET_COLOR),
                            false, this);

            // watch for power-button specifc stuff that has been loaded
            for(Uri uri : PowerButtonBottom.getAllObservedUris()) {
                resolver.registerContentObserver(uri, false, this);
            }
        }

        public void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();

            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChangeUri(Uri uri, boolean selfChange) {
            ContentResolver resolver = mContext.getContentResolver();
            Resources res = mContext.getResources();

            // first check if our widget buttons have changed
            if(uri.equals(Settings.System.getUriFor(Settings.System.WIDGET_BUTTONS))) {
                setupWidget();
            // now check if we change visibility
            } else if(uri.equals(Settings.System.getUriFor(Settings.System.EXPANDED_VIEW_WIDGET))) {
                updateVisibility();
            }

            // do whatever the individual buttons must
            PowerButtonBottom.handleOnChangeUri(uri);

            // something happened so update the widget
            updateWidget();
        }
    }
}
