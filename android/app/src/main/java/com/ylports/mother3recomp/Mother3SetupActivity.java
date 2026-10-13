package com.ylports.mother3recomp;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
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
 * Thin MOTHER 3 launcher diagnostic wrapper.
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
    /** Set when PLAY started the game; the next resume uploads its progress. */
    private boolean gameLaunched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Avoid a crash/relaunch loop while the Android port is in bring-up.
        getSharedPreferences("setup", MODE_PRIVATE)
            .edit()
            .putBoolean("skip_launcher_on_boot", false)
            .apply();
        GbaGameConfig config = GbaGameConfig.load(this);
        cloudSaves = new CloudSaves(this, config.romFile,
            config.romSha1.length > 0 ? config.romSha1[0] : "",
            this::refreshCloudButton);
        super.onCreate(savedInstanceState);
        addRetroAchievementsButton();
        addCloudSavesButton();
    }

    /** PLAY (and auto-start) go through here: sync the save first. */
    @Override
    public void startActivity(Intent intent) {
        // An APK with an embedded ROM skips this screen (and finishes at once),
        // so it has no cloud saves.
        if (cloudSaves == null || hasBundledRom() || intent.getComponent() == null
                || !GbaGameActivity.class.getName().equals(intent.getComponent().getClassName())) {
            super.startActivity(intent);
            return;
        }
        cloudSaves.syncBeforePlay(() -> {
            gameLaunched = true;
            Mother3SetupActivity.super.startActivity(intent);
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // The shared setup screen rebuilds its content on rotation/resize.
        super.onConfigurationChanged(newConfig);
        addRetroAchievementsButton();
        addCloudSavesButton();
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

    private boolean hasBundledRom() {
        try (java.io.InputStream rom = getAssets().open(
                "payload/roms/" + GbaGameConfig.load(this).romFile)) {
            return rom.read() >= 0;
        } catch (IOException missing) {
            return false;
        }
    }

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
                    } catch (IOException failure) {
                        error = failure.getMessage();
                    }
                    final String message = error;
                    final String account = loggedIn;
                    runOnUiThread(() -> {
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
        if (gameLaunched) {
            gameLaunched = false;
            cloudSaves.syncAfterPlay();
        }
    }

    private void showNativeLogIfChanged() {
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
