/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.module;

import java.lang.reflect.Module;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleDescriptor.Provides;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A <em>services catalog</em>. Each {@code ClassLoader} and {@code Layer} has
 * an optional {@code ServicesCatalog} for modules that provide services.
 *
 * @see java.util.ServiceLoader
 */
public final class ServicesCatalog {

    /**
     * Represents a service provider in the services catalog.
     */
    public final class ServiceProvider {
        private final Module module;
        private final String providerName;

        public ServiceProvider(Module module, String providerName) {
            this.module = module;
            this.providerName = providerName;
        }

        public Module module() {
            return module;
        }

        public String providerName() {
            return providerName;
        }

        @Override
        public int hashCode() {
            return Objects.hash(module, providerName);
        }

        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof ServiceProvider))
                return false;
            ServiceProvider that = (ServiceProvider)ob;
            return Objects.equals(this.module, that.module)
                    && Objects.equals(this.providerName, that.providerName);
        }
    }

    // service name -> service providers
    private Map<String, Set<ServiceProvider>> map = new ConcurrentHashMap<>();

    private ServicesCatalog() { }

    /**
     * Returns a unmodifiable set that is the union of the given sets.
     */
    private <T> Set<T> union(Set<T> s1, Set<T> s2) {
        Set<T> result = new HashSet<>(s1);
        result.addAll(s2);
        return Collections.unmodifiableSet(result);
    }

    /**
     * Adds or replaces an entry in the map.
     */
    private void replace(String sn,
                         Set<ServiceProvider> oldSet,
                         Set<ServiceProvider> newSet) {
        boolean replaced;
        if (oldSet == null) {
            replaced = (map.putIfAbsent(sn, newSet) == null);
        } else {
            replaced = map.replace(sn, oldSet, newSet);
        }
        if (replaced) {
            // added or replaced
            return;
        }
        synchronized (this) {
            oldSet = map.get(sn); // re-read
            assert oldSet != null;
            map.put(sn, union(oldSet, newSet));
        }
    }

    /**
     * Creates a ServicesCatalog that supports concurrent registration and
     * and lookup
     */
    public static ServicesCatalog create() {
        return new ServicesCatalog();
    }

    /**
     * Registers the providers in the given module in this services catalog.
     *
     * @throws UnsupportedOperationException
     *         If this services catalog is immutable
     */
    public void register(Module module) {
        ModuleDescriptor descriptor = module.getDescriptor();
        for (Provides provides : descriptor.provides().values()) {
            String sn = provides.service();
            Set<String> providerNames = provides.providers();

            Set<ServiceProvider> oldSet = map.get(sn);
            Set<ServiceProvider> newSet;
            if (oldSet == null && providerNames.size() == 1) {
                String pn = providerNames.iterator().next();
                newSet = Set.of(new ServiceProvider(module, pn));
            } else {
                newSet = new HashSet<>();
                if (oldSet != null) {
                    newSet.addAll(oldSet);
                }
                for (String pn : providerNames) {
                    newSet.add(new ServiceProvider(module, pn));
                }
            }

            replace(sn, oldSet, newSet);
        }
    }

    /**
     * Add a provider in the given module to this services catalog
     */
    public void addProvider(Module module, Class<?> service, Class<?> impl) {
        String sn = service.getName();
        ServiceProvider provider = new ServiceProvider(module, impl.getName());
        Set<ServiceProvider> providers = Set.of(provider);

        Set<ServiceProvider> oldSet = map.get(sn);
        Set<ServiceProvider> newSet;
        if (oldSet == null) {
            newSet = providers;
        } else {
            newSet = union(oldSet, providers);
        }

        replace(sn, oldSet, newSet);
    }

    /**
     * Returns the (possibly empty) set of service providers that implement the
     * given service type.
     */
    public Set<ServiceProvider> findServices(String service) {
        return map.getOrDefault(service, Collections.emptySet());
    }
}