/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.jigsaw.module;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.internal.Hasher;
import jdk.jigsaw.module.internal.Hasher.HashSupplier;
import jdk.jigsaw.module.internal.ModuleInfo;
import sun.misc.JModCache;

/**
 * A finder of module artifacts.
 *
 * <p> An important property is that a {@code ModuleArtifactFinder} admits to
 * at most one module with a given name. A {@code ModuleArtifactFinder} that
 * finds modules in sequence of directories for example, will locate the first
 * occurrence of a module and ignores other modules of that name that appear in
 * directories later in the sequence. </p>
 *
 * <p> Example usage: </p>
 *
 * <pre>{@code
 *     Path dir1, dir2, dir3;
 *
 *     ModuleArtifactFinder finder =
 *         ModuleArtifactFinder.ofDirectories(dir1, dir2, dir3);
 *
 *     ModuleArtifact artifact = finder.find("jdk.foo");
 * }</pre>
 *
 * @apiNote The eventual API will need to define how errors are handled, say
 * for example find lazily searching the module path and finding two modules of
 * the same name in the same directory.
 *
 * @apiNote Rename to {@code ModuleDefinition} as per Mark's sketch?
 */
public interface ModuleArtifactFinder {

    /**
     * Finds a module artifact where the module has the given name.
     * Returns {@code null} if not found.
     */
    public ModuleArtifact find(String name);

    /**
     * Returns the set of all module artifacts that this finder can
     * locate.
     *
     * @apiNote This is important to have for methods such as {@link
     * Configuration#bind} that need to scan the module path to find
     * modules that provide a specific service.
     */
    public Set<ModuleArtifact> allModules();

    /**
     * Returns a module finder for modules that are linked into the
     * runtime image.
     *
     * @apiNote Do we need a permission check?
     */
    public static ModuleArtifactFinder installedModules() {
        if (InstalledModuleFinder.isModularImage()) {
            return new InstalledModuleFinder();
        } else {
            String home = System.getProperty("java.home");
            Path mlib = Paths.get(home, "modules");
            if (Files.isDirectory(mlib)) {
                return ofDirectories(mlib);
            } else {
                System.err.println("WARNING: " + mlib.toString() +
                        " not found or not a directory");
                return ofDirectories(new Path[0]);
            }
        }
    }

    /**
     * Creates a finder that locates modules on the file system by
     * searching a sequence of directories containing module artifacts
     * ({@code jmod}, modular JAR, exploded modules).
     *
     * @apiNote This method needs to define how the returned finder handles
     * I/O and other errors (a ClassFormatError when parsing a module-info.class
     * for example).
     */
    public static ModuleArtifactFinder ofDirectories(Path... dirs) {
        return new ModulePath(dirs);
    }

    /**
     * Returns a finder that is the equivalent to concatenating the given
     * finders. The resulting finder will locate modules artifacts using {@code
     * first}; if not found then it will attempt to locate module artifacts
     * using {@code second}.
     */
    public static ModuleArtifactFinder concat(ModuleArtifactFinder first,
                                              ModuleArtifactFinder second)
    {
        return new ModuleArtifactFinder() {
            Set<ModuleArtifact> allModules;

            @Override
            public ModuleArtifact find(String name) {
                ModuleArtifact m = first.find(name);
                if (m == null)
                    m = second.find(name);
                return m;
            }
            @Override
            public Set<ModuleArtifact> allModules() {
                if (allModules == null) {
                    allModules = Stream.concat(first.allModules().stream(),
                                               second.allModules().stream())
                                       .map(a -> a.descriptor().name())
                                       .distinct()
                                       .map(this::find)
                                       .collect(Collectors.toSet());
                }
                return allModules;
            }
        };
    }

    /**
     * Returns a <em>null</em> finder. The null finder does not find any
     * modules.
     *
     * @apiNote This is useful when using methods such as {@link
     * Configuration#resolve} where two finders are specified.
     */
    public static ModuleArtifactFinder nullFinder() {
        return new ModuleArtifactFinder() {
            @Override public ModuleArtifact find(String name) {
                return null;
            }
            @Override public Set<ModuleArtifact> allModules() {
                return Collections.emptySet();
            }
        };
    }
}


/**
 * Locates module artifacts on the file system by searching a sequence of
 * directories for jmod, modular JAR or exploded
 * modules.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */
class ModulePath implements ModuleArtifactFinder {
    private static final String MODULE_INFO = "module-info.class";

    // the directories on this module path
    private final Path[] dirs;
    private int next;

    // the module name to artifact map of modules already located
    private final Map<String, ModuleArtifact> cachedModules = new HashMap<>();

    public ModulePath(Path... dirs) {
        this.dirs = dirs; // no need to clone
    }

    @Override
    public ModuleArtifact find(String name) {
        // try cached modules
        ModuleArtifact m = cachedModules.get(name);
        if (m != null)
            return m;

        // the module may be in directories that we haven't scanned yet
        while (hasNextDirectory()) {
            scanNextDirectory();
            m = cachedModules.get(name);
            if (m != null)
                return m;
        }
        return null;
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return cachedModules.values().stream().collect(Collectors.toSet());
    }

    /**
     * Returns {@code true} if there are additional directories to scan
     */
    private boolean hasNextDirectory() {
        return next < dirs.length;
    }

    /**
     * Scans the next directory on the module path. A no-op if all
     * directories have already been scanned.
     */
    private void scanNextDirectory() {
        if (hasNextDirectory()) {
            Path dir = dirs[next++];
            scan(dir);
        }
    }

    /**
     * Scans the given directory for jmod or exploded modules. For each module
     * found then it enumerates its contents and creates a {@code Module} and
     * adds it (and its URL) to the cache.
     *
     * @throws UncheckedIOException if an I/O error occurs
     * @throws RuntimeException if direcory contains more than on version of
     * a module (need to decide on a better exception for this case).
     */
    private void scan(Path dir) {
        // the set of module names found in this directory
        Set<String> namesInThisDirectory = new HashSet<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry: stream) {
                ModuleArtifact artifact = null;

                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                } catch (IOException ioe) {
                    // ignore for now
                    continue;
                }
                if (attrs.isRegularFile()) {
                    if (entry.toString().endsWith(".jmod")) {
                        artifact = readJMod(entry);
                    } else if (entry.toString().endsWith(".jar")) {
                        artifact = readJar(entry);
                    }
                } else if (attrs.isDirectory()) {
                    artifact = readExploded(entry);
                }

                // module artifact found
                if (artifact != null) {
                    // check that there is only one version of the module
                    // in this directory
                    String name = artifact.descriptor().name();
                    if (namesInThisDirectory.contains(name)) {
                        throw new RuntimeException(dir +
                            " contains more than one version of " + name);
                    }
                    namesInThisDirectory.add(name);

                    // a module of this name found in a previous location
                    // on the module path so ignore it
                    if (cachedModules.containsKey(name))
                        continue;

                    // add the module to the cache
                    cachedModules.put(name, artifact);
                }
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    /**
     * Returns a {@code ModuleArtifact} to represent a jmod file on the
     * file system.
     */
    private ModuleArtifact readJMod(Path file) throws IOException {
        // file -> jmod URL for direct access
        URI location = URI.create("jmod" + file.toUri().toString().substring(4));
        ZipFile zf = JModCache.get(location.toURL());
        ZipEntry ze = zf.getEntry("classes/" + MODULE_INFO);
        if (ze == null) {
            // jmod without classes/module-info, ignore for now or should
            // we should throw an exception?
            return null;
        }

        ModuleInfo mi;
        try (InputStream in = zf.getInputStream(ze)) {
            mi = ModuleInfo.read(in);
        }

        Set<String> packages = zf.stream()
                                 .filter(e -> e.getName().startsWith("classes/") &&
                                         e.getName().endsWith(".class"))
                                 .map(e -> toPackageName(e))
                                 .filter(pkg -> pkg.length() > 0)   // module-info
                                 .distinct()
                                 .collect(Collectors.toSet());

        HashSupplier hasher = (algorithm) -> Hasher.generate(file, algorithm);
        return new ModuleArtifact(mi, packages, location, hasher);
    }

    /**
     * Returns a {@code ModuleArtifact} to represent a module jar on the
     * file system.
     */
    private ModuleArtifact readJar(Path file) throws IOException {
        try (JarFile jf = new JarFile(file.toString())) {
            JarEntry entry = jf.getJarEntry(MODULE_INFO);
            if (entry == null) {
                // not a modular jar
                return null;
            }

            // jar URI
            URI location = URI.create("jar:" + file.toUri() + "!/");

            ModuleInfo mi = ModuleInfo.read(jf.getInputStream(entry));

            Set<String> packages = jf.stream()
                                     .filter(e -> e.getName().endsWith(".class"))
                                     .map(e -> toPackageName(e))
                                     .filter(pkg -> pkg.length() > 0)   // module-info
                                     .distinct()
                                     .collect(Collectors.toSet());

            HashSupplier hasher = (algorithm) -> Hasher.generate(file, algorithm);
            return new ModuleArtifact(mi, packages, location, hasher);
        }
    }

    /**
     * Returns a {@code ModuleArtifact} to represent an exploded module
     * on the file system.
     */
    private ModuleArtifact readExploded(Path dir) throws IOException {
        Path file = dir.resolve(MODULE_INFO);
        if (Files.notExists((file))) {
            // no module-info in directory
            return null;
        }

        URI location = dir.toUri();

        ModuleInfo mi;
        try (InputStream in = Files.newInputStream(file)) {
            mi = ModuleInfo.read(new BufferedInputStream(in));
        }

        Set<String> packages =
                Files.find(dir, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                                path.toString().endsWith(".class")))
                        .map(path -> toPackageName(dir.relativize(path)))
                        .filter(pkg -> pkg.length() > 0)   // module-info
                        .distinct()
                        .collect(Collectors.toSet());

        return new ModuleArtifact(mi, packages, location);
    }

    private String toPackageName(ZipEntry entry) {
        String name = entry.getName();
        assert name.endsWith(".class");
        // jmod classes in classes/, jar in /
        int start = name.startsWith("classes/") ? 8 : 0;
        int index = name.lastIndexOf("/");
        if (index > start) {
            return name.substring(start, index).replace('/', '.');
        } else {
            return "";
        }
    }

    private String toPackageName(Path path) {
        String name = path.toString();
        assert name.endsWith(".class");
        int index = name.lastIndexOf(File.separatorChar);
        if (index != -1) {
            return name.substring(0, index).replace(File.separatorChar, '.');
        } else {
            return "";
        }
    }
}
