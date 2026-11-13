package app.boxblox;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Publishes {@link OverlayDir} to the system files app, so the overlay can be
 * edited without USB or root.
 *
 * <p>A {@code DocumentsProvider} rather than scoped storage or a public
 * directory: the overlay sits in this app's private files, and this is the
 * platform's own mechanism for exposing exactly one tree of them. The
 * documents are the real files — no copying either way — so edits land the
 * moment they are saved.
 *
 * <p><b>Cursors are built per requested column.</b> The documents UI asks for
 * its own projection, which is not this class's list. Rows filled positionally
 * threw on the extra columns, and a provider that throws from
 * {@code queryRoots} is dropped from the picker silently — measured: the
 * drawer listed Termux and Zilith Launcher but not this root.
 */
public final class OverlayDocumentsProvider extends DocumentsProvider {

    private static final String ROOT_ID = "overlay";
    private static final String ROOT_DOC_ID = ROOT_ID + ":";

    private static final String[] ROOT_PROJECTION = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DOCUMENT_PROJECTION = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
    };

    private File root;

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        OverlayDir.ensure(context);
        root = OverlayDir.dir(context);
        return root.isDirectory();
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : ROOT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            row.add(rootValue(column));
        }
        return cursor;
    }

    private Object rootValue(String column) {
        switch (column) {
            case DocumentsContract.Root.COLUMN_ROOT_ID:
                return ROOT_ID;
            case DocumentsContract.Root.COLUMN_MIME_TYPES:
                return "*/*";
            case DocumentsContract.Root.COLUMN_FLAGS:
                return DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_LOCAL_ONLY;
            case DocumentsContract.Root.COLUMN_TITLE:
                return "Boxblox overlay";
            case DocumentsContract.Root.COLUMN_SUMMARY:
                return "Files here replace the game's assets at launch";
            case DocumentsContract.Root.COLUMN_DOCUMENT_ID:
                return ROOT_DOC_ID;
            case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES:
                return root.getFreeSpace();
            default:
                return null;
        }
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        return documentCursor(fileFor(documentId), documentId, projection);
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection,
            String sortOrder) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        // The README regenerates on every listing: a deleted README must not
        // survive until the next app restart to come back.
        OverlayDir.ensure(getContext());
        String[] columns = projection != null ? projection : DOCUMENT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] children = parent.listFiles();
        if (children != null) {
            java.util.Arrays.sort(children, java.util.Comparator.comparing(File::getName));
            for (File child : children) {
                addDocumentRow(cursor, child, docIdFor(child), columns);
            }
        }
        return cursor;
    }

    private Cursor documentCursor(File file, String documentId, String[] projection) {
        String[] columns = projection != null ? projection : DOCUMENT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(columns);
        addDocumentRow(cursor, file, documentId, columns);
        return cursor;
    }

    private void addDocumentRow(MatrixCursor cursor, File file, String docId, String[] columns) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            row.add(documentValue(column, file, docId));
        }
    }

    private Object documentValue(String column, File file, String docId) {
        switch (column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                return docId;
            case DocumentsContract.Document.COLUMN_MIME_TYPE:
                if (file.isDirectory()) {
                    return DocumentsContract.Document.MIME_TYPE_DIR;
                }
                // Extension-driven; README.txt is the only file shipped.
                return file.getName().endsWith(".txt")
                        ? "text/plain" : "application/octet-stream";
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                return ROOT_DOC_ID.equals(docId) ? "overlay" : file.getName();
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                return file.lastModified();
            case DocumentsContract.Document.COLUMN_FLAGS:
                if (ROOT_DOC_ID.equals(docId)) return DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
                return file.isDirectory()
                        // FLAG_DIR_SUPPORTS_CREATE is what lets the files app walk
                        // into a folder and add to it, so the documented nested
                        // layout (content/textures/ui/...) can be built by hand.
                        ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                        : DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                                | DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
            case DocumentsContract.Document.COLUMN_SIZE:
                return file.isDirectory() ? null : file.length();
            default:
                return null;
        }
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        requireSimpleName(displayName);
        File parent = fileFor(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");
        File created = new File(parent, displayName);
        if (created.exists()) throw new FileNotFoundException("Document already exists: " + displayName);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
            if (!created.mkdir()) throw new FileNotFoundException("Could not create " + displayName);
        } else {
            try {
                if (!created.createNewFile()) throw new FileNotFoundException("Document already exists");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Could not create " + displayName, e);
            }
        }
        return docIdFor(created);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = mutableFile(documentId);
        try {
            BuildFiles.delete(file);
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("Could not delete document: " + e.getMessage());
        }
        if (getContext() != null) OverlayDir.ensure(getContext());
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        requireSimpleName(displayName);
        File file = mutableFile(documentId);
        File renamed = new File(file.getParentFile(), displayName);
        if (renamed.exists()) throw new FileNotFoundException("Document already exists: " + displayName);
        if (file.renameTo(renamed)) {
            return docIdFor(renamed);
        }
        throw new IllegalStateException("Could not rename to " + displayName);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal signal) throws FileNotFoundException {
        // parseMode, not a "w" substring test: MODE_READ_WRITE neither truncates
        // nor creates, so an editor saving a shorter file would leave the old
        // tail behind. parseMode yields MODE_WRITE_ONLY/MODE_READ_WRITE with
        // TRUNCATE and CREATE honoured, which is what the caller asked for.
        return ParcelFileDescriptor.open(fileFor(documentId),
                ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            return fileFor(documentId).getPath()
                    .startsWith(fileFor(parentDocumentId).getPath() + File.separator);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- private

    /**
     * Rejects names that could address a path instead of a single entry.
     * createDocument and renameDocument take a display name straight from the
     * files app; a separator there would build a path outside the parent, so
     * only one plain component is ever accepted.
     */
    private static void requireSimpleName(String displayName) throws FileNotFoundException {
        if (displayName == null
                || displayName.isEmpty()
                || displayName.indexOf('/') >= 0
                || displayName.indexOf('\\') >= 0
                || ".".equals(displayName)
                || "..".equals(displayName)) {
            throw new FileNotFoundException("Invalid document name: " + displayName);
        }
    }

    private File mutableFile(String documentId) throws FileNotFoundException {
        File file = fileFor(documentId);
        if (file.equals(fileFor(ROOT_DOC_ID))) throw new FileNotFoundException("Cannot modify the root");
        return file;
    }

    private File fileFor(String documentId) throws FileNotFoundException {
        if (documentId == null || !documentId.startsWith(ROOT_DOC_ID)) {
            throw new FileNotFoundException("Invalid document ID");
        }
        File file = ROOT_DOC_ID.equals(documentId)
                ? root
                : new File(root, documentId.substring(ROOT_DOC_ID.length()));
        // Document ids arrive from the files app and are ours to distrust: the
        // suffix is arbitrary, so "overlay:../../databases/x.db" resolves out of
        // the tree. Only the canonical root or a canonical child may be served —
        // a plain startsWith would accept a sibling whose name merely shares the
        // root's prefix.
        String rootPath;
        String path;
        try {
            rootPath = root.getCanonicalPath();
            path = file.getCanonicalPath();
        } catch (java.io.IOException e) {
            // The id does not resolve to any path at all; refuse it the same way.
            throw new FileNotFoundException("Unresolvable document: " + documentId);
        }
        if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
            throw new FileNotFoundException("Outside the overlay: " + documentId);
        }
        if (!file.exists()) {
            throw new FileNotFoundException("No such document: " + documentId);
        }
        return new File(path);
    }

    private String docIdFor(File file) {
        try {
            String rootPath = root.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(rootPath)) return ROOT_DOC_ID;
            if (!path.startsWith(rootPath + File.separator)) {
                throw new IllegalStateException("Outside the overlay");
            }
            return ROOT_DOC_ID + path.substring(rootPath.length() + 1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not resolve document", e);
        }
    }
}
