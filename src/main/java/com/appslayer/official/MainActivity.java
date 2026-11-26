package com.appslayer.official;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {

    private static final String UPDATE_API_URL = "https://api.github.com/repos/UnknuwProjects/App-Slayer/releases/latest";

    private View cardModeNormal;
    private View cardModeAggressive;
    private View cardModeExtreme;
    private View chipModeNormal;
    private View chipModeAggressive;
    private View chipModeExtreme;
    private TextView textCurrentModeName;
    private TextView textCurrentModeDesc;
    private TextView textNotificationState;
    private TextView textToastState;
    private TextView textLanguageState;
    private Button btnSave;
    private Button btnCheckUpdate;
    private String currentFilePath;
    private String originalFileContent;
    private String currentMode;
    private EditText editTextFileContent;
    private LinearLayout layoutFileEditor;
    private BroadcastReceiver toastOnlyReceiver;
    private LinearLayout updateNotice;
    private Process suProcess;
    private DataOutputStream suOutput;
    private BufferedReader suReader;
    private boolean rootAvailable = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private interface RootCallback {
        void run() throws Exception;
    }

    private interface RootErrorCallback {
        void onError(Exception e);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        wireHeaderButtons();
        this.textCurrentModeName = (TextView) findViewById(R.id.textCurrentModeName);
        this.textCurrentModeDesc = (TextView) findViewById(R.id.textCurrentModeDesc);
        this.cardModeNormal = findViewById(R.id.cardModeNormal);
        this.cardModeAggressive = findViewById(R.id.cardModeAggressive);
        this.cardModeExtreme = findViewById(R.id.cardModeExtreme);
        this.chipModeNormal = findViewById(R.id.chipModeNormal);
        this.chipModeAggressive = findViewById(R.id.chipModeAggressive);
        this.chipModeExtreme = findViewById(R.id.chipModeExtreme);
        this.textNotificationState = (TextView) findViewById(R.id.textNotificationState);
        this.textToastState = (TextView) findViewById(R.id.textToastState);
        this.textLanguageState = (TextView) findViewById(R.id.textLanguageState);
        this.layoutFileEditor = (LinearLayout) findViewById(R.id.layoutFileEditor);
        this.rowRootWarning = (LinearLayout) findViewById(R.id.rowRootWarning);
        Button btnRequestRoot = (Button) findViewById(R.id.btnRequestRoot);
        if (btnRequestRoot != null) {
            btnRequestRoot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestRootAccess();
                }
            });
        }
        this.editTextFileContent = (EditText) findViewById(R.id.editTextFileContent);
        this.btnSave = (Button) findViewById(R.id.btnSave);
        this.btnCheckUpdate = (Button) findViewById(R.id.btnCheckUpdate);
        this.updateNotice = (LinearLayout) findViewById(R.id.updateNotice);
        SharedPreferences sharedPreferences = getSharedPreferences(PrefsKeys.APP_PREFS, 0);
        this.currentMode = sharedPreferences.getString(PrefsKeys.MODE, "normal");
        updateModeHeader();
        updateNotificationRow();
        updateToastRow();
        updateLanguageRow();
        initRootAccess();
        checkUpdate(false);
        maybeRequestNotificationPermission();

        this.cardModeNormal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("normal");
            }
        });
        this.cardModeAggressive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("aggressive");
            }
        });
        this.cardModeExtreme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setMode("extreme");
            }
        });
        findViewById(R.id.rowNotificationToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences(PrefsKeys.APP_PREFS, 0);
                boolean enabled = prefs.getBoolean(PrefsKeys.NOTIFICATIONS, true);
                DiagLog.log("USER", "notifications: " + enabled + " -> " + (!enabled));
                Intent intent = new Intent(MainActivity.this, MyService.class);
                if (enabled) {
                    prefs.edit().putBoolean(PrefsKeys.NOTIFICATIONS, false).apply();
                    intent.setAction(MyService.ACTION_STOP_NOTIFICATION);
                    startService(intent);
                } else {
                    prefs.edit().putBoolean(PrefsKeys.NOTIFICATIONS, true).apply();
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(intent);
                    } else {
                        startService(intent);
                    }
                    maybeRequestNotificationPermission();
                }
                updateNotificationRow();
            }
        });
        findViewById(R.id.rowToastToggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SharedPreferences prefs = getSharedPreferences(PrefsKeys.APP_PREFS, 0);
                boolean enabled = !prefs.getBoolean(PrefsKeys.TOAST, false);
                DiagLog.log("USER", "toast: " + (!enabled) + " -> " + enabled);
                prefs.edit().putBoolean(PrefsKeys.TOAST, enabled).apply();
                Intent intent = new Intent("com.appslayer.official.SHOW_TOAST");
                intent.putExtra("toast", getString(enabled ? R.string.toast_enabled : R.string.toast_disabled));
                intent.setPackage(getPackageName());
                sendBroadcast(intent);
                updateToastRow();
                Toast.makeText(MainActivity.this,
                        enabled ? R.string.toast_toast_on : R.string.toast_toast_off,
                        Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.rowExecuteNow).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DiagLog.log("USER", "manual.sh run pressed");
                Toast.makeText(MainActivity.this, R.string.now_loading, Toast.LENGTH_SHORT).show();
                executeRootCommandAsync(new RootCallback() {
                    @Override
                    public void run() throws Exception {
                        runSuCommandWithOutput("sh " + AppPaths.MANUAL_SCRIPT);
                        DiagLog.log("SYS", "manual.sh finished ok");
                    }
                }, new RootErrorCallback() {
                    @Override
                    public void onError(Exception e) {
                        DiagLog.log("SYS", "manual.sh FAILED: " + e.getMessage());
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                }, null);
            }
        });
        findViewById(R.id.rowEditGamelist).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileInEditor(AppPaths.listFile(AppPaths.LIST_GAMELIST));
            }
        });
        findViewById(R.id.rowEditWhitelist).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileInEditor(AppPaths.listFile(AppPaths.LIST_WHITELIST));
            }
        });
        findViewById(R.id.rowEditExtreme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileInEditor(AppPaths.listFile(AppPaths.LIST_EXTREME));
            }
        });
        findViewById(R.id.rowEditExtrakill).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFileInEditor(AppPaths.listFile(AppPaths.LIST_KILL));
            }
        });
        findViewById(R.id.rowFloatingMonitor).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FloatingMonitorActivity.class));
            }
        });
        findViewById(R.id.rowBatteryInfo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, FloatingMonitorActivity.class));
            }
        });
        findViewById(R.id.rowLanguage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLanguagePicker();
            }
        });
        this.btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        this.btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCurrentFile();
            }
        });
        registerCustomReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.appslayer.official.ACTION_TOAST".equals(action)) {
                    String message = intent.getStringExtra("toast");
                    if (message == null) {
                        message = intent.getStringExtra("msg");
                    }
                    if (message != null) {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                    }
                }
            }
        }, new IntentFilter("com.appslayer.official.ACTION_TOAST"));
        registerCustomReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.appslayer.official.ACTION_TOAST_MODE".equals(intent.getAction())) {
                    String msg = intent.getStringExtra("msg");
                    String status = intent.getStringExtra("status");
                    Toast.makeText(context, (msg != null ? msg : "") + "\n" + (status != null ? status : ""), Toast.LENGTH_LONG).show();
                }
            }
        }, new IntentFilter("com.appslayer.official.ACTION_TOAST_MODE"));
    }

    // Receivers for the app's own broadcast actions. On Android 13+
    // these must be registered with an explicit export flag.
    private void registerCustomReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    private void setMode(final String nextMode) {
        // The daemon reads numbers, not names: 1 = Normal,
        // 2 = Aggressive, 3 = Extreme. Anything else in mode.txt
        // silently falls back to Normal on the module side.
        final String modeNumber = "extreme".equals(nextMode) ? "3"
                : "aggressive".equals(nextMode) ? "2" : "1";
        DiagLog.log("USER", "mode tap: " + nextMode + " (writes " + modeNumber + " to mode.txt)");
        executeRootCommandAsync(new RootCallback() {
            @Override
            public void run() throws Exception {
                runSuCommandWithOutput("echo '" + modeNumber + "' > " + AppPaths.MODE_FILE);
                DiagLog.log("SYS", "mode write ok: mode.txt=" + modeNumber);
            }
        }, new RootErrorCallback() {
            @Override
            public void onError(Exception e) {
                DiagLog.log("SYS", "mode write FAILED: " + e.getMessage());
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_mode_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }, new RootCallback() {
            @Override
            public void run() {
                currentMode = nextMode;
                getSharedPreferences(PrefsKeys.APP_PREFS, 0).edit()
                        .putString(PrefsKeys.MODE, currentMode).apply();
                updateModeHeader();
                int toastRes;
                if ("aggressive".equals(currentMode)) {
                    toastRes = R.string.toast_mode_aggressive;
                } else if ("extreme".equals(currentMode)) {
                    toastRes = R.string.toast_mode_extreme;
                } else {
                    toastRes = R.string.toast_mode_normal;
                }
                Toast.makeText(MainActivity.this, toastRes, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Header text, card highlight and the "active" chip all follow the
    // mode stored in preferences.
    private void updateModeHeader() {
        int nameRes;
        int descRes;
        if ("aggressive".equals(this.currentMode)) {
            nameRes = R.string.mode_name_aggressive;
            descRes = R.string.mode_desc_aggressive;
        } else if ("extreme".equals(this.currentMode)) {
            nameRes = R.string.mode_name_extreme;
            descRes = R.string.mode_desc_extreme;
        } else {
            nameRes = R.string.mode_name_normal;
            descRes = R.string.mode_desc_normal;
        }
        this.textCurrentModeName.setText(nameRes);
        this.textCurrentModeDesc.setText(descRes);
        applyModeCard(this.cardModeNormal, this.chipModeNormal, "normal".equals(this.currentMode));
        applyModeCard(this.cardModeAggressive, this.chipModeAggressive, "aggressive".equals(this.currentMode));
        applyModeCard(this.cardModeExtreme, this.chipModeExtreme, "extreme".equals(this.currentMode));
    }

    private void applyModeCard(View card, View chip, boolean active) {
        card.setBackgroundResource(active ? R.drawable.card_background_active : R.drawable.card_background);
        chip.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    // At targetSdk 36 the foreground-service notification stays
    // hidden until the user grants POST_NOTIFICATIONS at runtime —
    // declaring it in the manifest alone does nothing on Android 13+.
    // The app never asked before, so on those versions the status
    // notification silently never appeared.
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            DiagLog.log("SYS", "requesting POST_NOTIFICATIONS");
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_POST_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == 0;
            DiagLog.log("SYS", "POST_NOTIFICATIONS result: " + (granted ? "granted" : "DENIED"));
            if (!granted) {
                Toast.makeText(this, R.string.toast_notif_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateNotificationRow() {
        boolean enabled = getSharedPreferences(PrefsKeys.APP_PREFS, 0)
                .getBoolean(PrefsKeys.NOTIFICATIONS, true);
        this.textNotificationState.setText(enabled ? R.string.state_on : R.string.state_off);
        this.textNotificationState.setTextColor(enabled ? Color.rgb(76, 175, 80) : Color.rgb(154, 160, 166));
    }

    private void updateToastRow() {
        boolean enabled = getSharedPreferences(PrefsKeys.APP_PREFS, 0)
                .getBoolean(PrefsKeys.TOAST, false);
        this.textToastState.setText(enabled ? R.string.state_on : R.string.state_off);
        this.textToastState.setTextColor(enabled ? Color.rgb(76, 175, 80) : Color.rgb(154, 160, 166));
    }

    private void updateLanguageRow() {
        String saved = LocaleHelper.getSavedLanguage(this);
        int nameRes;
        if (saved.isEmpty()) {
            nameRes = R.string.language_name_system;
        } else if ("en".equals(saved)) {
            nameRes = R.string.language_name_en;
        } else if ("zh".equals(saved)) {
            nameRes = R.string.language_name_zh;
        } else if ("hi".equals(saved)) {
            nameRes = R.string.language_name_hi;
        } else if ("es".equals(saved)) {
            nameRes = R.string.language_name_es;
        } else if ("ar".equals(saved)) {
            nameRes = R.string.language_name_ar;
        } else {
            nameRes = R.string.language_name_id;
        }
        this.textLanguageState.setText(nameRes);
    }

    // Language codes in picker order; "" follows the system language.
    private static final String[] LANGUAGE_CODES = {"", "id", "en", "zh", "hi", "es", "ar"};
    private static final int[] LANGUAGE_RADIO_IDS = {
            R.id.radioLangSystem, R.id.radioLangId, R.id.radioLangEn,
            R.id.radioLangZh, R.id.radioLangHi, R.id.radioLangEs, R.id.radioLangAr
    };

    // Bottom-sheet language picker: a dark panel anchored to the
    // bottom of the screen with a radio list. Picking an entry saves
    // it through LocaleHelper and recreates, exactly like the old
    // two-language toggle did; Cancel just closes the sheet.
    private void showLanguagePicker() {
        final Dialog dialog = new Dialog(this, R.style.LanguagePickerTheme);
        dialog.setContentView(R.layout.dialog_language_picker);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams params = window.getAttributes();
            params.gravity = Gravity.BOTTOM;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        RadioGroup group = (RadioGroup) dialog.findViewById(R.id.radioLanguages);
        String saved = LocaleHelper.getSavedLanguage(this);
        int checkedIndex = 0;
        for (int i = 0; i < LANGUAGE_CODES.length; i++) {
            if (LANGUAGE_CODES[i].equals(saved)) {
                checkedIndex = i;
                break;
            }
        }
        // Check the current language before the listener is attached,
        // so opening the sheet never re-applies it.
        group.check(LANGUAGE_RADIO_IDS[checkedIndex]);
        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int checkedId) {
                for (int i = 0; i < LANGUAGE_RADIO_IDS.length; i++) {
                    if (LANGUAGE_RADIO_IDS[i] == checkedId) {
                        String next = LANGUAGE_CODES[i];
                        String current = LocaleHelper.getSavedLanguage(MainActivity.this);
                        if (!next.equals(current)) {
                            DiagLog.log("USER", "language: " + current + " -> " + next);
                            LocaleHelper.saveLanguage(MainActivity.this, next);
                            dialog.dismiss();
                            recreate();
                        }
                        return;
                    }
                }
            }
        });
        dialog.findViewById(R.id.btnLanguageCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    // The main screen has no stock action bar anymore (MainTheme);
    // the two actions that lived in it sit in the layout's header
    // row instead and run the exact same code paths: the gear opens
    // the Floating Monitor, the overflow button pops up the same
    // main menu and feeds its items to onOptionsItemSelected.
    private void wireHeaderButtons() {
        ImageButton btnMonitor = (ImageButton) findViewById(R.id.btnHeaderMonitor);
        if (btnMonitor != null) {
            btnMonitor.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, FloatingMonitorActivity.class));
                }
            });
        }
        ImageButton btnOverflow = (ImageButton) findViewById(R.id.btnHeaderOverflow);
        if (btnOverflow != null) {
            btnOverflow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    PopupMenu popup = new PopupMenu(MainActivity.this, v);
                    popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());
                    popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            return onOptionsItemSelected(item);
                        }
                    });
                    popup.show();
                }
            });
        }
    }

    // Opens MainActivity's own su session (mode writes and the
    // file editor run on it). Blocking; sets rootAvailable and
    // suError. True when the session answered the ROOT_OK
    // handshake. Split out of initRootAccess so the "request root
    // access" flow can reopen the session after a fresh grant
    // without replaying the startup toasts.
    private String suError = null;

    private boolean tryOpenSu() {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream output = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            output.writeBytes("echo ROOT_OK\n");
            output.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ROOT_OK")) {
                    // Hand the session over to runSuCommandWithOutput.
                    this.suProcess = process;
                    this.suOutput = output;
                    this.suReader = reader;
                    this.rootAvailable = true;
                    this.suError = null;
                    return true;
                }
            }
            this.suError = getString(R.string.error_root_denied);
        } catch (Exception e) {
            String msg = e.getMessage();
            // su could not even be executed (error=13): say that in
            // plain words instead of surfacing the raw exec error.
            if (msg != null && (msg.contains("error=13") || msg.contains("Permission denied"))) {
                this.suError = getString(R.string.test_root_exec_denied);
            } else {
                this.suError = msg;
            }
        }
        this.rootAvailable = false;
        return false;
    }

    private void initRootAccess() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = tryOpenSu();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        rootCheckDone = true;
                        if (ok) {
                            Toast.makeText(MainActivity.this, R.string.toast_root_ok, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.toast_root_failed,
                                            suError != null ? suError : ""),
                                    Toast.LENGTH_LONG).show();
                        }
                        updateRootRow();
                    }
                });
            }
        }).start();
    }

    // The root warning row: shown only once a root check has
    // actually finished and neither MainActivity's own session nor
    // the shared SystemStats shell is up. Before the first check
    // completes it stays hidden, so it never flashes on startup.
    private LinearLayout rowRootWarning;
    private boolean rootCheckDone = false;

    private void updateRootRow() {
        if (this.rowRootWarning == null) {
            return;
        }
        boolean available = this.rootAvailable || SystemStats.isShellVerified();
        this.rowRootWarning.setVisibility(
                this.rootCheckDone && !available ? View.VISIBLE : View.GONE);
    }

    // "Minta akses root": runs a FRESH `su -c id` (SystemStats
    // probe) — that fresh spawn is what makes Magisk/KernelSU show
    // its grant prompt. On a grant, both shells are reset/reopened
    // so every root-backed feature recovers without an app restart.
    private void requestRootAccess() {
        DiagLog.log("USER", "request root access pressed");
        final Button button = (Button) findViewById(R.id.btnRequestRoot);
        if (button != null) {
            button.setEnabled(false);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final SystemStats.RootProbe probe = SystemStats.probeRootFresh();
                if (probe.granted()) {
                    SystemStats.resetShell();
                    SystemStats.runShellCommand("echo ROOT_OK");
                    if (!rootAvailable) {
                        tryOpenSu();
                    }
                }
                DiagLog.log("SYS", "root probe: " + probe.status
                        + (probe.detail.isEmpty() ? "" : " (" + probe.detail + ")"));
                // The toast names the actual outcome — granted,
                // su not executable (not on the Superuser list),
                // su missing, no answer, or refused — instead of one
                // generic "not granted" for everything.
                final int toastRes;
                if (probe.granted()) {
                    toastRes = R.string.toast_root_granted;
                } else if ("execdenied".equals(probe.status)) {
                    toastRes = R.string.toast_root_exec_denied;
                } else if ("notfound".equals(probe.status)) {
                    toastRes = R.string.toast_root_notfound;
                } else if ("timeout".equals(probe.status)) {
                    toastRes = R.string.toast_root_timeout;
                } else {
                    toastRes = R.string.toast_root_denied;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (button != null) {
                            button.setEnabled(true);
                        }
                        rootCheckDone = true;
                        Toast.makeText(MainActivity.this, toastRes,
                                Toast.LENGTH_LONG).show();
                        updateRootRow();
                    }
                });
            }
        }).start();
    }

    private String runSuCommandWithOutput(String command) throws Exception {
        if (!this.rootAvailable || this.suOutput == null || this.suReader == null) {
            throw new IOException(getString(R.string.error_root_not_available));
        }
        this.suOutput.writeBytes(command + "\n");
        this.suOutput.writeBytes("echo EXIT:$?\n");
        this.suOutput.flush();
        StringBuilder output = new StringBuilder();
        String line;
        int exitCode = -1;
        while ((line = this.suReader.readLine()) != null) {
            if (line.startsWith("EXIT:")) {
                try {
                    exitCode = Integer.parseInt(line.substring(5).trim());
                } catch (NumberFormatException ignored) {
                }
                break;
            }
            output.append(line).append("\n");
        }
        if (exitCode != 0) {
            throw new IOException(output.toString().trim());
        }
        return output.toString().trim();
    }

    private void executeRootCommandAsync(final RootCallback task, final RootErrorCallback onError, final RootCallback onSuccess) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    task.run();
                    if (onSuccess != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    onSuccess.run();
                                } catch (Exception ignored) {
                                }
                            }
                        });
                    }
                } catch (final Exception e) {
                    if (onError != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                onError.onError(e);
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private void openFileInEditor(final String path) {
        Toast.makeText(this, R.string.now_loading, Toast.LENGTH_SHORT).show();
        executeRootCommandAsync(new RootCallback() {
            @Override
            public void run() throws Exception {
                String content = runSuCommandWithOutput("cat " + path + "; echo ===END===");
                String cleaned = content.replace("===END===", "").trim();
                currentFilePath = path;
                originalFileContent = cleaned;
                final String text = cleaned;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        editTextFileContent.setText(text);
                        layoutFileEditor.setVisibility(View.VISIBLE);
                        Toast.makeText(MainActivity.this, R.string.toast_file_opened, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, new RootErrorCallback() {
            @Override
            public void onError(Exception e) {
                Toast.makeText(MainActivity.this,
                        getString(R.string.toast_file_open_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }, null);
    }

    // Line-set diff between the content the editor was opened with
    // and what is being saved, for the diagnostic log: which entries
    // were added and which removed, by name.
    private static String describeListDiff(String before, String after) {
        java.util.Set<String> oldLines = new java.util.LinkedHashSet<>();
        java.util.Set<String> newLines = new java.util.LinkedHashSet<>();
        if (before != null) {
            for (String line : before.split("\n")) {
                if (!line.trim().isEmpty()) {
                    oldLines.add(line.trim());
                }
            }
        }
        if (after != null) {
            for (String line : after.split("\n")) {
                if (!line.trim().isEmpty()) {
                    newLines.add(line.trim());
                }
            }
        }
        java.util.List<String> added = new java.util.ArrayList<>(newLines);
        added.removeAll(oldLines);
        java.util.List<String> removed = new java.util.ArrayList<>(oldLines);
        removed.removeAll(newLines);
        StringBuilder sb = new StringBuilder();
        sb.append("+").append(added.size()).append(" -").append(removed.size());
        if (!added.isEmpty()) {
            sb.append(", added: ").append(summarizeNames(added));
        }
        if (!removed.isEmpty()) {
            sb.append(", removed: ").append(summarizeNames(removed));
        }
        return sb.toString();
    }

    private static String summarizeNames(java.util.List<String> names) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(names.size(), 10);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(names.get(i));
        }
        if (names.size() > shown) {
            sb.append(", +").append(names.size() - shown).append(" more");
        }
        return sb.toString();
    }

    private void saveCurrentFile() {
        if (this.currentFilePath != null) {
            String content = this.editTextFileContent.getText().toString();
            DiagLog.log("USER", "list save: " + new File(this.currentFilePath).getName()
                    + " (" + describeListDiff(this.originalFileContent, content) + ")");
            StringBuilder command = new StringBuilder();
            command.append("> ").append(this.currentFilePath).append("\n");
            if (!content.trim().isEmpty()) {
                for (String line : content.split("\n")) {
                    command.append("echo '").append(line.replace("'", "'\\''")).append("' >> ")
                            .append(this.currentFilePath).append("\n");
                }
            }
            final String fullCommand = command.toString();
            final String savedPath = this.currentFilePath;
            executeRootCommandAsync(new RootCallback() {
                @Override
                public void run() throws Exception {
                    for (String line : fullCommand.split("\n")) {
                        if (!line.trim().isEmpty()) {
                            runSuCommandWithOutput(line);
                        }
                    }
                    DiagLog.log("SYS", "list write ok: " + savedPath);
                }
            }, new RootErrorCallback() {
                @Override
                public void onError(Exception e) {
                    DiagLog.log("SYS", "list write FAILED: " + savedPath + ": " + e.getMessage());
                    Toast.makeText(MainActivity.this, R.string.toast_save_failed, Toast.LENGTH_LONG).show();
                }
            }, new RootCallback() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
                    layoutFileEditor.setVisibility(View.GONE);
                }
            });
        }
    }

    private String getCurrentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService("connectivity");
            if (manager == null) {
                return false;
            }
            NetworkInfo activeNetworkInfo = manager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        } catch (SecurityException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // Numeric, part-by-part version comparison ("6.0.1" > "6.0.0").
    // Returns a negative value when a < b, 0 when equal, positive when
    // a > b. Non-numeric parts count as 0.
    // The comparison itself lives in PureCalc so the JVM tests in
    // tests/ cover the exact code the update checker runs.
    private static int compareVersions(String a, String b) {
        return PureCalc.compareVersions(a, b);
    }

    // The automatic check on launch stays silent unless a real upgrade
    // exists. A manual check (CEK UPDATE button) also tells the user
    // when everything is already up to date.
    private void checkUpdate(final boolean manual) {
        if (!isNetworkAvailable()) {
            if (manual) {
                Toast.makeText(this, R.string.toast_no_internet, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(UPDATE_API_URL).openConnection();
                    connection.setRequestProperty("User-Agent", "AppSlayer");
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    JSONObject json = new JSONObject(response.toString());
                    String tag = json.optString("tag_name");
                    final String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                    final String changelog = json.optString("body");
                    final String releaseUrl = json.optString("html_url");
                    String apkUrl = null;
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset != null && asset.optString("name").endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                    final String downloadUrl = apkUrl;
                    if (!latest.isEmpty() && compareVersions(latest, getCurrentVersion()) > 0) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateNotice.setVisibility(View.VISIBLE);
                                updateNotice.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        showUpdateDialog(latest, changelog, downloadUrl, releaseUrl);
                                    }
                                });
                            }
                        });
                    } else if (manual) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, R.string.toast_up_to_date, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // No update info is not an error worth showing.
                }
            }
        }).start();
    }

    private void showUpdateDialog(final String latest, String changelog, final String apkUrl, final String releaseUrl) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_update_title))
                .setMessage(getString(R.string.dialog_update_message, latest, changelog, getCurrentVersion()))
                .setPositiveButton(getString(R.string.dialog_yes), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!isNetworkAvailable()) {
                            Toast.makeText(MainActivity.this, R.string.toast_no_internet, Toast.LENGTH_SHORT).show();
                        } else if (apkUrl != null && !apkUrl.isEmpty()) {
                            Toast.makeText(MainActivity.this, R.string.toast_downloading_update, Toast.LENGTH_SHORT).show();
                            downloadApk(apkUrl);
                        } else if (releaseUrl != null && !releaseUrl.isEmpty()) {
                            // No APK attached to the release: open its page instead.
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)));
                        }
                    }
                })
                .setNegativeButton(getString(R.string.dialog_no), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private void downloadApk(String apkUrl) {
        final String filePath = new File(getExternalFilesDir(null), "update.apk").getAbsolutePath();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
                    connection.connect();
                    InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(filePath);
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.close();
                    input.close();
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            installApk(filePath);
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void installApk(String filePath) {
        // A file:// Uri crashes with FileUriExposedException on modern
        // Android; hand the installer a content:// Uri instead.
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", new File(filePath));
        Intent intent = new Intent("android.intent.action.VIEW");
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_monitor) {
            startActivity(new Intent(this, FloatingMonitorActivity.class));
            return true;
        }
        if (id == R.id.menu_language) {
            showLanguagePicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Retry the shared root shell when the app comes to the
        // foreground, and refresh the root warning row (the kick
        // verifies on its own thread, so re-check shortly after).
        SystemStats.kickRootShell();
        updateRootRow();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateRootRow();
            }
        }, 5000);
        // Self-heal the notification toggle: the row reflects the
        // preference, but the service can be dead while the
        // preference says on (fresh install defaults to on, a ROM
        // can kill the service, ...). That state used to be a silent
        // dead end — the row said ON, nothing ran, no battery
        // history was collected. If the preference is on and the
        // service is not running, start it.
        if (getSharedPreferences(PrefsKeys.APP_PREFS, 0).getBoolean(PrefsKeys.NOTIFICATIONS, true)
                && !MyService.isRunning()) {
            DiagLog.log("SYS", "notification preference on but service not running — restarting it");
            Intent intent = new Intent(this, MyService.class);
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            } catch (Exception e) {
                DiagLog.log("SYS", "notification service restart FAILED: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        this.toastOnlyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.appslayer.official.SHOW_TOAST".equals(action)) {
                    String toast = intent.getStringExtra("toast");
                    if (toast != null && getSharedPreferences(PrefsKeys.APP_PREFS, 0).getBoolean(PrefsKeys.TOAST, false)) {
                        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show();
                    }
                } else if ("com.appslayer.official.ACTION_TOAST_MODE".equals(action)) {
                    String msg = intent.getStringExtra("msg");
                    String status = intent.getStringExtra("status");
                    Toast.makeText(context, (msg != null ? msg : "") + "\n" + (status != null ? status : ""), Toast.LENGTH_LONG).show();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.appslayer.official.SHOW_TOAST");
        filter.addAction("com.appslayer.official.ACTION_TOAST_MODE");
        registerCustomReceiver(this.toastOnlyReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        DiagLog.flushMirrorAsync(this);
        if (this.toastOnlyReceiver != null) {
            unregisterReceiver(this.toastOnlyReceiver);
        }
    }
}
