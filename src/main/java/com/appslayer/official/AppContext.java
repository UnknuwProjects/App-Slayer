package com.appslayer.official;

import android.app.Application;
import android.content.Context;

public class AppContext extends Application {

    private static AppContext instance;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagLog.log(this, "SYS", "AppSlayer process start (Android SDK "
                + android.os.Build.VERSION.SDK_INT + ")");
    }

    public static Context get() {
        return instance;
    }
}
