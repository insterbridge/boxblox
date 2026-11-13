package app.boxblox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;

/** Private build artifacts: complete archive sets and exact asset-tree copies. */
final class BuildFiles {
    private BuildFiles() {
    }

    static List<File> archives(File directory) {
        File[] files = directory.listFiles((dir, name) -> name.matches("[0-9]{4}\\.apk"));
        if (files == null) return List.of();
        Arrays.sort(files, Comparator.comparing(File::getName));
        return List.of(files);
    }

    static List<File> snapshot(List<File> sources, File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create " + directory);
        }
        if (sources.size() > 9999) throw new IOException("Too many APKs");
        List<File> copies = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            File copy = new File(directory, String.format(java.util.Locale.ROOT, "%04d.apk", i));
            Files.copy(sources.get(i).toPath(), copy.toPath());
            if (!copy.setWritable(false, false)) {
                throw new IOException("Could not make archive read-only: " + copy);
            }
            copies.add(copy);
        }
        return copies;
    }

    /** Mirror pristine assets, removing old-build and overlay-only files first. */
    static void mirror(File source, File target) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) {
            throw new IOException("Asset symlink: " + source);
        }
        if (source.isDirectory()) {
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                    && (!target.isDirectory() || Files.isSymbolicLink(target.toPath()))) {
                delete(target);
            }
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("Could not create " + target);
            }
            File[] children = source.listFiles();
            File[] existing = target.listFiles();
            if (children == null || existing == null) throw new IOException("Could not list assets");
            for (File old : existing) {
                if (!new File(source, old.getName()).exists()) delete(old);
            }
            for (File child : children) mirror(child, new File(target, child.getName()));
        } else {
            if (target.isDirectory() || Files.isSymbolicLink(target.toPath())) delete(target);
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void delete(File file) throws IOException {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Could not list " + file);
            for (File child : children) delete(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
