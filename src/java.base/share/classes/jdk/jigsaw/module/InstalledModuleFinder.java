/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jdk.jigsaw.module.internal.ModuleInfo;
import jdk.internal.jimage.ImageLocation;
import jdk.internal.jimage.ImageModuleData;
import jdk.internal.jimage.ImageReader;
import jdk.internal.jimage.ImageReaderFactory;
import sun.misc.PerfCounter;

/**
 * A {@code ModuleArtifactFinder} that finds modules that are
 * linked into the modular image.
 */
class InstalledModuleFinder implements ModuleArtifactFinder {
    private static final String BOOT_JIMAGE = "bootmodules.jimage";
    private static final String EXT_JIMAGE = "extmodules.jimage";
    private static final String APP_JIMAGE = "appmodules.jimage";

    // the module name to artifact map of modules already located
    private final Map<String, ModuleArtifact> cachedModules = new ConcurrentHashMap<>();
    private final Map<String, Image> moduleToImage = new HashMap<>();

    InstalledModuleFinder() {
        long t0 = System.nanoTime();
        String home = System.getProperty("java.home");
        Path libModules = Paths.get(home, "lib", "modules");
        openImageIfExists(libModules.resolve(BOOT_JIMAGE));
        openImageIfExists(libModules.resolve(EXT_JIMAGE));
        openImageIfExists(libModules.resolve(APP_JIMAGE));
        initTime.addElapsedTimeFrom(t0);
    }

    private void openImageIfExists(Path path) {
        if (Files.notExists(path)) {
            return;
        }

        try {
            long t0 = System.nanoTime();
            Image image = new Image(path);
            image.modules.stream().forEach(mn -> moduleToImage.put(mn, image));
            readModuleDataTime.addElapsedTimeFrom(t0);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    private ModuleArtifact toModuleArtifact(String name) {
        long t0 = System.nanoTime();
        Image image = moduleToImage.get(name);
        try (InputStream in = new ByteArrayInputStream(image.readModuleInfo(name))) {
            ModuleInfo mi = ModuleInfo.read(in);
            URI location = URI.create("jrt:/" + name);
            ModuleArtifact artifact =
                    new ModuleArtifact(mi, image.packagesForModule(name), location);
            installedModulesCount.increment();
            installedModulesTime.addElapsedTimeFrom(t0);
            return artifact;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isModularImage() {
        String home = System.getProperty("java.home");
        Path jimage = Paths.get(home, "lib", "modules", BOOT_JIMAGE);
        return Files.isRegularFile(jimage);
    }

    @Override
    public ModuleArtifact find(String name) {
        if (!moduleToImage.containsKey(name)) {
            return null;
        }

        // try cached modules
        ModuleArtifact m = cachedModules.get(name);
        if (m != null)
            return m;

        // create ModuleArtifact from module descriptor
        m = toModuleArtifact(name);
        ModuleArtifact previous = cachedModules.putIfAbsent(name, m);
        if (previous == null) {
            return m;
        } else {
            return previous;
        }
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        // ensure ModuleArtifact for all modules are created
        return moduleToImage.keySet().stream()
                .map(this::find).collect(Collectors.toSet());
    }

    class Image {
        private static final String MODULE_INFO = "module-info.class";

        final ImageReader imageReader;
        final ImageModuleData mdata;
        final Set<String> modules;
        Image(Path image) throws IOException {
            this.imageReader = ImageReaderFactory.get(image);
            this.mdata = new ImageModuleData(imageReader);
            this.modules = mdata.allModuleNames();
        }

        /**
         * Returns the packages of the given module.
         */
        Set<String> packagesForModule(String name) {
            return mdata.moduleToPackages(name).stream()
                    .map(pn -> pn.replace('/', '.'))
                    .collect(Collectors.toSet());
        }

        /**
         * Return the bytes of the module-info.class of the given module
         */
        byte[] readModuleInfo(String name) throws IOException {
            String rn = "/" + name + "/" + MODULE_INFO;
            ImageLocation loc = imageReader.findLocation(rn);
            return imageReader.getResource(loc);
        }
    }

    private static final PerfCounter initTime =
            PerfCounter.newPerfCounter("jdk.module.installedModules.initTime");
    private static final PerfCounter installedModulesTime =
            PerfCounter.newPerfCounter("jdk.module.installedModules.initArtifactTime");
    private static final PerfCounter installedModulesCount =
            PerfCounter.newPerfCounter("jdk.module.installedModules.artifacts");
    private static final PerfCounter readModuleDataTime =
            PerfCounter.newPerfCounter("jdk.module.imageModuleData.readTime");
}
