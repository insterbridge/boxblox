package app.boxblox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public final class LogTailTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private void append(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    @Test public void threePollsDoNotSkipAppendedBytes() throws Exception {
        File file = temporary.newFile();
        LogTail reader = new LogTail();
        append(file, "one\n");
        assertEquals("one\n", reader.read(file));
        append(file, "two\n");
        assertEquals("one\ntwo\n", reader.read(file));
        append(file, "three\n");
        assertEquals("two\nthree\n", reader.read(file));
        assertEquals("", reader.read(file));
    }

    @Test public void splitJoinAndPartialAddressAreRetained() throws Exception {
        File file = temporary.newFile();
        LogTail reader = new LogTail();
        append(file, "DatacenterId=437\n");
        reader.read(file);
        append(file, "UDMUX Address = 128.116.");
        assertEquals("", reader.read(file));
        append(file, "13.34\n");
        assertEquals("DatacenterId=437\nUDMUX Address = 128.116.13.34\n", reader.read(file));
    }

    @Test public void multibyteLinesUseByteOffsets() throws Exception {
        File file = temporary.newFile();
        LogTail reader = new LogTail();
        append(file, "héllo\n");
        assertEquals("héllo\n", reader.read(file));
        append(file, "next\n");
        assertEquals("héllo\nnext\n", reader.read(file));
    }

    @Test public void rotationAndTruncationResetState() throws Exception {
        File file = temporary.newFile();
        LogTail reader = new LogTail();
        append(file, "old log data\n");
        reader.read(file);
        Files.write(file.toPath(), "new\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("new\n", reader.read(file));
        File rotated = temporary.newFile();
        append(rotated, "rotated\n");
        assertEquals("rotated\n", reader.read(rotated));
    }
}
