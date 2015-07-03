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
package jdk.tools.jlink.internal;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import java.nio.file.Path;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.internal.jimage.BasicImageWriter;
import jdk.tools.jlink.plugins.ImageBuilder;
import jdk.tools.jlink.plugins.ImageFilePool;
import jdk.tools.jlink.plugins.ImageFilePool.ImageFile;
/**
 *
 * @author jdenise
 */
public class DefaultImageBuilder implements ImageBuilder {

    private static class DirectoryCopy implements FileVisitor<Path> {

        private final Path source;
        private final Path destination;

        DirectoryCopy(Path source, Path destination) {
            this.source = source;
            this.destination = destination;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            Path newdir = destination.resolve(source.relativize(dir));
            copy(dir, newdir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            copy(file, destination.resolve(source.relativize(file)));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
                throw exc;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            throw exc;
        }
    }

    static void copy(Path source, Path destination) throws IOException {
        CopyOption[] options = {COPY_ATTRIBUTES, NOFOLLOW_LINKS};
        Files.copy(source, destination, options);
    }

    private final Path root;
    private final Path mdir;
    private final Map<String, Path> mods;
    private final String jimage;
    private final String[] filesToCopy;
    private final boolean genBom;

    public DefaultImageBuilder(Properties properties, Path root,
            Map<String, Path> mods) throws IOException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(mods);
        String img = properties.getProperty(DefaultImageBuilderProvider.JIMAGE_NAME_PROPERTY);
        jimage = img == null ? BasicImageWriter.BOOT_IMAGE_NAME : img;

        String lst = properties.getProperty(DefaultImageBuilderProvider.COPY_FILES);
        String[] files = new String[0];
        if (lst != null) {
            files = lst.split(",");
            for (int i = 0; i < files.length; i++) {
                files[i] = files[i].trim();
            }
        }
        filesToCopy = files;

        genBom = properties.getProperty(DefaultImageBuilderProvider.GEN_BOM) != null;

        this.root = root;
        this.mdir = root.resolve(root.getFileSystem().getPath("lib", "modules"));
        this.mods = mods;
        Files.createDirectories(mdir);
    }

    private void storeFiles(Set<String> modules, String bom) throws IOException {
        // Retrieve release file from JDK home dir.
        String path = System.getProperty("java.home");
        File f = new File(path, "release");
        Properties release = null;
        if (!f.exists()) {
            // XXX When jlink is exposed to user.
            //System.err.println("WARNING, no release file found in " + path +
               //     ". release file not added to generated image");
        } else {
            release = new Properties();
            try(FileInputStream fi = new FileInputStream(f)) {
                release.load(fi);
            }
            addModules(release, modules);
        }

        if (release != null) {
            File r = new File(root.toFile(), "release");
            try(FileOutputStream fo = new FileOutputStream(r)) {
                release.store(fo, null);
            }
        }
        for (String file : filesToCopy) {
            File p = new File(path, file);
            File dest = new File(root.toFile(), p.getName());
            if (p.exists()) {
                if (p.isFile()) {
                    copy(p.toPath(), dest.toPath());
                } else {
                    if (p.isDirectory()) {
                        Files.walkFileTree(p.toPath(),
                                new DirectoryCopy(p.toPath(), dest.toPath()));
                    }
                }
            }
        }
        // Generate bom
        if (genBom) {
            File bomFile = new File(root.toFile(), "bom");
            createUtf8File(bomFile, bom);
        }
    }

    private void addModules(Properties release, Set<String> modules) throws IOException {
        if (release != null) {
            StringBuilder builder = new StringBuilder();
            int i = 0;
            for (String m : modules) {
                builder.append(m);
                if (i < modules.size() - 1) {
                    builder.append(",");
                }
                i++;
            }
            release.setProperty("MODULES", builder.toString());
        }
    }

    @Override
    public void storeFiles(ImageFilePool files, Set<String> modules, String bom) throws IOException {
        for (ImageFile f : files.getFiles()) {
            accept(f);
        }
        storeFiles(modules, bom);

         // launchers in the bin directory need execute permission
        Path bin = root.resolve("bin");
        if (Files.getFileStore(bin).supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.list(bin)
                 .filter(f -> !f.toString().endsWith(".diz"))
                 .filter(f -> Files.isRegularFile(f))
                 .forEach(this::setExecutable);

            // jspawnhelper is in lib or lib/<arch>
            Path lib = root.resolve("lib");
            Files.find(lib, 2, (path, attrs) -> {
                return path.getFileName().toString().equals("jspawnhelper");
            }).forEach(this::setExecutable);
        }

        // generate launch scripts for the modules with a main class
        for (Map.Entry<String, Path> entry : mods.entrySet()) {
            String module = entry.getKey();
            if (modules.contains(module)) {
                Path jmodpath = entry.getValue();

                Optional<String> mainClass = Optional.empty();

                try (ZipFile zf = new ZipFile(jmodpath.toString())) {
                    String e = getModuleInfoPath(jmodpath.toString());
                    ZipEntry ze = zf.getEntry(e);
                    if (ze != null) {
                        try (InputStream in = zf.getInputStream(ze)) {
                            mainClass = ModuleDescriptor.read(in).mainClass();
                        }
                    } else {
                        throw new IOException("module-info not found for " + module);
                    }
                }

                if (mainClass.isPresent()) {
                    Path cmd = root.resolve("bin").resolve(module);
                    if (!Files.exists(cmd)) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("#!/bin/sh")
                                .append("\n");
                        sb.append("DIR=`dirname $0`")
                                .append("\n");
                        sb.append("$DIR/java -m ")
                                .append(module).append('/')
                                .append(mainClass.get())
                                .append(" $@\n");

                        try (BufferedWriter writer = Files.newBufferedWriter(cmd,
                                StandardCharsets.ISO_8859_1,
                                StandardOpenOption.CREATE_NEW)) {
                            writer.write(sb.toString());
                        }
                        if (Files.getFileStore(bin)
                                .supportsFileAttributeView(PosixFileAttributeView.class)) {
                            setExecutable(cmd);
                        }
                    }
                }
            }
        }
    }

    private String getModuleInfoPath(String archive) throws IOException {
        String path = "module-info.class";
        if(archive.endsWith(".jar")) {
            return path;
        } else {
            if(archive.endsWith(".jmod")) {
                return "classes" +"/" + path;
            }
        }
        throw new IOException("Unsupported archive " + archive);
    }

    @Override
    public DataOutputStream getJImageOutputStream() throws IOException {
        Path jimageFile = mdir.resolve(jimage);
        OutputStream fos = Files.newOutputStream(jimageFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        return new DataOutputStream(bos);
    }

    private void accept(ImageFile file) throws IOException {
        String name = file.getPath();
        String filename = name.substring(name.indexOf('/') + 1);
        try (InputStream in = file.stream()) {
            switch (file.getType()) {
                case NATIVE_LIB:
                    writeEntry(in, destFile(nativeDir(filename), filename));
                    break;
                case NATIVE_CMD:
                    Path path = destFile("bin", filename);
                    writeEntry(in, path);
                    path.toFile().setExecutable(true);
                    break;
                case CONFIG:
                    writeEntry(in, destFile("conf", filename));
                    break;
                default:
                    //throw new InternalError("unexpected entry: " + name + " " + zipfile.toString()); //TODO
                    throw new InternalError("unexpected entry: " + name + " " + name);
            }
        }
    }

    private Path destFile(String dir, String filename) {
        return root.resolve(dir).resolve(filename);
    }

    private void writeEntry(InputStream in, Path dstFile) throws IOException {
        Files.createDirectories(dstFile.getParent());
        Files.copy(in, dstFile);
    }

    private static String nativeDir(String filename) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (filename.endsWith(".dll") || filename.endsWith(".diz")
                || filename.endsWith(".pdb") || filename.endsWith(".map")) {
                return "bin";
            } else {
                return "lib";
            }
        } else {
            return "lib";
        }
    }

    /**
     * chmod ugo+x file
     */
    private void setExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    private static void createUtf8File(File file, String content) throws IOException {
        try (OutputStream fout = new FileOutputStream(file);
                Writer output = new OutputStreamWriter(fout, "UTF-8")) {
            output.write(content);
        }
    }
}
