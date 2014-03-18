/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.sun.tools.classanalyzer;

import com.sun.tools.classanalyzer.ClassPath.Archive;
import com.sun.tools.classanalyzer.Module.Factory;
import com.sun.tools.classanalyzer.Service.ProviderConfigFile;
import static com.sun.tools.classanalyzer.Dependence.Identifier.*;

import com.sun.tools.classfile.ClassFile;
import com.sun.tools.classfile.ConstantPoolException;
import com.sun.tools.classfile.Dependencies;
import com.sun.tools.classfile.Dependency;
import com.sun.tools.classfile.Dependency.Location;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.*;
import java.util.function.Function;

/**
 * Module builder that creates modules as defined in the given
 * module configuration files.  The run() method assigns
 * all classes and resources according to the module definitions.
 * Additional dependency information can be specified e.g.
 * Class.forName, JNI_FindClass, and service providers.
 *
 * @see DependencyConfig
 */
public class ModuleBuilder {
    protected final List<ModuleConfig> mconfigs = new ArrayList<>();
    protected final String version;
    private final List<Archive> archives;
    private final Map<String, Klass> classes = new HashMap<>();
    private final Map<String, Service> services = new HashMap<>();
    private final Map<Module, Map<String,Dependence>> dependencesForModule = new HashMap<>();

    private final JigsawModules graph = new JigsawModules();
    public ModuleBuilder(List<ModuleConfig> configs,
                         List<Archive> archives,
                         String version) throws IOException {
        this.mconfigs.addAll(configs);
        this.archives = archives;
        this.version = version;
    }

    /**
     * Returns the module factory.
     */
    protected Factory factory() {
        return Module.getFactory();
    }

    public Set<Module> getModuleDependences(Module m) {
        Set<Module> deps = new HashSet<>();
        for (Dependence d : dependencesForModule.get(m).values()) {
            if (!d.requiresService()) {
                deps.add(factory().getModule(d.name()));
            }
        }
        return deps;
    }

    /**
     * This method assigns the classes and resource files
     * to modules and generates the package information and
     * the module information.
     *
     * This method can be overridden in a subclass implementation.
     */
    public void run() throws IOException {
        // assign classes and resource files to the modules and
        // group fine-grained modules per configuration files
        buildModules();

        // build jigsaw modules
        for (Module m : dependencesForModule.keySet()) {
            graph.build(m, dependencesForModule.get(m).values());
        }
        System.out.format("%d modules %d classes analyzed%n",
                          dependencesForModule.size(), classes.size());
    }

    public void store(OutputStream out) throws IOException {
        graph.store(out);
    }

    public void printModuleInfo(PrintWriter writer, Module m) throws IOException {
        graph.printModuleInfo(writer, m);
    }

    public Iterable<Module> sort() {
        TopoSorter<Module> sorter = new TopoSorter<>(dependencesForModule.keySet(),
                                                     this::getModuleDependences);
        return sorter.result();
    }

    /**
     * Builds modules from the existing list of classes and resource
     * files according to the module configuration files.
     *
     */
    protected void buildModules() throws IOException {
        // create the modules for the given configs
        factory().init(mconfigs);

        // analyze class dependencies
        findDependencies();

        // group fine-grained modules
        factory().buildModuleMembers();

        // analyze cross-module dependencies
        Set<Profile> profiles = new HashSet<>(Arrays.asList(Profile.values()));
        for (Module m : factory().getAllModules()) {
            if (m.group() == m) {
                // module with no class is not included except the base module
                // or reexporting APIs from required modules
                boolean reexports = false;
                for (Dependence d : m.config().requires().values()) {
                    reexports = reexports || d.requiresPublic();
                }

                if (isBaseModule(m) || !m.isEmpty() || m.allowsEmpty() || reexports) {
                    Map<String, Dependence> requires = buildModuleDependences(m);
                    dependencesForModule.put(m, requires);

                    String profile = m.profile() != null
                                        ? m.profile().modulename
                                        : null;
                    if (m.name().equals(profile)) {
                        // an aggregate module for profile
                        validateProfile(m, requires);
                        profiles.remove(m.profile());
                    }

                    // validate permits
                    for (String n : m.permits()) {
                        if (factory().findModule(n) == null) {
                            throw new RuntimeException("permits " + n +
                                " in config file for " + m + " not found");
                        }
                    }
                }
            }
        }

        if (!profiles.isEmpty()) {
            throw new RuntimeException("Profile module missing: " + profiles);
        }
    }

    private Module findModule(String name) {
        Module module = null;
        for (Module m : Module.getFactory().getAllModules()) {
            ModuleConfig config = m.config();
            if (config.matchesIncludes(name) && !config.isExcluded(name)) {
                return m;
            }
        }
        return null;
    }

    private void findDependencies() throws IOException {
        Dependency.Finder finder = Dependencies.getClassDependencyFinder();
        Dependency.Filter filter = new Dependency.Filter() {
            @Override
            public boolean accepts(Dependency dependency) {
                return !dependency.getOrigin().equals(dependency.getTarget());
            }
        };

        // get the immediate dependencies of the input files
        for (Archive a : archives) {
            for (ClassFile cf : a.getClassFiles()) {
                String classFileName;
                try {
                    classFileName = cf.getName();
                } catch (ConstantPoolException e) {
                    throw new Dependencies.ClassFileError(e);
                }

                Klass k = classes.get(classFileName);
                if (k == null) {
                    k = new Klass(classFileName, cf.access_flags, cf.byteLength());
                    String cn = k.getClassName();
                    Module module = getPackage(k).module;
                    module.addKlass(k);
                    classes.put(classFileName, k);
                }
                for (Dependency d : finder.findDependencies(cf)) {
                    if (filter.accepts(d)) {
                        k.reference(d.getTarget());
                    }
                }
            }
        }

        // process resources and service providers
        for (Archive a : archives) {
            for (Resource res : a.getResources()) {
                if (!res.isService()) {
                    Module module = getPackage(res).module;
                    module.addResource(res);
                } else {
                    ProviderConfigFile pcf = ProviderConfigFile.class.cast(res);
                    String name = pcf.getName().replace('.', '/');
                    Klass k = classes.get(name);
                    if (k == null) {
                        System.out.println("Warning: " + name + " not found");
                        continue;
                    }
                    Service s = services.get(k.getClassName());
                    if (s == null) {
                        services.put(k.getClassName(), s = new Service(k));
                        k.getModule().addService(s);
                    }
                    for (String p : pcf.providers) {
                        Klass provider = classes.get(p.replace('.', '/'));
                        if (provider == null) {
                            throw new RuntimeException(s + " provider not exist: " + p);
                        }
                        provider.getModule().addProvider(s, provider);
                    }
                }
            }
        }
    }

    // returns true if the given class is not in module m and
    // not in an exported package of the base module
    private boolean requiresModuleDependence(Module m, Klass k) {
        return m.group() != k.getModule().group();
    }

    interface KlassFilter {
        boolean accept(Klass k);
    }

    // returns all classes that are not in the base module
    // referenced by the given class
    public Set<Klass> getDeps(Klass from) {
        return getDeps(from, new KlassFilter() {
            public boolean accept(Klass k) {
                Module m = k.getModule().group();
                return !isBaseModule(m) || !m.getPackage(k.getPackageName()).isExported;
            }
        });
    }

    public Set<Klass> getDeps(Klass from, KlassFilter filter) {
        Set<Klass> deps = new TreeSet<>();
        for (Location target : from.getDeps()) {
            Klass to = classes.get(target.getName());
            if (to == null) {
                System.err.println(target.getName() + " not found");
            } else if (requiresModuleDependence(from.getModule(), to)) {
                if (filter == null || filter.accept(to)) {
                    deps.add(to);
                }
            }
        }
        return deps;
    }

    private Package getPackage(Klass k) {
        String pn = k.getPackageName();
        return getPackage(pn, k.getClassName());
    }

    private Package getPackage(Resource res) {
        String pn = "";
        int i = res.getName().lastIndexOf('/');
        if (i > 0) {
            pn = res.getName().substring(0, i).replace('/', '.');
        }
        return getPackage(pn, res.getName());
    }

    private Package getPackage(String pn, String name) {
        Module module = findModule(name);
        return module.getPackage(pn);
    }

    public Map<String, Set<Module>> getPackages() {
        Map<String, Set<Module>> modulesForPackage = new TreeMap<>();
        for (Module m : dependencesForModule.keySet()) {
            for (Package p : m.packages()) {
                Set<Module> ms = modulesForPackage.get(p.name());
                if (ms == null) {
                    modulesForPackage.put(p.name(), ms = new HashSet<>());
                }
                ms.add(m);
            }
        }
        return modulesForPackage;
    }

    private static List<String> baseModules = Arrays.asList("base", "jdk.base", "java.base");
    private boolean isBaseModule(Module m) {
        return baseModules.contains(m.name());
    }

    private void validateProfile(Module m, Map<String, Dependence> requires) {
       Profile profile = m.profile();
        for (Dependence d : requires.values()) {
            Profile p = factory().getModule(d.name()).profile();
            if (p != null && p != profile && !profile.requires(p)) {
                // dependence is a module in this profile
                // or requires a smaller profile
                throw new RuntimeException(m.name() + " requires "
                        + d.name() + " in profile " + p);
            }
        }
        return;
    }

    protected Map<String, Dependence> buildModuleDependences(Module m) {
        Map<String, Dependence> requires = new TreeMap<>();
        for (Map.Entry<String, Dependence> e : m.configRequires().entrySet()) {
            Dependence d = e.getValue();
            if (d.requiresService()) {
                Service s = services.get(e.getKey());
                if (s == null) {
                    // no service provider config file
                    String cf = e.getKey().replace('.', '/');
                    Klass k = classes.get(cf);
                    if (k == null) {
                        throw new RuntimeException("service " + e.getKey() + " not found");
                    }
                    services.put(k.getClassName(), s = new Service(k));
                    k.getModule().addService(s);
                }
                if (requiresModuleDependence(m, s.service)) {
                    addDependence(m, s.service, requires);
                }
                requires.put(e.getKey(), d);
            } else {
                addDependence(m, d, requires);
            }
        }

        // add static dependences
        for (Klass from : m.classes()) {
            for (Klass to : getDeps(from, null)) {
                addDependence(m, to, requires);
            }
        }

        for (Service s : m.services()) {
            String name = s.service.getClassName();
            requires.put(name, new Dependence(name, EnumSet.of(SERVICE)));
        }

        return requires;
    }

    private void addDependence(Module from, Klass k,
                               Map<String, Dependence> requires) {
        Module to = k.getModule().group();
        assert from.group() != to;

        if (!getPackage(k).isExported) {
            to.exportsInternalClass(k, from);
        }
        addDependence(from, to, requires);
    }

    private void addDependence(Module from, Module to,
                               Map<String, Dependence> requires) {
        addDependence(from, to, requires, EnumSet.noneOf(Dependence.Identifier.class));
    }

    private void addDependence(Module from, Module to,
                               Map<String, Dependence> requires,
                               Set<Dependence.Identifier> mods) {
        String name = to.name();
        Dependence dep = requires.get(name);
        if (dep == null) {
            requires.put(name, dep = new Dependence(name, mods));
        } else {
            assert dep.name().equals(name);
        }
    }

    private void addDependence(Module m, Dependence d, Map<String, Dependence> requires) {
        Module other = factory().getModule(d.name());
        if (other == null) {
            throw new RuntimeException(m.name() + " requires "
                    + d.name() + "not found");
        }

        if (other.isEmpty() && !other.allowsEmpty()) {
            return;
        }
        addDependence(m, other, requires, d.mods());
    }

    public interface Visitor<R, P> {
        R visitModule(Module m) throws IOException;
    }

    public <R, P> void visit(Visitor<R, P> visitor) throws IOException {
        for (Module m : dependencesForModule.keySet()) {
            visitor.visitModule(m);
        }
    }

    public static class TopoSorter<T> {
        final Deque<T> result = new LinkedList<>();
        final Deque<T> nodes = new LinkedList<>();
        final Function<T,Set<T>> edges;
        TopoSorter(Collection<T> nodes, Function<T,Set<T>> edges) {
            for (T n : nodes) {
                // filter duplicated nodes
                if (!this.nodes.contains(n)) {
                    this.nodes.add(n);
                }
            }
            this.edges = edges;
            sort();
        }

        public Iterable<T> result() {
            return result;
        }

        private void sort() {
            Deque<T> visited = new LinkedList<>();
            Deque<T> done = new LinkedList<>();
            T node;
            while ((node = nodes.poll()) != null) {
                if (!visited.contains(node)) {
                    visit(node, visited, done);
                }
            }
        }

        private void visit(T m,
                           Deque<T> visited,
                           Deque<T> done) {
            if (visited.contains(m)) {
                if (!done.contains(m)) {
                    throw new IllegalArgumentException("Cyclic detected: " +
                        m + " " + edges.apply(m));
                }
                return;
            }
            visited.add(m);
            for (T e : edges.apply(m)) {
                visit(e, visited, done);
            }
            done.add(m);
            result.addLast(m);
        }
    }
}
