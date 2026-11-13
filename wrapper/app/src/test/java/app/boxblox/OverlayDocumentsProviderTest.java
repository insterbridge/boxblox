package app.boxblox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public final class OverlayDocumentsProviderTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private OverlayDocumentsProvider provider(File root) throws Exception {
        OverlayDocumentsProvider provider = new OverlayDocumentsProvider();
        java.lang.reflect.Field field = OverlayDocumentsProvider.class.getDeclaredField("root");
        field.setAccessible(true);
        field.set(provider, root);
        return provider;
    }

    @Test public void treeGrantDoesNotIncludePrefixSiblingOrSelf() throws Exception {
        File root = temporary.newFolder();
        assertTrue(new File(root, "a").mkdir());
        assertTrue(new File(root, "ab").mkdir());
        assertTrue(new File(root, "a/child").createNewFile());
        OverlayDocumentsProvider provider = provider(root);
        assertFalse(provider.isChildDocument("overlay:a", "overlay:ab"));
        assertFalse(provider.isChildDocument("overlay:a", "overlay:a"));
        assertTrue(provider.isChildDocument("overlay:a", "overlay:a/child"));
    }

    @Test public void traversalAndInvalidIdsAreNotChildren() throws Exception {
        File root = temporary.newFolder();
        OverlayDocumentsProvider provider = provider(root);
        assertFalse(provider.isChildDocument("overlay:", "overlay:../"));
        assertFalse(provider.isChildDocument("overlay:", "x"));
        assertFalse(provider.isChildDocument("overlay:", null));
    }

    @Test public void rootCannotBeRenamedOrDeleted() throws Exception {
        OverlayDocumentsProvider provider = provider(temporary.newFolder());
        assertThrows(FileNotFoundException.class, () -> provider.deleteDocument("overlay:"));
        assertThrows(FileNotFoundException.class, () -> provider.renameDocument("overlay:.", "other"));
    }

    @Test public void renamingCannotOverwriteAnotherDocument() throws Exception {
        File root = temporary.newFolder();
        Files.write(new File(root, "a").toPath(), new byte[]{1});
        Files.write(new File(root, "b").toPath(), new byte[]{2});
        OverlayDocumentsProvider provider = provider(root);
        assertThrows(FileNotFoundException.class, () -> provider.renameDocument("overlay:a", "b"));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(new File(root, "b").toPath()));
    }

    @Test public void deletingAFolderActuallyRemovesItsContents() throws Exception {
        File root = temporary.newFolder();
        assertTrue(new File(root, "folder").mkdir());
        assertTrue(new File(root, "folder/file").createNewFile());
        provider(root).deleteDocument("overlay:folder");
        assertFalse(new File(root, "folder").exists());
    }
}
