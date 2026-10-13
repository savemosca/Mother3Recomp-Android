package com.ylports.mother3recomp;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ylports.mother3recomp.cloud.CloudSaves;

import org.gbarecomp.GbaGameActivity;
import org.gbarecomp.GbaGameConfig;
import org.gbarecomp.GbaSetupActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * MOTHER 3 front screen.
 *
 * Without embedded assets it is the shared ROM/BIOS setup screen plus the
 * RetroAchievements and cloud-save buttons. An APK with the ROM and BIOS
 * embedded shows a small launcher instead (PLAY, RetroAchievements, cloud
 * saves), which the game returns to when it exits.
 *
 * Native stdout/stderr are redirected by gbarecomp to
 * files/android-runtime.log. If SDL_main returns or the previous native
 * process died, surface the tail here instead of making PLAY appear to do
 * nothing.
 */
public final class Mother3SetupActivity extends GbaSetupActivity {
    private static final String PREFS = "mother3_diagnostics";
    private static final String LAST_LOG = "last_log_snapshot";
    private Button raButton;
    private Button cloudButton;
    private CloudSaves cloudSaves;
    private GbaGameConfig gameConfig;
    /** The APK embeds the ROM/BIOS: this screen is the launcher. */
    private boolean launcherMode;
    private Button playButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Avoid a crash/relaunch loop while the Android port is in bring-up.
        getSharedPreferences("setup", MODE_PRIVATE)
            .edit()
            .putBoolean("skip_launcher_on_boot", false)
            .apply();
        gameConfig = GbaGameConfig.load(this);
        cloudSaves = new CloudSaves(this, gameConfig.romFile,
            gameConfig.romSha1.length > 0 ? gameConfig.romSha1[0] : "",
            this::refreshCloudButton);
        super.onCreate(savedInstanceState);
        if (!launcherMode) {
            addRetroAchievementsButton();
            addCloudSavesButton();
        }
    }

    /** Embedded ROM/BIOS: show the launcher instead of starting at once. */
    @Override
    protected void onBundledAssets() {
        launcherMode = true;
        setContentView(buildLauncher());
        playButton.requestFocus();
    }

    /** START on the gamepad plays from anywhere on the launcher. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (launcherMode && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_START) {
            if (event.getAction() == KeyEvent.ACTION_UP) playButton.performClick();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** PLAY (and auto-start) go through here: sync the save first. */
    @Override
    public void startActivity(Intent intent) {
        if (cloudSaves == null || intent.getComponent() == null
                || !GbaGameActivity.class.getName().equals(intent.getComponent().getClassName())) {
            super.startActivity(intent);
            return;
        }
        cloudSaves.syncBeforePlay(() -> {
            // A sync can outlive this screen; a newer one handles PLAY then.
            if (isDestroyed()) return;
            cloudSaves.gameStarting();
            Mother3SetupActivity.super.startActivity(intent);
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // The shared setup screen rebuilds its content on rotation/resize; the
        // launcher's centred column needs no rebuild.
        super.onConfigurationChanged(newConfig);
        if (launcherMode) return;
        addRetroAchievementsButton();
        addCloudSavesButton();
    }

    // ── Launcher (embedded ROM/BIOS) ──────────────────────────────────────

    private View buildLauncher() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(Color.rgb(9, 11, 24));
        page.setPadding(dp(24), dp(16), dp(24), dp(16));

        TextView eyebrow = label("RECOMPILED FOR ANDROID", 12, Color.rgb(78, 216, 255));
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        eyebrow.setLetterSpacing(0.16f);
        page.addView(eyebrow);

        TextView title = label(gameConfig.title, 30, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title, column(-2, 0, dp(2), dp(18)));

        playButton = launcherButton("PLAY", true);
        playButton.setOnClickListener(view -> startActivity(new Intent(this, gameActivityClass())));
        page.addView(playButton, column(dp(58), 0, 0, dp(12)));

        raButton = launcherButton("", false);
        raButton.setOnClickListener(view -> showRetroAchievementsDialog());
        page.addView(raButton, column(dp(48), 0, 0, dp(10)));
        refreshRetroAchievementsButton();

        cloudButton = launcherButton("", false);
        cloudButton.setOnClickListener(view -> cloudSaves.showSettings());
        page.addView(cloudButton, column(dp(48), 0, 0, dp(14)));
        refreshCloudButton();

        page.addView(label("START plays. Quitting the game returns here.", 13,
            Color.rgb(132, 143, 173)));
        return page;
    }

    private Button launcherButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(primary ? 18 : 14);
        button.setTextColor(primary ? Color.rgb(8, 16, 27) : Color.WHITE);
        button.setFocusable(true);
        // The custom background hides the platform focus highlight, so the
        // focused button (D-pad navigation) gets its own yellow border.
        int fill = primary ? Color.rgb(78, 216, 255) : Color.rgb(20, 25, 48);
        int edge = primary ? Color.TRANSPARENT : Color.rgb(48, 58, 92);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, rounded(fill, Color.rgb(255, 211, 78)));
        states.addState(new int[] {android.R.attr.state_focused}, rounded(fill, Color.rgb(255, 211, 78)));
        states.addState(new int[] {}, rounded(fill, edge));
        button.setBackground(states);
        return button;
    }

    private Drawable rounded(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(14));
        shape.setStroke(dp(3), stroke);
        return shape;
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    /** A fixed-width centred row in the launcher column. */
    private LinearLayout.LayoutParams column(int height, int left, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            height == -2 ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(420),
            height == -2 ? LinearLayout.LayoutParams.WRAP_CONTENT : height);
        params.setMargins(left, top, 0, bottom);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── RetroAchievements ─────────────────────────────────────────────────

    /** Inserts the RetroAchievements button above the shared PLAY footer. */
    private void addRetroAchievementsButton() {
        raButton = addSetupButton(view -> showRetroAchievementsDialog());
        refreshRetroAchievementsButton();
    }

    /** Adds a full-width button above the shared PLAY footer. */
    private Button addSetupButton(View.OnClickListener listener) {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;
        View page = content.getChildAt(0);
        if (!(page instanceof LinearLayout)) return null;
        LinearLayout column = (LinearLayout) page;
        Button button = new Button(this);
        button.setAllCaps(true);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int margin = Math.round(8 * getResources().getDisplayMetrics().density);
        params.setMargins(margin * 2, margin, margin * 2, 0);
        column.addView(button, Math.max(0, column.getChildCount() - 1), params);
        return button;
    }

    // ── Cloud saves ───────────────────────────────────────────────────────

    private void addCloudSavesButton() {
        cloudButton = addSetupButton(view -> cloudSaves.showSettings());
        refreshCloudButton();
    }

    private void refreshCloudButton() {
        if (cloudButton != null) cloudButton.setText(cloudSaves.buttonLabel());
    }

    private void refreshRetroAchievementsButton() {
        if (raButton == null) return;
        String user = RaBridge.savedUser(getFilesDir());
        raButton.setText(user != null
            ? "RetroAchievements: " + user
            : "RetroAchievements: log in");
    }

    private void showRetroAchievementsDialog() {
        String user = RaBridge.savedUser(getFilesDir());
        if (user != null) {
            new AlertDialog.Builder(this)
                .setTitle("RetroAchievements")
                .setMessage("Logged in as " + user + ".\n\n"
                    + "Achievements unlock in softcore mode: RetroAchievements "
                    + "accepts hardcore unlocks only from approved emulators.")
                .setPositiveButton("OK", null)
                .setNegativeButton("Log out", (dialog, which) -> {
                    RaBridge.logout(getFilesDir());
                    refreshRetroAchievementsButton();
                })
                .show();
            return;
        }

        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, pad / 2, pad, 0);
        EditText userField = new EditText(this);
        userField.setHint("Username");
        userField.setSingleLine(true);
        userField.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText passwordField = new EditText(this);
        passwordField.setHint("Password");
        passwordField.setSingleLine(true);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView status = new TextView(this);
        status.setText("Softcore mode. The password is used once to obtain a "
            + "token and is not stored.");
        form.addView(userField);
        form.addView(passwordField);
        form.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("RetroAchievements")
            .setView(form)
            .setPositiveButton("Log in", null)
            .setNegativeButton("Cancel", null)
            .create();
        dialog.setOnShowListener(shown -> {
            Button login = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            login.setOnClickListener(view -> {
                String name = userField.getText().toString().trim();
                String password = passwordField.getText().toString();
                if (name.isEmpty() || password.isEmpty()) {
                    status.setText("Enter username and password.");
                    return;
                }
                login.setEnabled(false);
                status.setText("Logging in…");
                new Thread(() -> {
                    String error = null;
                    String loggedIn = null;
                    try {
                        String[] credentials = RaBridge.login(name, password);
                        RaBridge.saveCredentials(getFilesDir(), credentials[0], credentials[1]);
                        loggedIn = credentials[0];
                    } catch (IOException | RuntimeException failure) {
                        error = CloudSaves.describe(failure);
                    }
                    final String message = error;
                    final String account = loggedIn;
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        if (message == null) {
                            dialog.dismiss();
                            refreshRetroAchievementsButton();
                            Toast.makeText(this, "RetroAchievements: logged in as " + account,
                                Toast.LENGTH_SHORT).show();
                        } else {
                            status.setText(message);
                            login.setEnabled(true);
                        }
                    });
                }, "ra-login").start();
            });
        });
        dialog.show();
    }

    @Override
    protected void onResume() {
        // Keep automatic relaunch off even if PLAY's shared launcher checkbox
        // wrote it immediately before the native Activity started.
        getSharedPreferences("setup", MODE_PRIVATE)
            .edit()
            .putBoolean("skip_launcher_on_boot", false)
            .apply();

        super.onResume();
        showNativeLogIfChanged();
        // Only after a game started from PLAY (remembered across recreation).
        cloudSaves.syncAfterPlay();
    }

    private void showNativeLogIfChanged() {
        // A log still being written belongs to a game that is running.
        if (org.libsdl.app.SdlSession.nativeRunning()) return;
        File log = new File(getFilesDir(), "android-runtime.log");
        if (!log.isFile() || log.length() == 0) return;

        String text;
        try {
            text = readTail(log, 12000);
        } catch (IOException error) {
            return;
        }
        if (text.trim().isEmpty()) return;

        String previous = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(LAST_LOG, "");
        if (text.equals(previous)) return;

        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(LAST_LOG, text)
            .apply();
        // A normal quit needs no report; a crash never writes this line.
        if (text.contains("mother3: session ended, exit code 0\n")) return;

        final String visible = lastLines(text, 45);
        new AlertDialog.Builder(this)
            .setTitle("MOTHER 3 — last launch log")
            .setMessage(visible)
            .setPositiveButton("OK", null)
            .show();
    }

    private static String readTail(File file, int maxBytes) throws IOException {
        long length = file.length();
        int count = (int)Math.min(length, maxBytes);
        byte[] data = new byte[count];
        try (FileInputStream input = new FileInputStream(file)) {
            long skip = Math.max(0L, length - count);
            while (skip > 0) {
                long n = input.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            int off = 0;
            while (off < data.length) {
                int n = input.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static String lastLines(String text, int maxLines) {
        String[] lines = text.split("\\r?\\n");
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; ++i) {
            if (lines[i].isEmpty()) continue;
            out.append(lines[i]).append('\n');
        }
        return out.toString();
    }
}
