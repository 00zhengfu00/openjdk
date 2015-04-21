/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.lang.module.ExtendedModuleDescriptor;
import java.lang.module.ModuleArtifact;
import java.lang.module.ModuleArtifactFinder;
import java.lang.module.ModuleExport;
import java.lang.module.ModuleReader;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A container of modules that acts as a ModuleArtifactFinder for testing
 * purposes.
 */

class ModuleArtifactLibrary implements ModuleArtifactFinder {
    private final Set<ExtendedModuleDescriptor> modules = new HashSet<>();
    private final Map<String, ModuleArtifact> namesToArtifact = new HashMap<>();

    ModuleArtifactLibrary(ExtendedModuleDescriptor... descriptors) {
        for (ExtendedModuleDescriptor descriptor: descriptors) {
            String name = descriptor.name();
            if (!namesToArtifact.containsKey(name)) {
                modules.add(descriptor);

                URI uri = URI.create("module:/" + descriptor.name());

                Set<String> packages = descriptor.exports().stream()
                        .map(ModuleExport::pkg)
                        .collect(Collectors.toSet());

                ModuleArtifact artifact = new ModuleArtifact(descriptor, packages, uri) {
                    @Override
                    public ModuleReader open() throws IOException {
                        throw new IOException("No module reader for: " + uri);
                    }
                };

                namesToArtifact.put(name, artifact);
            }
        }
    }

    @Override
    public ModuleArtifact find(String name) {
        return namesToArtifact.get(name);
    }

    @Override
    public Set<ModuleArtifact> allModules() {
        return new HashSet<>(namesToArtifact.values());
    }
}

