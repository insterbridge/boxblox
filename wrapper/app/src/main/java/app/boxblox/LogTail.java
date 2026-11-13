package app.boxblox;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Bounded incremental log reads with one complete line of overlap. */
final class LogTail {
    private static final int CHUNK = 256 * 1024;
    private String path;
    private long offset;
    private String pending = "";
    private String overlap = "";

    String read(File file) throws IOException {
        String current = file.getAbsolutePath();
        if (!current.equals(path) || file.length() < offset) {
            path = current;
            offset = 0;
            pending = "";
            overlap = "";
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            input.seek(offset);
            byte[] bytes = new byte[(int) Math.min(CHUNK, input.length() - offset)];
            int count = input.read(bytes);
            if (count <= 0) return "";
            offset += count;
            String text = pending + new String(bytes, 0, count, StandardCharsets.UTF_8);
            int end = text.lastIndexOf('\n');
            if (end < 0) {
                pending = suffix(text);
                return "";
            }
            pending = suffix(text.substring(end + 1));
            String complete = text.substring(0, end + 1);
            String result = overlap + complete;
            int previous = complete.lastIndexOf('\n', complete.length() - 2);
            overlap = suffix(complete.substring(previous + 1));
            return result;
        }
    }

    private static String suffix(String value) {
        return value.substring(Math.max(0, value.length() - 4096));
    }
}
