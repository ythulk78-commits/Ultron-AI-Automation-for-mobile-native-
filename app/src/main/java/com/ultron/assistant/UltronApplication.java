package com.ultron.assistant;

import android.app.Application;

/** Gives UltronCore (and anything else) a Context without needing an Activity/Service handle. */
public class UltronApplication extends Application {

    private static UltronApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static UltronApplication get() {
        return instance;
    }
}
