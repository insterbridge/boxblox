package app.boxblox;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Diagnostics that survive a crash.
 *
 * <p>Written because this device suppresses the wrapper's {@code Log} output
 * while the process is alive, and a native abort discards anything buffered. A
 * file in the app's own storage is readable afterwards with
 * {@code adb shell run-as app.boxblox cat files/diag.txt}, which is the
 * only reliable channel for a startup that dies before it can render.
 */
final class Diag {

    private static final int LIMIT = 200;
    static final int FILE_LIMIT = 1024 * 1024;
    private static final int LINE_LIMIT = 16 * 1024;
    private static final List<String> LINES = Collections.synchronizedList(new ArrayList<>());

    private static File file;

    private Diag() {
    }

    static synchronized void install(File dir) {
        if (dir != null) {
            file = new File(dir, "diag.txt");
        }
    }

    static synchronized void set(String message) {
        String line = String.valueOf(message);
        if (line.length() > LINE_LIMIT) line = line.substring(0, LINE_LIMIT) + "…";
        LINES.add(line);
        while (LINES.size() > LIMIT) {
            LINES.remove(0);
        }
        android.util.Log.i("RobloxWrapper", line);
        append(line);
    }

    private static synchronized void append(String line) {
        if (file == null) return;
        try {
            byte[] bytes = (line + '\n').getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (file.length() + bytes.length > FILE_LIMIT) {
                // Also bound a log left by an older append-only version.
                byte[] previous = new byte[(int) Math.min(FILE_LIMIT, file.length())];
                try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file, "r")) {
                    input.seek(input.length() - previous.length);
                    input.readFully(previous);
                }
                java.nio.file.Files.write(new File(file.getParentFile(), "diag.previous.txt").toPath(), previous);
                java.nio.file.Files.delete(file.toPath());
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, true)) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // A diagnostic that cannot be written must not become the failure.
        }
    }

    static synchronized void clear() {
        LINES.clear();
        if (file != null && file.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static String text() {
        synchronized (LINES) {
            StringBuilder out = new StringBuilder();
            for (String line : LINES) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }
}
