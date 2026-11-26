package com.appslayer.official;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

public class PermissionActivity extends Activity {

    private static final int PERMISSION_REQUEST_CODE = 123;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") == PackageManager.PERMISSION_GRANTED) {
            finish();
        } else {
            requestPermissions(new String[]{"android.permission.ACCESS_FINE_LOCATION"}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
