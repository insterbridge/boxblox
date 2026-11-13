package app.boxblox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class DiagTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void diagnosticsRotateInsteadOfGrowingForever() throws Exception {
        File directory = temporary.newFolder();
        Diag.install(directory);
        for (int i = 0; i < 180; i++) Diag.set("x".repeat(16 * 1024));
        assertTrue(new File(directory, "diag.txt").length() <= Diag.FILE_LIMIT);
        File previous = new File(directory, "diag.previous.txt");
        assertTrue(previous.isFile());
        assertTrue(previous.length() <= Diag.FILE_LIMIT);
    }

    @Test public void oversizedLegacyLogIsBoundedWithoutLosingNewMessages() throws Exception {
        File directory = temporary.newFolder();
        File current = new File(directory, "diag.txt");
        Files.write(current.toPath(), new byte[Diag.FILE_LIMIT * 2]);
        Diag.install(directory);
        Diag.set("new session");
        assertEquals("new session\n", new String(Files.readAllBytes(current.toPath()),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Diag.FILE_LIMIT, new File(directory, "diag.previous.txt").length());
    }
}
