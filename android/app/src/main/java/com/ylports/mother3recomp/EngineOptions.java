package com.ylports.mother3recomp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The engine settings that the runtime menu keeps in files/config.ini
 * (integer scaling in [Display], enhanced audio in [Audio]), so the launcher
 * can change them before PLAY as well. Same "[Section]" / "key = value"
 * format as gbarecomp's HostWindow, which reads the file at launch and keeps
 * those values when it rewrites it. Only edit while no game is running: a
 * running session would overwrite the file with its own values.
 */
final class EngineOptions {
    private final File file;

    EngineOptions(File filesDir) {
        file = new File(filesDir, "config.ini");
    }

    boolean get(String section, String key, boolean fallback) {
        String header = "[" + section.toLowerCase(Locale.ROOT) + "]";
        boolean inSection = false;
        for (String line : readLines()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inSection = trimmed.toLowerCase(Locale.ROOT).equals(header);
            } else if (inSection && keyOf(trimmed).equalsIgnoreCase(key)) {
                String value = trimmed.substring(trimmed.indexOf('=') + 1).trim();
                try {
                    return Integer.parseInt(value) != 0;
                } catch (NumberFormatException notANumber) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /** Sets key in section, keeping every other line; written atomically. */
    void set(String section, String key, boolean value) throws IOException {
        String header = "[" + section.toLowerCase(Locale.ROOT) + "]";
        String entry = key + " = " + (value ? 1 : 0);
        List<String> lines = readLines();
        int sectionAt = -1;
        int lastInSection = -1;
        boolean inSection = false;
        for (int i = 0; i < lines.size(); ++i) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[")) {
                inSection = trimmed.toLowerCase(Locale.ROOT).equals(header);
                if (inSection) {
                    sectionAt = i;
                    lastInSection = i;
                }
            } else if (inSection) {
                if (keyOf(trimmed).equalsIgnoreCase(key)) {
                    lines.set(i, entry);
                    write(lines);
                    return;
                }
                if (!trimmed.isEmpty()) lastInSection = i;
            }
        }
        if (sectionAt >= 0) {
            lines.add(lastInSection + 1, entry);
        } else {
            while (!lines.isEmpty() && lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            if (!lines.isEmpty()) lines.add("");
            lines.add("[" + section + "]");
            lines.add(entry);
        }
        write(lines);
    }

    private static String keyOf(String line) {
        int equals = line.indexOf('=');
        return equals < 0 ? "" : line.substring(0, equals).trim();
    }

    private List<String> readLines() {
        try {
            return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException missingOrUnreadable) {
            return new ArrayList<>();
        }
    }

    private void write(List<String> lines) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            for (String line : lines) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            throw new IOException("cannot replace " + file.getName());
        }
    }
}
