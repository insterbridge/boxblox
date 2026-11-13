package app.boxblox;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds the archive's dex to the app's own class loader.
 *
 * <p><b>Why the app's loader and not a child loader.</b> Measured: the engine's
 * {@code JNI_OnLoad} resolves {@code com.roblox.engine.jni.*} with a JNI
 * {@code FindClass}, and on this platform that goes through the app's
 * {@code PathClassLoader} — the abort names
 * {@code DexPathList[[zip file ".../app.boxblox.../base.apk"]]}, which is
 * the app's own path, not any loader the wrapper created. A child
 * {@code DexClassLoader} is invisible to it however the thread or library is
 * arranged.
 *
 * <p><b>How the element is built.</b> {@code DexFile.loadDexFile(File, File)}
 * does not exist on Android 15 (measured: {@code NoSuchMethodException}), so the
 * element is built by the platform's own factory,
 * {@code DexPathList.makeDexElements(Element[], File, File, ClassLoader)}: pass
 * it an empty element array and the archive, and it returns the elements for
 * that archive built exactly as the platform builds them.
 */
final class LoaderPath {

    private static final String TAG = "RobloxWrapper";

    private LoaderPath() {
    }

    /**
     * Prepend {@code apk}'s dex to {@code loader}'s search path.
     *
     * @return false when the loader is not a {@code BaseDexClassLoader} or the
     *         platform's factory is not reachable, so the caller can say so
     *         rather than fail later inside the engine.
     */
    static boolean append(ClassLoader loader, File apk, File nativeDir) {
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            if (!base.isInstance(loader)) {
                Diag.set("loader " + loader + " is not a BaseDexClassLoader");
                return false;
            }
            Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(loader);
            Class<?> pathListClass = pathList.getClass();

            Class<?> elementType = Class.forName("dalvik.system.DexPathList$Element");
            Object empty = java.lang.reflect.Array.newInstance(elementType, 0);
            Object elements = makeElements(pathListClass, elementType, empty, apk, loader);
            if (elements == null) {
                return false;
            }
            int added = java.lang.reflect.Array.getLength(elements);

            Field elementsField = pathListClass.getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object existing = elementsField.get(pathList);
            int length = java.lang.reflect.Array.getLength(existing);

            Object combined = java.lang.reflect.Array.newInstance(elementType, added + length);
            // The archive's elements go first, so the guest's classes win over
            // anything the wrapper shares a name with, while the platform's own
            // dex stays in the list so android.* and the wrapper still resolve.
            for (int i = 0; i < added; i++) {
                java.lang.reflect.Array.set(combined, i, java.lang.reflect.Array.get(elements, i));
            }
            for (int i = 0; i < length; i++) {
                java.lang.reflect.Array.set(combined, added + i,
                        java.lang.reflect.Array.get(existing, i));
            }
            elementsField.set(pathList, combined);
            Diag.set("app loader now has " + (added + length) + " dex elements ("
                    + apk.getName() + " added)");

            // The native library path has to grow too. Roblox's Java loads its
            // own libraries by name — System.loadLibrary("zstd-jni-1.5.7-6") and
            // friends — and those live in the archive's unpacked lib directory,
            // not in the wrapper's installed APK.
            extendNativeLibraryPath(pathList, nativeDir);
            return true;
        } catch (Throwable t) {
            Diag.set("append failed: " + t);
            return false;
        }
    }


    /**
     * Add {@code nativeDir} to a {@code DexPathList}'s library search path.
     *
     * <p>Roblox's Java resolves several libraries by bare name, so the directory
     * holding them has to be on the path the loader searches. The field is a
     * plain {@code File[]} on every release that has it, so it is extended in
     * place rather than rebuilt.
     */
    private static void extendNativeLibraryPath(Object pathList, File nativeDir) {
        if (nativeDir == null || !nativeDir.isDirectory()) {
            return;
        }
        try {
            Field field = pathList.getClass().getDeclaredField("nativeLibraryDirectories");
            field.setAccessible(true);
            Object existing = field.get(pathList);
            String wanted = nativeDir.getAbsolutePath();

            // Measured on Android 15: this field is an ArrayList, not a File[].
            // Older releases used File[], so both shapes are handled.
            if (existing instanceof List<?> list) {
                for (Object entry : list) {
                    if (entry instanceof File file && wanted.equals(file.getAbsolutePath())) {
                        return;
                    }
                }
                List<File> combined = new ArrayList<>();
                combined.add(nativeDir);
                for (Object entry : list) {
                    if (entry instanceof File file) combined.add(file);
                }
                field.set(pathList, combined);
            } else if (existing instanceof File[]) {
                File[] files = (File[]) existing;
                for (File file : files) {
                    if (file != null && wanted.equals(file.getAbsolutePath())) return;
                }
                File[] combined = new File[files.length + 1];
                combined[0] = nativeDir;
                System.arraycopy(files, 0, combined, 1, files.length);
                field.set(pathList, combined);
            } else if (existing == null) {
                List<File> combined = new ArrayList<>();
                combined.add(nativeDir);
                field.set(pathList, combined);
            } else {
                Diag.set("nativeLibraryDirectories is " + existing.getClass().getName());
                return;
            }
            Object check = field.get(pathList);
            Diag.set("native path now: " + check);

            // nativeLibraryDirectories is only the legacy view. The loader
            // resolves a bare library name through nativeLibraryPathElements,
            // which is built once when the DexPathList is constructed, so
            // editing the File list alone still fails to find the library
            // (measured: IllegalArgumentException "unable to find native
            // library libroblox.so using classloader: ..." while the same
            // directory was listed in nativeLibraryDirectories).
            extendNativePathElements(pathList, nativeDir);
        } catch (Throwable t) {
            Diag.set("could not extend the native library path: " + t);
        }
    }

    /**
     * Add {@code nativeDir} to {@code DexPathList.nativeLibraryPathElements}.
     *
     * <p>Each entry is a {@code NativeLibraryElement}, whose class is not part of
     * the public API, so the elements are built reflectively through whichever
     * constructor this release offers — {@code (File)} on current releases,
     * {@code (File, String)} on some older ones — and prepended so the archive's
     * directory is searched before the app's own.
     */
    private static void extendNativePathElements(Object pathList, File nativeDir) {
        try {
            Field field = pathList.getClass().getDeclaredField("nativeLibraryPathElements");
            field.setAccessible(true);
            Object existing = field.get(pathList);
            if (!(existing instanceof Object[] current)) {
                Diag.set("nativeLibraryPathElements is "
                        + (existing == null ? "null" : existing.getClass().getName()));
                return;
            }
            String wanted = nativeDir.getAbsolutePath();
            for (Object element : current) {
                if (element != null && wanted.equals(String.valueOf(element))) {
                    return;
                }
            }
            Class<?> elementType = current.getClass().getComponentType();
            Object built = null;
            for (Class<?>[] shape : new Class<?>[][]{{File.class}, {File.class, String.class}}) {
                try {
                    java.lang.reflect.Constructor<?> ctor = elementType.getConstructor(shape);
                    // The element class is package-private, so the constructor is
                    // reachable but not invokable until it is opened; without this
                    // the call fails with "Class<...NativeLibraryElement> is not
                    // accessible from Class<...LoaderPath>" (measured).
                    ctor.setAccessible(true);
                    built = shape.length == 1
                            ? ctor.newInstance(nativeDir)
                            : ctor.newInstance(nativeDir, null);
                    break;
                } catch (NoSuchMethodException ignored) {
                    // try the next shape
                }
            }
            if (built == null) {
                Diag.set("no usable NativeLibraryElement constructor");
                return;
            }
            Object[] combined = java.util.Arrays.copyOf(current, current.length + 1);
            System.arraycopy(combined, 0, combined, 1, current.length);
            combined[0] = built;
            field.set(pathList, combined);
            Diag.set("native elements now: " + combined.length);
        } catch (Throwable t) {
            Diag.set("could not extend nativeLibraryPathElements: " + t);
        }
    }

    /**
     * Ask the platform to build elements for {@code apk}.
     *
     * {@code makeDexElements(Element[] existing, File dir, File[] suppressed,
     * ClassLoader loader)} on recent releases, and the same without the
     * suppressed array on older ones.
     */
    private static Object makeElements(Class<?> pathListClass, Class<?> elementType,
                                       Object empty, File apk, ClassLoader loader) {
        // Measured on Android 15: the only shape is
        //   makeDexElements(List<File> files, File dir, List<IOException> suppressed,
        //                   ClassLoader loader) -> Element[]
        // Older releases used Element[]/File[]; both are tried so the wrapper is
        // not tied to one API level.
        List<Method> candidates = new ArrayList<>();
        for (Method method : pathListClass.getDeclaredMethods()) {
            if ("makeDexElements".equals(method.getName())) candidates.add(method);
        }
        for (Method method : candidates) {
            Class<?>[] parameters = method.getParameterTypes();
            Object[] args = null;
            if (parameters.length == 4
                    && List.class.isAssignableFrom(parameters[0])
                    && parameters[1] == File.class
                    && List.class.isAssignableFrom(parameters[2])) {
                List<File> files = new ArrayList<>();
                files.add(apk);
                args = new Object[]{files, apk.getParentFile(), new ArrayList<Object>(), loader};
            } else if (parameters.length == 4 && parameters[0].isArray()) {
                args = new Object[]{empty, apk, null, loader};
            }
            if (args == null) continue;
            try {
                method.setAccessible(true);
                Object result = method.invoke(null, args);
                if (result != null) {
                    return result;
                }
            } catch (Throwable t) {
                Diag.set("makeDexElements threw: " + (t.getCause() == null ? t : t.getCause()));
            }
        }
        StringBuilder sigs = new StringBuilder();
        for (Method method : candidates) {
            sigs.append(method.getName()).append('(');
            for (Class<?> c : method.getParameterTypes()) sigs.append(c.getSimpleName()).append(',');
            sigs.append("); ");
        }
        Diag.set("makeDexElements rejected; available: " + sigs);
        return null;
    }
}
