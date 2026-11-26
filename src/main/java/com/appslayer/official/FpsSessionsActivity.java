package com.appslayer.official;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// The recorded FPS sessions, newest first. Reads the same
// filesDir/fps_sessions/ directory the recorder writes to (through
// FpsSession). A tap opens the session report; a long press deletes
// the session after a confirmation.
public class FpsSessionsActivity extends Activity {

    public static final String EXTRA_SESSION_PATH = "session_path";

    private List<FpsSession> sessions = new ArrayList<>();
    private BaseAdapter adapter;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.activity_fps_sessions);
        setTitle(getString(R.string.fps_sessions_title));
        ListView list = (ListView) findViewById(R.id.sessionsList);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return sessions.size();
            }

            @Override
            public Object getItem(int position) {
                return sessions.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView;
                if (view == null) {
                    view = getLayoutInflater().inflate(R.layout.item_fps_session, parent, false);
                }
                FpsSession session = sessions.get(position);
                TextView title = (TextView) view.findViewById(R.id.sessionRowTitle);
                TextView subtitle = (TextView) view.findViewById(R.id.sessionRowSubtitle);
                title.setText(appLabel(FpsSessionsActivity.this, session.packageName));
                String date = new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                        .format(new Date(session.startTimeMs));
                if (session.samples.isEmpty()) {
                    // A recording whose FPS sources never answered:
                    // say so on the row instead of inventing figures.
                    subtitle.setText(date + " · " + formatDuration(session.durationMs())
                            + " · " + getString(R.string.fps_session_no_data));
                } else {
                    PureCalc.FpsSummary summary = PureCalc.summarizeFps(session.fpsArray());
                    String avg = Double.isNaN(summary.avg)
                            ? "—"
                            : getString(R.string.fps_avg_short, summary.avg);
                    subtitle.setText(date + " · " + formatDuration(session.durationMs()) + " · " + avg);
                }
                return view;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                FpsSession session = sessions.get(position);
                DiagLog.log("USER", "session opened: " + session.file.getName()
                        + " (" + appLabel(FpsSessionsActivity.this, session.packageName) + ")");
                Intent intent = new Intent(FpsSessionsActivity.this, FpsReportActivity.class);
                intent.putExtra(EXTRA_SESSION_PATH, session.file.getAbsolutePath());
                startActivity(intent);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                confirmDelete(sessions.get(position));
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<FpsSession> loaded = FpsSession.list(FpsSessionsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sessions = loaded;
                        adapter.notifyDataSetChanged();
                        TextView empty = (TextView) findViewById(R.id.sessionsEmpty);
                        if (empty != null) {
                            empty.setVisibility(loaded.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final FpsSession session) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.fps_delete_confirm))
                .setPositiveButton(getString(R.string.dialog_delete), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean deleted = session.file.delete();
                        DiagLog.log("USER", "session deleted: " + session.file.getName()
                                + " -> " + (deleted ? "ok" : "FAILED"));
                        Toast.makeText(FpsSessionsActivity.this,
                                R.string.toast_session_deleted, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    // Human label for a package ("Mobile Legends"), falling back to
    // the package name, then to the unknown-app string when the
    // session recorded no foreground app at all.
    public static String appLabel(Context context, String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return context.getString(R.string.fps_unknown_app);
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {
        }
        return packageName;
    }

    // "H:MM:SS" past an hour, "M:SS" below it — session lengths
    // are usually minutes.
    public static String formatDuration(long durationMs) {
        long totalSeconds = durationMs / 1000;
        if (totalSeconds >= 3600) {
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, totalSeconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }
}
