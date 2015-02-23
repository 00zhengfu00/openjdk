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

package java.lang.reflect;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jdk.jigsaw.module.Configuration;
import jdk.jigsaw.module.Layer;
import jdk.jigsaw.module.Layer.ClassLoaderFinder;
import jdk.jigsaw.module.ModuleArtifact;
import jdk.jigsaw.module.ModuleDescriptor;
import jdk.jigsaw.module.ModuleExport;
import jdk.jigsaw.module.Version;
import sun.misc.JavaLangReflectAccess;
import sun.misc.ServicesCatalog;
import sun.misc.SharedSecrets;

/**
 * Represents a runtime module.
 *
 * <p> {@code Module} does not define a public constructor. Instead {@code
 * Module} objects are constructed by the Java Virtual Machine when a
 * {@link jdk.jigsaw.module.Configuration Configuration} is reified by means
 * of creating a {@link jdk.jigsaw.module.Layer Layer}. </p>
 *
 * @since 1.9
 * @see java.lang.Class#getModule
 */
public final class Module {

    // no <clinit> as this class is initialized very early in the startup

    // module name and loader
    private final String name;
    private final ClassLoader loader;

    // module descriptor (need to make this final)
    private volatile ModuleDescriptor descriptor;

    // all packages in the module
    private volatile Set<String> packages;

    // the modules that this module permanently reads
    // (need to make this final)
    private volatile Set<Module> reads;

    // created lazily, additional modules that this module temporarily reads
    private volatile WeakSet<Module> transientReads;

    // module exports. The key is the package name; the value is an empty map
    // for unqualified exports; the value is a WeakHashMap when there are
    // qualified exports
    private volatile Map<String, Map<Module, Boolean>> exports;

    // indicates whether the Module is fully defined - will be used later
    // by snapshot APIs (JVM TI for example)
    private volatile boolean defined;

    /**
     * Invoked by the VM when creating a Module.
     */
    private Module(ClassLoader loader, String name) {
        this.loader = loader;
        this.name = name;
    }

    /**
     * Returns the module name.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the {@code ClassLoader} for this module.
     */
    public ClassLoader getClassLoader() {
        return loader;
    }

    /**
     * Returns the module descriptor for this module.
     */
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }

    /**
     * Returns an array of the package names of the packages in this module.
     *
     * <p> The package names are the fully-qualified names of the packages as
     * defined in section 6.5.3 of <cite>The Java&trade; Language Specification
     * </cite>, for example, {@code "java.lang"}. </p>
     *
     * <p> The returned array contains an element for each package in the
     * module when it was initially created. It may contain elements
     * corresponding to packages added to the module after it was created
     * (packages added to support dynamic proxy classes for example). A package
     * name appears at most once in the returned array. </p>
     *
     * @apiNote This method returns an array rather than a {@code Set} for
     * consistency with other {@code java.lang.reflect} types.
     *
     * @return an array of the package names of the packages in this module
     */
    public String[] getPackages() {
        return packages.toArray(new String[0]);
    }

    /**
     * Makes the given {@code Module} readable to this module. This method
     * is no-op if {@code target} is {@code null} or {@code this} (all modules
     * can read the unnamed module or themselves).
     *
     * <p> If there is a security manager then its {@code checkPermission}
     * method if first called with a {@code ReflectPermission("addReadsModule")}
     * permission to check that the caller is allowed to change the
     * readability graph. </p>
     *
     * @throws SecurityException if denied by the security manager
     *
     * @see #canRead
     */
    public void addReads(Module target) {
        if (target != null && target != this) {
            SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                ReflectPermission perm = new ReflectPermission("addReadsModule");
                sm.checkPermission(perm);
            }
            addReads(target, true);
        }
    }

    /**
     * Makes the given {@code Module} readable to this module without
     * notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addReadsNoSync(Module target) {
        addReads(target, false);
    }

    /**
     * Makes the given {@code Module} readable to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addReads(Module target, boolean syncVM) {
        // check if we already read this module
        Set<Module> reads = this.reads;
        if (reads.contains(target))
            return;

        // update VM first, just in case it fails
        if (syncVM)
            jvmAddReadsModule(this, target);

        // add temporary read.
        WeakSet<Module> tr = this.transientReads;
        if (tr == null) {
            synchronized (this) {
                tr = this.transientReads;
                if (tr == null) {
                    tr = new WeakSet<>();
                    this.transientReads = tr;
                }
            }
        }
        tr.add(target);
    }

    /**
     * Indicates if this {@code Module} reads the given {@code Module}.
     *
     * <p> Returns {@code true} if {@code m} is {@code null} (the unnamed
     * readable is readable to all modules), or {@code m} is this module (a
     * module can read itself). </p>
     *
     * @see #addReads
     */
    public boolean canRead(Module target) {
        if (target == null || target == this)
            return true;

        // check if module reads target
        Set<Module> reads = this.reads; // volatile read
        if (reads.contains(target))
            return true;

        // check if module reads the target temporarily
        WeakSet<Module> tr = this.transientReads; // volatile read
        if (tr != null && tr.contains(target))
            return true;

        return false;
    }

    /**
     * Returns the string representation.
     */
    @Override
    public String toString() {
        return "module " + name;
    }

    /**
     * Define a new Module to the runtime. The resulting Module will be
     * defined to the VM but will not read any other modules or have any
     * exports. It will also not be registered in the service catalog.
     */
    static Module defineModule(ClassLoader loader,
                               ModuleDescriptor descriptor,
                               Version version,
                               URI location,
                               Set<String> packages)
    {
        Module m;

        // define module to VM, except java.base as it is defined by VM
        String name = descriptor.name();
        if (loader == null && name.equals("java.base")) {
            m = Object.class.getModule();
        } else {
            int n = packages.size();
            String[] array = new String[n];
            int i = 0;
            for (String pkg: packages) {
                array[i++] = pkg.replace('.', '/');
            }

            String vs = (version != null) ? version.toString() : null;
            String uris = (location != null) ? location.toString() : null;

            // define module in the VM
            m = jvmDefineModule(name, vs, uris, loader, array);
        }

        // set fields as these are not set by the VM
        m.descriptor = descriptor;
        m.packages = packages;
        m.reads = Collections.emptySet();
        m.exports = Collections.emptyMap();

        return m;
    }

    /**
     * Defines each of the module in the given configuration to the runtime.
     *
     * @return a map of module name to runtime {@code Module}
     */
    static Map<String, Module> defineModules(Configuration cf,
                                             Layer.ClassLoaderFinder clf)
    {
        Map<String, Module> modules = new HashMap<>();
        Map<String, ClassLoader> loaders = new HashMap<>();

        // define each of the modules in the configuration to the VM
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            String name = descriptor.name();

            ModuleArtifact artifact = cf.findArtifact(name);
            ClassLoader loader = clf.loaderForModule(artifact);

            Version version = artifact.descriptor().id().version();
            Module m =  defineModule(loader,
                                     artifact.descriptor(),
                                     version,
                                     artifact.location(),
                                     artifact.packages());
            modules.put(name, m);
            loaders.put(name, loader);
        }

        // setup readability and exports
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Module m = modules.get(descriptor.name());
            assert m != null;

            // reads
            Set<Module> reads = new HashSet<>();
            for (ModuleDescriptor other: cf.readDependences(descriptor)) {
                String dn = other.name();
                Module m2 = modules.get(dn);
                Layer parent = cf.layer();
                if (m2 == null && parent != null)
                    m2 = parent.findModule(other.name());
                if (m2 == null) {
                    throw new InternalError(descriptor.name() +
                            " reads unknown module: " + other.name());
                }
                reads.add(m2);

                // update VM view
                jvmAddReadsModule(m, m2);
            }
            m.reads = reads;

            // exports
            Map<String, Map<Module, Boolean>> exports = new HashMap<>();
            for (ModuleExport export: descriptor.exports()) {
                String pkg = export.pkg();
                String permit = export.permit();
                if (permit == null) {
                    exports.computeIfAbsent(pkg, k -> Collections.emptyMap());

                    // update VM view
                    jvmAddModuleExports(m, pkg.replace('.', '/'), null);
                } else {
                    // only export to modules that are in this configuration
                    Module m2 = modules.get(permit);
                    if (m2 != null) {
                        exports.computeIfAbsent(pkg, k -> new HashMap<>())
                               .put(m2, Boolean.TRUE);

                        // update VM view
                        jvmAddModuleExports(m, pkg.replace('.', '/'), m2);
                    }
                }
            }
            m.exports = exports;
        }

        // register the modules in the service catalog if they provide services
        // and set the "defined" field to mark as fully defined.
        for (ModuleDescriptor descriptor: cf.descriptors()) {
            String name = descriptor.name();
            Module m = modules.get(name);
            ClassLoader loader = loaders.get(name);

            Map<String, Set<String>> services = descriptor.services();
            if (!services.isEmpty()) {
                ServicesCatalog catalog;
                if (loader == null) {
                    catalog = ServicesCatalog.getSystemServicesCatalog();
                } else {
                    catalog = SharedSecrets.getJavaLangAccess().getServicesCatalog(loader);
                }
                catalog.register(m);
            }

            m.defined = true;
        }

        return modules;
    }

    /**
     * Add a package to this module.
     *
     * @apiNote This is an expensive operation, not expected to be used often.
     * At this time then it does not validate that the package name is a
     * valid java identifier.
     */
    void addPackage(String pkg) {
        addPackage(pkg, true);
    }

    /**
     * Add a package to this module without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addPackageNoSync(String pkg) {
        addPackage(pkg.replace('/', '.'), false);
    }

    /**
     * Add a package to this module.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addPackage(String pkg, boolean syncVM) {
        if (pkg.length() == 0)
            throw new IllegalArgumentException("<unnamed> package not allowed");

        synchronized (this) {
            // copy set
            Set<String> pkgs = new HashSet<>(this.packages);
            if (!pkgs.add(pkg)) {
                // already has this package
                return;
            }

            // update VM first, just in case it fails
            if (syncVM)
                jvmAddModulePackage(this, pkg.replace('.', '/'));

            // replace with new set
            this.packages = pkgs; // volatile write
        }
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code who}. If {@code who} is {@code null} then the package is exported
     * to all modules that read this module.
     *
     * @throws IllegalArgumentException if {@code pkg} is not a module package
     *
     * @apiNote This is an expensive operation, not expected to be used often
     */
    void addExports(String pkg, Module who) {
        addExports(pkg, who, true);
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code who} but without notifying the VM.
     *
     * This method is for use by VM whitebox tests only.
     */
    void addExportsNoSync(String pkg, Module who) {
        addExports(pkg.replace('/', '.'), who, false);
    }

    /**
     * Updates the exports so that package {@code pkg} is exported to module
     * {@code who}.
     *
     * If {@code syncVM} is {@code true} then the VM is notified.
     */
    private void addExports(String pkg, Module who, boolean syncVM) {
        Objects.nonNull(pkg);

        if (!packages.contains(pkg)) {
            throw new IllegalArgumentException("exported package " + pkg +
                " not in contents");
        }

        synchronized (this) {

            // update VM first, just in case it fails
            if (syncVM)
                jvmAddModuleExports(this, pkg.replace('.', '/'), who);

            // copy existing map
            Map<String, Map<Module, Boolean>> exports = new HashMap<>(this.exports);

            // the package may be exported already, need to handle all cases
            Map<Module, Boolean> permits = exports.get(pkg);
            if (permits == null) {
                // pkg not already exported
                if (who == null) {
                    // unqualified export
                    exports.put(pkg, Collections.emptyMap());
                } else {
                    // qualified export
                    permits = new WeakHashMap<>();
                    permits.put(who, Boolean.TRUE);
                    exports.put(pkg, permits);
                }
            } else {
                // pkg already exported
                if (!permits.isEmpty()) {
                    if (who == null) {
                        // change from qualified to unqualified
                        exports.put(pkg, Collections.emptyMap());
                    } else {
                        // copy the existing qualified exports
                        permits = new WeakHashMap<>(permits);
                        permits.put(who, Boolean.TRUE);
                        exports.put(pkg, permits);
                    }
                }
            }

            // volatile write
            this.exports = exports;

        }
    }

    /**
     * Returns {@code true} if the given package name is exported to the
     * given module.
     */
    boolean isExported(String pkg, Module who) {
        Objects.nonNull(pkg);

        Map<String, Map<Module, Boolean>>  exports = this.exports; // volatile read
        Map<Module, Boolean> permits = exports.get(pkg);
        if (permits != null) {
            // unqualified export or exported to 'who'
            if (permits.isEmpty() || permits.containsKey(who)) return true;
        }

        // not exported
        return false;
    }

    /**
     * A "not-a-Set" set of weakly referenced objects that supports concurrent
     * access.
     */
    private static class WeakSet<E> {
        private final ReadWriteLock lock = new ReentrantReadWriteLock();
        private final Lock readLock = lock.readLock();
        private final Lock writeLock = lock.writeLock();

        private final WeakHashMap<E, Boolean> map = new WeakHashMap<>();

        /**
         * Adds the specified element to the set.
         */
        void add(E e) {
            writeLock.lock();
            try {
                map.put(e, Boolean.TRUE);
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * Returns {@code true} if this set contains the specified element.
         */
        boolean contains(E e) {
            readLock.lock();
            try {
                return map.containsKey(e);
            } finally {
                readLock.unlock();
            }
        }
    }


    // -- native methods --

    // JVM_DefineModule
    private static native Module jvmDefineModule(String name,
                                                 String version,
                                                 String location,
                                                 ClassLoader loader,
                                                 String[] pkgs);

    // JVM_AddReadsModule
    private static native void jvmAddReadsModule(Module from, Module to);

    // JVM_AddModuleExports
    private static native void jvmAddModuleExports(Module from, String pkg, Module to);

    // JVM_AddModulePackage
    private static native void jvmAddModulePackage(Module m, String pkg);

    /**
     * Register shared secret to provide access to package-private methods
     */
    static {
        SharedSecrets.setJavaLangReflectAccess(
            new JavaLangReflectAccess() {
                @Override
                public Map<String, Module> defineModules(Configuration cf,
                                                         ClassLoaderFinder clf) {
                    return Module.defineModules(cf, clf);
                }
                @Override
                public boolean isExported(Module m, String pkg, Module who) {
                    return m.isExported(pkg, who);
                }
                @Override
                public Module defineModule(ClassLoader loader,
                                           ModuleDescriptor descriptor,
                                           Set<String> packages) {
                    return Module.defineModule(loader, descriptor, null, null, packages);
                }
                @Override
                public void addPackage(Module m, String pkg) {
                    m.addPackage(pkg);
                }
                @Override
                public void addReadsModule(Module m1, Module m2) {
                    m1.addReads(m2, true);
                }
                @Override
                public void addExports(Module m, String pkg, Module who) {
                    m.addExports(pkg, who);
                }
            });
    }
}
