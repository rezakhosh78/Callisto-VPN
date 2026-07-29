package com.rkh.callisto;

import android.app.Application;

import com.rkh.callisto.log.DebugLog;

/** Installs diagnostics before either the UI or tunnel service is created. */
public final class CallistoApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.initialize(this);
        DebugLog.installCrashHandler(this);
        DebugLog.importLastProcessExit(this);
    }
}
