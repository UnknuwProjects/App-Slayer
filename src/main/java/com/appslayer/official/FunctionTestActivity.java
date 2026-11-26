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

    private final List<FunctionTester.Result> results = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView statusView;

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
            }
        });
    }
}
