package app.boxblox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public final class BuildFilesTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void snapshotRetainsArchiveOrderAndSurvivesSourceChanges() throws Exception {
        File base = temporary.newFile("base.apk");
        File split = temporary.newFile("split.apk");
        Files.write(base.toPath(), new byte[]{1});
        Files.write(split.toPath(), new byte[]{2});
        File dir = temporary.newFolder();
        List<File> snapshot = BuildFiles.snapshot(List.of(base, split), dir);
        Files.write(base.toPath(), new byte[]{9});
        Files.delete(split.toPath());
        assertEquals(snapshot, BuildFiles.archives(dir));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(snapshot.get(0).toPath()));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(snapshot.get(1).toPath()));
    }

    @Test public void duplicateSourceNamesDoNotCollide() throws Exception {
        File first = new File(temporary.newFolder(), "base.apk");
        File second = new File(temporary.newFolder(), "base.apk");
        Files.write(first.toPath(), new byte[]{1});
        Files.write(second.toPath(), new byte[]{2});
        List<File> copies = BuildFiles.snapshot(List.of(first, second), temporary.newFolder());
        assertEquals(2, copies.size());
        assertNotEquals(copies.get(0), copies.get(1));
    }

    @Test public void mirrorReplacesSameCountFilesAndRemovesDisabledOverlay() throws Exception {
        File source = temporary.newFolder();
        File target = temporary.newFolder();
        Files.write(new File(source, "texture").toPath(), new byte[]{1});
        Files.write(new File(target, "texture").toPath(), new byte[]{2});
        Files.write(new File(target, "overlay-only").toPath(), new byte[]{3});
        BuildFiles.mirror(source, target);
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(target, "texture").toPath()));
        assertFalse(new File(target, "overlay-only").exists());
    }

    @Test public void mirrorIncludesAndroidDictionaryAndNestedRepairs() throws Exception {
        File source = temporary.newFolder();
        File target = temporary.newFolder();
        File dictionary = new File(source, "android/settings.dict");
        assertTrue(dictionary.getParentFile().mkdir());
        Files.write(dictionary.toPath(), new byte[]{4});
        assertTrue(new File(target, "android").mkdir());
        BuildFiles.mirror(source, target);
        assertArrayEquals(new byte[]{4}, Files.readAllBytes(new File(target, "android/settings.dict").toPath()));
    }

    @Test public void mirrorDoesNotFollowTargetSymlinks() throws Exception {
        File source = temporary.newFolder();
        File target = temporary.newFolder();
        File outside = temporary.newFile();
        Files.write(outside.toPath(), new byte[]{8});
        Files.write(new File(source, "texture").toPath(), new byte[]{1});
        Files.createSymbolicLink(new File(target, "texture").toPath(), outside.toPath());
        BuildFiles.mirror(source, target);
        assertArrayEquals(new byte[]{8}, Files.readAllBytes(outside.toPath()));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(target, "texture").toPath()));
    }
}
