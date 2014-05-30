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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import sun.misc.JModCache;

/**
 * A module path implementation of {@code ModuleLibrary}. A module path
 * is essentially a PATH of directories containing exploded modules or jmod
 * files. The directories on the PATH are scanned lazily as modules are
 * located via {@code findLocalModule}.
 *
 * @apiNote This class is currently not safe for use by multiple threads.
 */

public class ModulePath extends ModuleLibrary {
    private static final String MODULE_INFO = "module-info.class";

    // extended module descriptor
    private static final String EXT_MODULE_DESCRIPTOR = "module/name";

    // the directories on this module path
    private final String[] dirs;
    private int next;

    // the module name to Module map of modules already located
    private final Map<String, Module> cachedModules = new HashMap<>();

    // the module to URL map of modules already located
    private final Map<Module, URL> urls = new HashMap<>();


    public ModulePath(String path, ModuleLibrary parent) {
        super(parent);
        this.dirs = path.split(File.pathSeparator);
    }

    @Override
    public Module findLocalModule(String name) {
        // try cached modules
        Module m = cachedModules.get(name);
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

    /**
     * Returns {@code true} if the module of the given name is already known
     * to the module library.
     */
    private boolean isKnownModule(String name) {
        ModuleLibrary parent = parent();
        if (parent != null && parent.findModule(name) != null)
            return true;
        return cachedModules.containsKey(name);
    }

    /**
     * Returns the URL for the purposes of class and resource loading.
     */
    public URL toURL(Module m) {
        return urls.get(m);
    }

    @Override
    public Set<Module> localModules() {
        // need to ensure that all directories have been scanned
        while (hasNextDirectory()) {
            scanNextDirectory();
        }
        return Collections.unmodifiableSet(urls.keySet());
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
            String dir = dirs[next++];
            scan(dir);
        }
    }

    /**
     * Scans the given directory for jmod or exploded modules. For each module
     * found then it enumerates its contents and creates a {@code Module} and
     * adds it (and its URL) to the cache.
     */
    private void scan(String dir) {
        // the set of module names found in this directory
        Set<String> localModules = new HashSet<>();

        Path dirPath = Paths.get(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path entry: stream) {
                ModuleArtifact artifact = null;

                BasicFileAttributes attrs =
                    Files.readAttributes(entry, BasicFileAttributes.class);
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
                    String name = artifact.moduleName();
                    if (localModules.contains(name)) {
                        throw new RuntimeException(dir +
                            " contains more than one version of " + name);
                    }
                    localModules.add(name);

                    // module already in cache (either parent module library
                    // or a previous directory on the path).
                    if (isKnownModule(name))
                        continue;

                    // add the module to the cache
                    Module m = artifact.makeModule();
                    cachedModules.put(name, m);
                    urls.put(m, artifact.url());
                }

            }
        } catch (IOException | UncheckedIOException ioe) {
            // warn for now, needs to be re-examined
            System.err.println(ioe);
        }

    }

    /**
     * A module artifact on the file system
     */
    private static class ModuleArtifact {
        final URL url;
        final String id;
        final ModuleInfo mi;
        final Collection<String> packages;

        ModuleArtifact(URL url, String id, ModuleInfo mi, Collection<String> packages) {
            this.url = url;
            this.id = id;
            this.mi = mi;
            this.packages = packages;
        }

        ModuleArtifact(URL url, ModuleInfo mi, Collection<String> packages) {
            this(url, mi.name(), mi, packages);
        }

        URL url() { return url; }
        String moduleName() { return mi.name(); }
        Iterable<String> packages() { return packages; }
        Module makeModule() { return mi.makeModule(id, packages); }
    }

    /**
     * Returns a {@code ModuleArtifact} to represent a jmod file on the
     * file system.
     */
    private ModuleArtifact readJMod(Path file) throws IOException {
        // file -> jmod URL
        String s = file.toUri().toURL().toString();
        URL url = new URL("jmod" + s.substring(4));

        ZipFile zf = JModCache.get(url);
        ZipEntry ze = zf.getEntry("classes/" + MODULE_INFO);
        if (ze == null) {
            // jmod without classes/module-info, ignore for now
            return null;
        }

        ModuleInfo mi;
        try (InputStream in = zf.getInputStream(ze)) {
            mi = ModuleInfo.read(in);
        }

        // extended module descriptor
        String id = null;
        ze = zf.getEntry(EXT_MODULE_DESCRIPTOR);
        if (ze != null) {
            try (InputStream in = zf.getInputStream(ze)) {
                id = new BufferedReader(
                    new InputStreamReader(in, "UTF-8")).readLine();
            }
        }

        List<String> packages =
            zf.stream()
              .filter(e -> e.getName().startsWith("classes/") &&
                      e.getName().endsWith(".class"))
              .map(e -> toPackageName(e))
              .filter(pkg -> pkg.length() > 0)   // module-info
              .distinct()
              .collect(Collectors.toList());

        return new ModuleArtifact(url, id, mi, packages);
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

            URL url = file.toUri().toURL();

            ModuleInfo mi = ModuleInfo.read(jf.getInputStream(entry));

            List<String> packages =
                jf.stream()
                  .filter(e -> e.getName().endsWith(".class"))
                  .map(e -> toPackageName(e))
                  .filter(pkg -> pkg.length() > 0)   // module-info
                  .distinct()
                  .collect(Collectors.toList());

            return new ModuleArtifact(url, mi, packages);
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

        URL url = dir.toUri().toURL();

        ModuleInfo mi;
        try (InputStream in = Files.newInputStream(file)) {
             mi = ModuleInfo.read(in);
        }

        List<String> packages =
            Files.find(dir, Integer.MAX_VALUE,
                        ((path, attrs) -> attrs.isRegularFile() &&
                            path.toString().endsWith(".class")))
                  .map(path -> toPackageName(dir.relativize(path)))
                  .filter(pkg -> pkg.length() > 0)   // module-info
                  .distinct()
                  .collect(Collectors.toList());

        return new ModuleArtifact(url, mi, packages);
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
