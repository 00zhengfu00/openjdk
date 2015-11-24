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
package jdk.tools.jlink.internal.plugins.asm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.module.ModuleDescriptor.Requires.Modifier;
import java.lang.module.ModuleDescriptor.Exports;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.tools.jlink.internal.ImageFileCreator;
import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.plugins.ResourcePool;

/**
 * A pool of ClassReader and other resource files. This class allows to
 * transform and sort classes and resource files.
 * <p>
 * Classes in the class pool are named following java binary name specification.
 * For example, java.lang.Object class is named java/lang/Object
 * <p>
 * Module information has been stripped out from class and other resource files
 * (.properties, binary files, ...).</p>
 */
final class AsmPoolImpl implements AsmModulePool {

    /**
     * Contains the transformed classes. When the jimage file is generated,
     * transformed classes take precedence on unmodified ones.
     */
    public final class WritableClassPoolImpl implements WritableClassPool {

        private WritableClassPoolImpl() {
        }

        /**
         * Add a class to the pool, if a class already exists, it is replaced.
         *
         * @param writer The class writer.
         * @throws java.io.IOException
         */
        @Override
        public void addClass(ClassWriter writer) throws IOException {
            Objects.requireNonNull(writer);
            // Retrieve the className
            ClassReader reader = newClassReader(writer.toByteArray());
            String className = reader.getClassName();
            String path;
            if (className.endsWith("module-info")) {
                // remove the module name contained in the class name
                className = className.substring(className.indexOf("/") + 1);
                path = "/" + moduleName + "/" + className;
            } else {
                path = toClassNamePath(className);
            }

            byte[] content = writer.toByteArray();
            Resource res = new Resource(path, ByteBuffer.wrap(content));
            transformedClasses.put(className, res);
        }

        /**
         * The class will be not added to the jimage file.
         *
         * @param className The class name to forget.
         */
        @Override
        public void forgetClass(String className) throws IOException {
            Objects.requireNonNull(className);
            // do we have a resource?
            Resource res = transformedClasses.get(className);
            if (res == null) {
                res = inputClasses.get(className);
                if (res == null) {
                    throw new IOException("Unknown class " + className);
                }
            }
            String path = toClassNamePath(className);
            forgetResources.add(path);
            // Just in case it has been added.
            transformedClasses.remove(className);
        }

        /**
         * Get a transformed class.
         *
         * @param binaryName The java class binary name
         * @return The ClassReader or null if the class is not found.
         */
        @Override
        public ClassReader getClassReader(String binaryName) throws IOException {
            Objects.requireNonNull(binaryName);
            Resource res = transformedClasses.get(binaryName);
            ClassReader reader = null;
            if (res != null) {
                reader = getClassReader(res);
            }
            return reader;
        }

        /**
         * Returns all the classes contained in the writable pool.
         *
         * @return The array of transformed classes.
         */
        @Override
        public Collection<Resource> getClasses() {
            List<Resource> classes = new ArrayList<>();
            for (Entry<String, Resource> entry : transformedClasses.entrySet()) {
                classes.add(entry.getValue());
            }
            return classes;
        }

        @Override
        public ClassReader getClassReader(Resource res) throws IOException {
            return newClassReader(res.getByteArray());
        }
    }

    /**
     * Contains the transformed resources. When the jimage file is generated,
     * transformed resources take precedence on unmodified ones.
     */
    public final class WritableResourcePoolImpl implements WritableResourcePool {

        private WritableResourcePoolImpl() {
        }

        /**
         * Add a resource, if the resource exists, it is replaced.
         *
         * @param resFile The resource file to add.
         * @throws IOException
         */
        @Override
        public void addResourceFile(ResourceFile resFile) throws IOException {
            Objects.requireNonNull(resFile);
            String path = toResourceNamePath(resFile.getPath());
            Resource res = new Resource(path, resFile.getContent());
            transformedResources.put(resFile.getPath(), res);
        }

        /**
         * The resource will be not added to the jimage file.
         *
         * @param resourceName
         * @throws java.io.IOException
         */
        @Override
        public void forgetResourceFile(String resourceName) throws IOException {
            Objects.requireNonNull(resourceName);
            String path = toResourceNamePath(resourceName);
            // do we have a resource?
            Resource res = transformedResources.get(resourceName);
            if (res == null) {
                res = inputResources.get(resourceName);
                if (res == null) {
                    throw new IOException("Unknown resource " + resourceName);
                }
            }
            forgetResources.add(path);
            // Just in case it has been added.
            transformedResources.remove(resourceName);
        }

        /**
         * Get a transformed resource.
         *
         * @param name The java resource name
         * @return The Resource or null if the resource is not found.
         */
        @Override
        public ResourceFile getResourceFile(String name) {
            Objects.requireNonNull(name);
            Resource res = transformedResources.get(name);
            ResourceFile resFile = null;
            if (res != null) {
                resFile = getResourceFile(res);
            }
            return resFile;
        }

        /**
         * Returns all the resources contained in the writable pool.
         *
         * @return The array of transformed classes.
         */
        @Override
        public Collection<Resource> getResourceFiles() {
            List<Resource> resources = new ArrayList<>();
            for (Entry<String, Resource> entry : transformedResources.entrySet()) {
                resources.add(entry.getValue());
            }
            return resources;
        }

        @Override
        public ResourceFile getResourceFile(Resource res) {
            return new ResourceFile(toJavaBinaryResourceName(res.getPath()),
                    res.getContent());
        }
    }

    private final ResourcePool jimageResources;
    private final Map<String, Resource> inputClasses;
    private final Map<String, Resource> inputResources;
    private final Map<String, String> inputClassPackageMapping;
    private final Map<String, String> inputOtherPackageMapping;

    private final WritableClassPool transClassesPool
            = new WritableClassPoolImpl();
    private final WritableResourcePool transResourcesPool
            = new WritableResourcePoolImpl();

    private Sorter sorter;

    private final Map<String, Resource> transformedClasses
            =            new LinkedHashMap<>();
    private final Map<String, Resource> transformedResources
            =            new LinkedHashMap<>();
    private final List<String> forgetResources = new ArrayList<>();
    private final Map<String, String> newPackageMapping = new HashMap<>();

    private final String moduleName;

    private final ModuleDescriptor descriptor;
    private final AsmPools pools;

    /**
     * A new Asm pool.
     *
     * @param inputResources The raw resources to build the pool from.
     * @param moduleName The name of a module.
     * @param pools The resource pools.
     * @param descriptor The module descriptor.
     * @throws IOException
     */
    AsmPoolImpl(ResourcePool inputResources, String moduleName,
            AsmPools pools,
            ModuleDescriptor descriptor)
            throws IOException {
        Objects.requireNonNull(inputResources);
        Objects.requireNonNull(moduleName);
        Objects.requireNonNull(pools);
        Objects.requireNonNull(descriptor);
        this.jimageResources = inputResources;
        this.moduleName = moduleName;
        this.pools = pools;
        this.descriptor = descriptor;
        List<Resource> readers = new ArrayList<>();
        List<Resource> resList = new ArrayList<>();
        Map<String, Resource> classes = new LinkedHashMap<>();
        Map<String, Resource> resources = new LinkedHashMap<>();
        Map<String, String> packageClassToModule = new HashMap<>();
        Map<String, String> packageOtherToModule = new HashMap<>();
        for (Resource res : inputResources.getResources()) {
            if (res.getPath().endsWith(".class")) {
                classes.put(toJavaBinaryClassName(res.getPath()), res);
                readers.add(res);
            } else {
                resources.put(toJavaBinaryResourceName(res.getPath()), res);
                resList.add(res);
            }
            String[] split = ImageFileCreator.splitPath(res.getPath());
            if (ImageFileCreator.isClassPackage(res.getPath())) {
                packageClassToModule.put(split[1], res.getModule());
            } else {
                // Keep a map of other resources
                // Same resource names such as META-INF/* should be handled with full path name.
                if (!split[1].isEmpty()) {
                    packageOtherToModule.put(split[1], res.getModule());
                }
            }
        }
        this.inputClasses = Collections.unmodifiableMap(classes);
        this.inputResources = Collections.unmodifiableMap(resources);

        this.inputClassPackageMapping = Collections.unmodifiableMap(packageClassToModule);
        this.inputOtherPackageMapping = Collections.unmodifiableMap(packageOtherToModule);
    }

    @Override
    public String getModuleName() {
        return moduleName;
    }

    /**
     * The writable pool used to store transformed resources.
     *
     * @return The writable pool.
     */
    @Override
    public WritableClassPool getTransformedClasses() {
        return transClassesPool;
    }

    /**
     * The writable pool used to store transformed resource files.
     *
     * @return The writable pool.
     */
    @Override
    public WritableResourcePool getTransformedResourceFiles() {
        return transResourcesPool;
    }

    /**
     * Set a sorter instance to sort all files. If no sorter is set, then input
     * Resources will be added in the order they have been received followed by
     * newly added resources.
     *
     * @param sorter
     */
    @Override
    public void setSorter(Sorter sorter) {
        this.sorter = sorter;
    }

    /**
     * Returns the classes contained in the pool.
     *
     * @return The array of classes.
     */
    @Override
    public Collection<Resource> getClasses() {
        return inputClasses.values();
    }

    /**
     * Returns the resources contained in the pool. Resources are all the file
     * that are not classes (eg: properties file, binary files, ...)
     *
     * @return The array of classes.
     */
    @Override
    public Collection<Resource> getResourceFiles() {
        return inputResources.values();
    }

    /**
     * Retrieves a resource based on the binary name. This name doesn't contain
     * the module name.
     * <b>NB:</b> When dealing with resources that have the same name in various
     * modules (eg: META-INFO/*), you should use the <code>ResourcePool</code>
     * referenced from this <code>AsmClassPool</code>.
     *
     * @param binaryName Name of a Java resource or null if the resource doesn't
     * exist.
     * @return
     */
    @Override
    public ResourceFile getResourceFile(String binaryName) {
        Objects.requireNonNull(binaryName);
        Resource res = inputResources.get(binaryName);
        ResourceFile resFile = null;
        if (res != null) {
            resFile = getResourceFile(res);
        }
        return resFile;
    }

    /**
     * Retrieve a ClassReader from the pool.
     *
     * @param binaryName Class binary name
     * @return A reader or null if the class is unknown
     * @throws IOException
     */
    @Override
    public ClassReader getClassReader(String binaryName) throws IOException {
        Objects.requireNonNull(binaryName);
        Resource res = inputClasses.get(binaryName);
        ClassReader reader = null;
        if (res != null) {
            reader = getClassReader(res);
        }
        return reader;
    }

    @Override
    public ResourceFile getResourceFile(Resource res) {
        return new ResourceFile(toJavaBinaryResourceName(res.getPath()),
                res.getContent());
    }

    @Override
    public ClassReader getClassReader(Resource res) throws IOException {
        return newClassReader(res.getByteArray());
    }

    /**
     * Lookup the class in this pool and the required pools. NB: static module
     * readability can be different at execution time.
     *
     * @param binaryName The class to lookup.
     * @return The reader or null if not found
     * @throws java.io.IOException
     */
    @Override
    public ClassReader getClassReaderInDependencies(String binaryName) throws IOException {
        Objects.requireNonNull(binaryName);
        ClassReader reader = getClassReader(binaryName);
        if (reader == null) {
            for (Requires requires : descriptor.requires()) {
                AsmModulePool pool = pools.getModulePool(requires.name());
                reader = pool.getExportedClassReader(moduleName, binaryName);
                if (reader != null) {
                    break;
                }
            }
        }
        return reader;
    }

    /**
     * Lookup the class in the exported packages of this module. "public
     * requires" modules are looked up. NB: static module readability can be
     * different at execution time.
     *
     * @param callerModule Name of calling module.
     * @param binaryName The class to lookup.
     * @return The reader or null if not found
     * @throws java.io.IOException
     */
    @Override
    public ClassReader getExportedClassReader(String callerModule,
            String binaryName) throws IOException {
        Objects.requireNonNull(callerModule);
        Objects.requireNonNull(binaryName);
        boolean exported = false;
        ClassReader clazz = null;
        for (Exports exports : descriptor.exports()) {
            String pkg = exports.source();
            Optional<Set<String>> targets = exports.targets();
            System.out.println("PKG " + pkg);
            if (!targets.isPresent() || targets.get().contains(callerModule)) {
                if (binaryName.startsWith(pkg)) {
                    String className = binaryName.substring(pkg.length());
                    System.out.println("CLASS " + className);
                    exported = !className.contains(".");
                }
                if (exported) {
                    break;
                }
            }
        }
        // public requires (re-export)
        if (!exported) {
            for (Requires requires : descriptor.requires()) {
                if (requires.modifiers().contains(Modifier.PUBLIC)) {
                    AsmModulePool pool = pools.getModulePool(requires.name());
                    clazz = pool.getExportedClassReader(moduleName, binaryName);
                    if (clazz != null) {
                        break;
                    }
                }
            }
        } else {
            clazz = getClassReader(binaryName);
        }
        return clazz;

    }

    @Override
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * To visit the set of ClassReaders.
     *
     * @param visitor The visitor.
     * @throws java.io.IOException
     */
    @Override
    public void visitClassReaders(ClassReaderVisitor visitor) throws IOException {
        Objects.requireNonNull(visitor);
        for (Resource res : getClasses()) {
            ClassReader reader = newClassReader(res.getByteArray());
            ClassWriter writer = visitor.visit(reader);
            if (writer != null) {

                getTransformedClasses().addClass(writer);
            }
        }
    }

    /**
     * To visit the set of ClassReaders.
     *
     * @param visitor The visitor.
     * @throws java.io.IOException
     */
    @Override
    public void visitResourceFiles(ResourceFileVisitor visitor)
            throws IOException {
        Objects.requireNonNull(visitor);
        for (Resource resource : getResourceFiles()) {
            ResourceFile resFile
                    = new ResourceFile(toJavaBinaryResourceName(resource.getPath()),
                            resource.getContent());
            ResourceFile res = visitor.visit(resFile);
            if (res != null) {
                getTransformedResourceFiles().addResourceFile(res);
            }
        }
    }

    /**
     * Returns the pool of all the resources (transformed and unmodified). The
     * input resources are replaced by the transformed ones. If a sorter has
     * been set, it is used to sort the returned resources.
     *
     * @throws Exception
     */
    @Override
    public void fillOutputResources(ResourcePool outputResources) throws Exception {
        List<String> added = new ArrayList<>();
        // If the sorter is null, use the input order.
        // New resources are added at the end
        // First input classes that have not been removed
        ResourcePool output = new ResourcePoolImpl(outputResources.getByteOrder());
        for (Resource inResource : jimageResources.getResources()) {
            if (!forgetResources.contains(inResource.getPath())) {
                Resource resource = inResource;
                // Do we have a transformed class with the same name?
                Resource res = transformedResources.
                        get(toJavaBinaryResourceName(inResource.getPath()));
                if (res != null) {
                    resource = res;
                } else {
                    res = transformedClasses.
                            get(toJavaBinaryClassName(inResource.getPath()));
                    if (res != null) {
                        resource = res;
                    }
                }
                output.addResource(resource);
                added.add(resource.getPath());
            }
        }
        // Then new resources
        for (Map.Entry<String, Resource> entry : transformedResources.entrySet()) {
            Resource resource = entry.getValue();
            if (!forgetResources.contains(resource.getPath())) {
                if (!added.contains(resource.getPath())) {
                    output.addResource(resource);
                }
            }
        }
        // And new classes
        for (Map.Entry<String, Resource> entry : transformedClasses.entrySet()) {
            Resource resource = entry.getValue();
            if (!forgetResources.contains(resource.getPath())) {
                if (!added.contains(resource.getPath())) {
                    output.addResource(resource);
                }
            }
        }

        AsmPools.sort(outputResources, output, sorter);
    }

    /**
     * Associate a package to this module, useful when adding new classes in new
     * packages. WARNING: In order to properly handle new package and/or new
     * module, module-info class must be added and/or updated.
     *
     * @param pkg The new package, following java binary syntax (/-separated
     * path name).
     * @throws java.lang.Exception If a mapping already exist for this package.
     */
    @Override
    public void addPackage(String pkg) throws IOException {
        Objects.requireNonNull(pkg);
        Objects.requireNonNull(moduleName);
        pkg = pkg.replaceAll("/", ".");
        String mod = newPackageMapping.get(pkg);
        if (mod != null) {
            throw new IOException(mod + " module already contains package " + pkg);
        }
        newPackageMapping.put(pkg, moduleName);
    }

    @Override
    public Set<String> getAllPackages() {
        ModuleDescriptor desc = getDescriptor();
        Set<String> packages = new HashSet<>();
        for (String p : desc.conceals()) {
            packages.add(p.replaceAll("\\.", "/"));
        }
        for (String p : newPackageMapping.keySet()) {
            packages.add(p.replaceAll("\\.", "/"));
        }
        for (Exports ex : desc.exports()) {
            packages.add(ex.source().replaceAll("\\.", "/"));
        }
        return packages;
    }

    private static ClassReader newClassReader(byte[] bytes) throws IOException {
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
        ClassReader reader = new ClassReader(stream);
        return reader;
    }

    private static String toJavaBinaryClassName(String path) {
        if (path.endsWith("module-info.class")) {
            path = removeClassExtension(path);
        } else {
            path = removeModuleName(path);
            path = removeClassExtension(path);
        }
        return path;
    }

    private static String toJavaBinaryResourceName(String path) {
        if (!path.endsWith("module-info.class")) {
            path = removeModuleName(path);
        }
        return path;
    }

    private static String removeClassExtension(String path) {
        return path.substring(0, path.length() - ".class".length());
    }

    private static String removeModuleName(String path) {
        path = path.substring(1);
        return path.substring(path.indexOf("/") + 1, path.length());
    }

    private String toClassNamePath(String className) throws IOException {
        return toResourceNamePath(className) + ".class";
    }

    /**
     * Entry point to manage resource<->module association.
     */
    private String toResourceNamePath(String resourceName) throws IOException {
        if (!resourceName.startsWith("/")) {
            resourceName = "/" + resourceName;
        }
        String pkg = toPackage(resourceName);
        String module = inputClassPackageMapping.get(pkg);
        if (module == null) {
            module = newPackageMapping.get(pkg);
            if (module == null) {
                module = inputOtherPackageMapping.get(pkg);
                if (module == null) {
                    throw new IOException("No module for package" + pkg);
                }
            }
        }
        return "/" + module + resourceName;
    }

    private static String toPackage(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int i = path.lastIndexOf("/");
        if (i == -1) {
            // Default package...
            return "";
        }
        return path.substring(0, i).replaceAll("/", ".");
    }
}
