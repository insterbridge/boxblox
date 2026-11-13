package app.boxblox;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Stream operations available on every supported Android release. */
final class Streams {
    private Streams() {
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }
    }
}
