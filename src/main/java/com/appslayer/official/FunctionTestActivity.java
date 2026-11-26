package com.appslayer.official;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// Results screen for the "Tes Fungsi" self-test. The tests run
// sequentially on a background thread (FunctionTester); each
// result is appended the moment it lands, so the user watches the
// list fill in function by function: name, BERFUNGSI/GAGAL, and a
// one-line plain detail of what the test did or why it failed.
public class FunctionTestActivity extends Activity {

    private static final int REQUEST_FPS_CAPTURE = 7302;

    private final List<FunctionTester.Result> results = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView statusView;
    private boolean testsStarted = false;
    // True when this screen started the capture service just for
    // the FPS test; it is stopped again when the tests finish
    // (unless a recording is using it by then).
    private boolean captureStartedForTest = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_function_test);
        setTitle(getString(R.string.test_title));
        this.statusView = (TextView) findViewById(R.id.testStatus);
        ListView list = (ListView) findViewById(R.id.testList);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return results.size();
            }

            @Override
            public Object getItem(int position) {
                return results.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(
                            android.R.layout.simple_list_item_2, parent, false);
                }
                FunctionTester.Result result = results.get(position);
                TextView title = (TextView) view.findViewById(android.R.id.text1);
                TextView detail = (TextView) view.findViewById(android.R.id.text2);
                title.setText(result.name + " — " + getString(
                        result.ok ? R.string.test_works : R.string.test_failed));
                title.setTextColor(result.ok
                        ? Color.rgb(76, 175, 80) : Color.rgb(239, 83, 80));
                detail.setText(result.detail);
                detail.setTextColor(Color.rgb(154, 160, 166));
                return view;
            }
        };
        list.setAdapter(adapter);
        DiagLog.log("USER", "test all functions pressed");
        prepareAndRun();
    }

    // The FPS test needs a live measurement path. Non-root first:
    // when no root shell is verified and no capture is running,
    // ask for the screen-capture consent so the test measures
    // through MediaProjection; the other tests do not wait for
    // this and the FPS test still reports honestly if the user
    // declines (it will find no reading).
    private void prepareAndRun() {
        if (testsStarted) {
            return;
        }
        if (SystemStats.isShellVerified() || FpsCaptureService.isRunning()) {
            runTests();
            return;
        }
        try {
            android.media.projection.MediaProjectionManager manager =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService("media_projection");
            if (manager == null) {
                runTests();
                return;
            }
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_FPS_CAPTURE);
        } catch (Exception e) {
            runTests();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FPS_CAPTURE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            DiagLog.log("USER", "test: screen-capture consent granted");
            FpsCaptureService.start(getApplicationContext(), resultCode, data);
            captureStartedForTest = true;
        } else {
            DiagLog.log("USER", "test: screen-capture consent declined");
        }
        runTests();
    }

    private void runTests() {
        if (testsStarted) {
            return;
        }
        testsStarted = true;
        FunctionTester.runAll(this, new FunctionTester.Callback() {
            @Override
            public void onResult(final FunctionTester.Result result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        results.add(result);
                        adapter.notifyDataSetChanged();
                    }
                });
            }

            @Override
            public void onDone() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (statusView != null) {
                            statusView.setText(R.string.test_done);
                        }
                    }
                });
                // A capture started only for this test stops with
                // it; one feeding a recording keeps running.
                if (captureStartedForTest && !FpsRecorder.isRecording()) {
                    FpsCaptureService.stop(getApplicationContext());
                    captureStartedForTest = false;
                }
            }
        });
    }
}
