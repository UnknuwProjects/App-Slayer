package com.appslayer.official;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class ToastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String toast = intent.getStringExtra("toast");
        if (toast != null) {
            Toast.makeText(context, toast, Toast.LENGTH_LONG).show();
        }
    }
}
