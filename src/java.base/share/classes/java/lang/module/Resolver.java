/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor.Provides;
import java.lang.module.ModuleDescriptor.Requires;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.internal.module.Hasher.DependencyHashes;

/**
 * The resolver used by {@link Configuration#resolve} and {@link Configuration#bind}.
 *
 * TODO:
 * - replace makeGraph with efficient implementation for multiple layers
 * - replace cycle detection. The current DFS is fast at startup for the boot
 *   layer but isn't generally scalable
 * - automatic modules => self references in readability graph
 */

final class Resolver {

    /**
     * The result of resolution or binding.
     */
    final class Resolution {

        // the set of module descriptors
        private final Set<ModuleDescriptor> selected;

        // maps name to module reference for modules in this resolution
        private final Map<String, ModuleReference> nameToReference;

        // set of nameToReference.values()
        private final Set<ModuleReference> references;

        // the readability graph
        private final Map<ModuleDescriptor, Set<ModuleDescriptor>> graph;

        // maps a service type (by name) to the set of modules that provide
        private final Map<String, Set<ModuleDescriptor>> serviceToProviders;

        Resolution(Set<ModuleDescriptor> selected,
                   Map<String, ModuleReference> nameToReference,
                   Map<ModuleDescriptor, Set<ModuleDescriptor>> graph,
                   Map<String, Set<ModuleDescriptor>> serviceToProviders)
        {
            this.selected = Collections.unmodifiableSet(selected);
            this.nameToReference = Collections.unmodifiableMap(nameToReference);
            Set<ModuleReference> refs = new HashSet<>(nameToReference.values());
            this.references = Collections.unmodifiableSet(refs);
            this.graph = graph; // no need to make defensive copy
            this.serviceToProviders = serviceToProviders; // no need to make copy
        }

        Set<ModuleDescriptor> selected() {
            return selected;
        }

        Set<ModuleReference> references() {
            return references;
        }

        Optional<ModuleReference> findReference(String name) {
            return Optional.ofNullable(nameToReference.get(name));
        }

        Set<ModuleDescriptor> reads(ModuleDescriptor descriptor) {
            Set<ModuleDescriptor> reads = graph.get(descriptor);
            if (reads == null) {
                return null;
            } else {
                return Collections.unmodifiableSet(reads);
            }
        }

        Set<ModuleDescriptor> provides(String sn) {
            Set<ModuleDescriptor> provides = serviceToProviders.get(sn);
            if (provides == null) {
                return Collections.emptySet();
            } else {
                return Collections.unmodifiableSet(provides);
            }
        }

        /**
         * Returns a new Resolution that this is this Resolution augmented with
         * modules (located via the module reference finders) that are induced
         * by service-use relationships.
         */
        Resolution bind() {
            return Resolver.this.bind();
        }

    }


    private final ModuleFinder beforeFinder;
    private final Layer layer;
    private final ModuleFinder afterFinder;

    // the set of module descriptors, added to at each iteration of resolve
    private final Set<ModuleDescriptor> selected = new HashSet<>();

    // map of module names to references
    private final Map<String, ModuleReference> nameToReference = new HashMap<>();

    // cached by resolve
    private Map<ModuleDescriptor, Set<ModuleDescriptor>> cachedGraph;


    private Resolver(ModuleFinder beforeFinder,
                     Layer layer,
                     ModuleFinder afterFinder)
    {
        this.beforeFinder = beforeFinder;
        this.layer = layer;
        this.afterFinder = afterFinder;
    }

    /**
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException
     */
    static Resolution resolve(ModuleFinder beforeFinder,
                              Layer layer,
                              ModuleFinder afterFinder,
                              Collection<String> roots)
    {
        Resolver resolver = new Resolver(beforeFinder, layer, afterFinder);
        return resolver.resolve(roots);
    }

    /**
     * Resolve the given collection of modules (by name).
     */
    private Resolution resolve(Collection<String> roots) {

        long start = trace_start("Resolve");

        // create the visit stack to get us started
        Deque<ModuleDescriptor> q = new ArrayDeque<>();
        for (String root : roots) {

            ModuleReference mref = find(beforeFinder, root);
            if (mref == null) {
                // ## Does it make sense to attempt to locate root modules with
                //    a finder other than the beforeFinder?
                mref = find(afterFinder, root);
                if (mref == null) {
                    fail("Module %s not found", root);
                }
            }

            if (TRACE) {
                trace("Root module %s located", root);
                if (mref.location().isPresent())
                    trace("  (%s)", mref.location().get());
            }

            nameToReference.put(root, mref);
            q.push(mref.descriptor());
        }

        resolve(q);

        detectCycles();

        checkHashes();

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph = makeGraph();
        cachedGraph = graph;

        Resolution r = new Resolution(selected,
                                      nameToReference,
                                      graph,
                                      Collections.emptyMap());

        if (TRACE) {
            long duration = System.currentTimeMillis() - start;
            Set<String> names
                = selected.stream()
                    .map(ModuleDescriptor::name)
                    .sorted()
                    .collect(Collectors.toSet());
            trace("Resolve completed in %s ms", duration);
            names.stream().sorted().forEach(name -> trace("  %s", name));
        }

        return r;
    }

    /**
     * Poll the given {@code Deque} for modules to resolve. On completion the
     * {@code Deque} will be empty and the selected set will contain the modules
     * that were selected.
     *
     * @return The set of module (descriptors) selected by this invocation of
     *         resolve
     */
    private Set<ModuleDescriptor> resolve(Deque<ModuleDescriptor> q) {
        Set<ModuleDescriptor> newlySelected = new HashSet<>();

        while (!q.isEmpty()) {
            ModuleDescriptor descriptor = q.poll();
            assert nameToReference.containsKey(descriptor.name());
            newlySelected.add(descriptor);

            // process dependences
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                String dn = requires.name();

                // before finder
                ModuleReference mref = find(beforeFinder, dn);

                // already defined to the runtime
                if (mref == null && layer.findModule(dn).isPresent()) {
                    trace("Module %s in parent Layer", dn);
                    continue;
                }

                // after finder
                if (mref == null) {
                    mref = find(afterFinder, dn);
                }

                // not found
                if (mref == null) {
                    fail("Module %s not found, required by %s",
                        dn, descriptor.name());
                }

                // check if module descriptor has already been seen
                ModuleDescriptor other = mref.descriptor();
                if (!selected.contains(other) && !newlySelected.contains(other)) {

                    if (nameToReference.put(dn, mref) == null) {
                        if (TRACE) {
                            trace("Module %s located, required by %s",
                                dn, descriptor.name());
                            if (mref.location().isPresent())
                                trace("  (%s)", mref.location().get());
                        }
                    }

                    newlySelected.add(other);

                    q.offer(other);
                }
            }
        }

        // add the newly selected modules the selected set
        selected.addAll(newlySelected);

        return newlySelected;
    }

    /**
     * Updates the Resolver with modules (located via the module reference finders)
     * that are induced by service-use relationships.
     */
    private Resolution bind() {

        long start = trace_start("Bind");

        // Scan the finders for all available service provider modules. As java.base
        // uses services then all finders will need to be scanned anyway.
        Map<String, Set<ModuleReference>> availableProviders = new HashMap<>();
        findAll().forEach(mref -> {
            ModuleDescriptor descriptor = mref.descriptor();
            if (!descriptor.provides().isEmpty()) {
                descriptor.provides().keySet().forEach(s ->
                    availableProviders.computeIfAbsent(s, k -> new HashSet<>()).add(mref));
            }
        });

        int initialSize = selected.size();

        // create the visit stack
        Deque<ModuleDescriptor> q = new ArrayDeque<>();

        // the initial set of modules that may use services
        Set<ModuleDescriptor> candidateConsumers;
        if (layer == null) {
            candidateConsumers = selected;
        } else {
            candidateConsumers = new HashSet<>();
            candidateConsumers.addAll(layer.allModuleDescriptors());
            candidateConsumers.addAll(selected);
        }

        // Where there is a consumer of a service then resolve all modules
        // that provide an implementation of that service
        do {
            for (ModuleDescriptor descriptor : candidateConsumers) {
                if (!descriptor.uses().isEmpty()) {
                    for (String service : descriptor.uses()) {
                        Set<ModuleReference> mrefs = availableProviders.get(service);
                        if (mrefs != null) {
                            for (ModuleReference mref : mrefs) {
                                ModuleDescriptor provider = mref.descriptor();
                                if (!provider.equals(descriptor)) {

                                    trace("Module %s provides %s, used by %s",
                                          provider.name(), service, descriptor.name());

                                    if (!selected.contains(provider)) {
                                        if (nameToReference.put(provider.name(), mref) == null) {

                                            if (TRACE && mref.location().isPresent())
                                                trace("  (%s)", mref.location().get());

                                        }
                                        q.push(provider);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            candidateConsumers = resolve(q);

        } while (!candidateConsumers.isEmpty());


        // For debugging purposes, print out the service consumers in the
        // selected set that use providers in a parent layer
        if (TRACE) {
            Set<ModuleDescriptor> allModules = layer.allModuleDescriptors();
            for (ModuleDescriptor descriptor : selected) {
                if (!descriptor.uses().isEmpty()) {
                    for (String service : descriptor.uses()) {
                        for (ModuleDescriptor other : allModules) {
                            if (other.provides().get(service) != null) {
                                trace("Module %s provides %s, used by %s",
                                        other.name(), service, descriptor.name());
                            }
                        }
                    }
                }
            }
        }

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph;

        // If the number of selected modules has increased then the post
        // resolution checks need to be repeated and the readability graph
        // re-generated.
        if (selected.size() > initialSize) {

            detectCycles();

            checkHashes();

            graph = makeGraph();

        } else {
            graph = cachedGraph;
        }

        // Finally create the map of service -> provider modules
        Map<String, Set<ModuleDescriptor>> serviceToProviders = new HashMap<>();
        for (ModuleDescriptor descriptor : selected) {
            Map<String, Provides> provides = descriptor.provides();
            for (Map.Entry<String, Provides> entry : provides.entrySet()) {
                String sn = entry.getKey();
                serviceToProviders.computeIfAbsent(sn, k -> new HashSet<>())
                        .add(descriptor);
            }
        }

        Resolution r = new Resolution(selected,
                                      nameToReference,
                                      graph,
                                      serviceToProviders);

        if (TRACE) {
            long duration = System.currentTimeMillis() - start;
            Set<String> names = selected.stream()
                .map(ModuleDescriptor::name)
                .sorted()
                .collect(Collectors.toSet());
            trace("Bind completed in %s ms", duration);
            names.stream().sorted().forEach(name -> trace("  %s", name));
        }

        return r;
    }


    /**
     * Computes and sets the readability graph for the modules in the given
     * Resolution object.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability graph will contain m1 requires requires m2, m1
     * requires m3, and m2 requires m3.
     */
    private Map<ModuleDescriptor, Set<ModuleDescriptor>> makeGraph() {

        // name -> ModuleDescriptor lookup
        Map<String, ModuleDescriptor> nameToDescriptor = new HashMap<>();
        selected.forEach(d -> nameToDescriptor.put(d.name(), d));

        // the "reads" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g2 = new HashMap<>();

        // need "requires public" from the modules in parent layers as
        // there may be selected modules that have a dependence.
        Layer l = this.layer;
        while (l != null) {
            Configuration cf = l.configuration().orElse(null);
            if (cf != null) {
                for (ModuleDescriptor descriptor: cf.descriptors()) {
                    Set<ModuleDescriptor> requiresPublic = new HashSet<>();
                    g2.put(descriptor, requiresPublic);
                    for (Requires d: descriptor.requires()) {
                        String dn = d.name();
                        if (nameToDescriptor.get(dn) == null
                            && d.modifiers().contains(Requires.Modifier.PUBLIC))
                        {
                            ModuleReference mref = l.findReference(dn).orElse(null);
                            if (mref == null)
                                throw new InternalError();
                            requiresPublic.add(mref.descriptor());
                        }
                    }
                }
            }
            l = l.parent().orElse(null);
        }

        // add the module dependence edges from the newly selected modules
        for (ModuleDescriptor m : selected) {

            Set<ModuleDescriptor> reads = new HashSet<>();
            g1.put(m, reads);

            Set<ModuleDescriptor> requiresPublic = new HashSet<>();
            g2.put(m, requiresPublic);

            for (Requires d: m.requires()) {
                String dn = d.name();
                ModuleDescriptor other = nameToDescriptor.get(dn);
                if (other == null && layer != null)
                    other = layer.findReference(dn)
                                 .map(ModuleReference::descriptor)
                                 .orElse(null);
                if (other == null)
                    throw new InternalError(dn + " not found??");

                // m requires other => m reads other
                reads.add(other);

                // m requires public other
                if (d.modifiers().contains(Requires.Modifier.PUBLIC)) {
                    requiresPublic.add(other);
                }
            }

            // if m is an automatic module then it requires public all
            // selected module and all (non-shadowed) modules in parent layers
            if (m.isAutomatic()) {
                for (ModuleDescriptor other : nameToDescriptor.values()) {
                    if (!other.equals(m)) {
                        reads.add(other);
                        requiresPublic.add(other);
                    }
                }

                l = this.layer;
                while (l != null) {
                    Configuration cf = layer.configuration().orElse(null);
                    if (cf != null) {
                        for (ModuleDescriptor other : cf.descriptors()) {
                            String name = other.name();
                            if (nameToDescriptor.putIfAbsent(name, other) == null) {
                                reads.add(other);
                                requiresPublic.add(other);
                            }
                        }
                    }
                    l = l.parent().orElse(null);
                }
            }

        }

        // add to g1 until there are no more requires public to propagate
        boolean changed;
        Map<ModuleDescriptor, Set<ModuleDescriptor>> changes = new HashMap<>();
        do {
            changed = false;
            for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: g1.entrySet()) {
                ModuleDescriptor m1 = entry.getKey();
                Set<ModuleDescriptor> m1Reads = entry.getValue();
                for (ModuleDescriptor m2: m1Reads) {
                    Set<ModuleDescriptor> m2RequiresPublic = g2.get(m2);
                    for (ModuleDescriptor m3 : m2RequiresPublic) {
                        if (!m1Reads.contains(m3)) {
                            changes.computeIfAbsent(m1, k -> new HashSet<>()).add(m3);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: changes.entrySet()) {
                    ModuleDescriptor m1 = entry.getKey();
                    g1.get(m1).addAll(entry.getValue());
                }
                changes.clear();
            }

        } while (changed);

        return g1;
    }


    /**
     * Checks the hashes in the extended module descriptor to ensure that they
     * match the hash of the dependency's module reference.
     */
    private void checkHashes() {

        for (ModuleDescriptor descriptor : selected) {
            String mn = descriptor.name();

            // get map of module names to hash
            Optional<DependencyHashes> ohashes
                = nameToReference.get(mn).descriptor().hashes();
            if (!ohashes.isPresent())
                continue;
            DependencyHashes hashes = ohashes.get();

            // check dependences
            for (Requires md: descriptor.requires()) {
                String dn = md.name();
                String recordedHash = hashes.hashFor(dn);

                if (recordedHash != null) {
                    ModuleReference mref = nameToReference.get(dn);
                    if (mref == null)
                        mref = layer.findReference(dn).orElse(null);
                    if (mref == null)
                        throw new InternalError(dn + " not found");

                    String actualHash = mref.computeHash(hashes.algorithm());
                    if (actualHash == null)
                        fail("Unable to compute the hash of module %s", dn);

                    if (!recordedHash.equals(actualHash)) {
                        fail("Hash of %s (%s) differs to expected hash (%s)",
                                dn, actualHash, recordedHash);
                    }
                }
            }
        }

    }


    /**
     * Checks the given module graph for cycles.
     *
     * For now the implementation is a simple depth first search on the
     * dependency graph. We'll replace this later, maybe with Tarjan if we
     * are also checking connectedness.
     */
    private void detectCycles() {
        visited = new HashSet<>();
        visitPath = new LinkedHashSet<>(); // preserve insertion order
        selected.forEach(d -> visit(d));
    }

    // the modules that were visited
    private Set<ModuleDescriptor> visited;

    // the modules in the current visit path
    private Set<ModuleDescriptor> visitPath;

    private void visit(ModuleDescriptor descriptor) {
        if (!visited.contains(descriptor)) {
            boolean added = visitPath.add(descriptor);
            if (!added) {
                throw new ResolutionException("Cycle detected: " +
                                              cycleAsString(descriptor));
            }
            for (Requires requires : descriptor.requires()) {
                ModuleReference mref = nameToReference.get(requires.name());
                if (mref != null) {
                    // dependency is in this configuration
                    ModuleDescriptor other = mref.descriptor();
                    // ignore self reference
                    if (other != descriptor)
                        visit(other);
                }
            }
            visitPath.remove(descriptor);
            visited.add(descriptor);
        }
    }

    /**
     * Returns a String with a list of the modules in a detected cycle.
     */
    private String cycleAsString(ModuleDescriptor descriptor) {
        List<ModuleDescriptor> list = new ArrayList<>(visitPath);
        list.add(descriptor);
        int index = list.indexOf(descriptor);
        return list.stream()
                   .skip(index)
                   .map(ModuleDescriptor::name)
                   .collect(Collectors.joining(" -> "));
    }


    /**
     * Returns true if a module of the given module's name is in a parent Layer
     */
    private boolean inParentLayer(ModuleReference mref) {
        return layer.findModule(mref.descriptor().name()).isPresent();
    }

    /**
     * Invokes the finder's find method to find the given module.
     */
    private static ModuleReference find(ModuleFinder finder, String mn) {
        try {
            return finder.find(mn).orElse(null);
        } catch (UncheckedIOException e) {
            throw new ResolutionException(e.getCause());
        } catch (RuntimeException | Error e) {
            throw new ResolutionException(e);
        }
    }

    /**
     * Returns a Stream of all modules.
     */
    private Stream<ModuleReference> findAll() {
        try {

            // only one source of modules?
            Set<ModuleReference> preModules = beforeFinder.findAll();
            Set<ModuleReference> postModules = afterFinder.findAll();
            if (layer == Layer.empty()) {
                if (preModules.isEmpty())
                    return postModules.stream();
                if (postModules.isEmpty())
                   return preModules.stream();
            }

            Stream<ModuleReference> s1 = preModules.stream();
            Stream<ModuleReference> s2
                = postModules.stream().filter(m -> !inParentLayer(m));
            return Stream.concat(s1, s2);

        } catch (UncheckedIOException e) {
            throw new ResolutionException(e.getCause());
        } catch (RuntimeException | Error e) {
            throw new ResolutionException(e);
        }
    }


    /**
     * Tracing support, limited to boot layer for now.
     */

    private final boolean TRACE
        = Boolean.getBoolean("jdk.launcher.traceResolver")
            && (Layer.boot() == null);

    private String op;

    private long trace_start(String op) {
        this.op = op;
        return System.currentTimeMillis();
    }

    private void trace(String fmt, Object ... args) {
        if (TRACE) {
            System.out.print("[" + op + "] ");
            System.out.format(fmt, args);
            System.out.println();
        }
    }


    private static void fail(String fmt, Object ... args) {
        throw new ResolutionException(fmt, args);
    }

}
