/*
 * Copyright (c)  2014, Oracle and/or its affiliates. All rights reserved.
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

package container;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;

import java.net.URL;
import java.net.URI;
import java.net.URLClassLoader;
import java.net.MalformedURLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple ClassLoader that that loads all newly selected modules in a
 * {@code ModuleGraph} with the same loader.
 */
public final class MultiModuleClassLoader extends URLClassLoader {
    private final Set<String> packages;

    public MultiModuleClassLoader(Configuration cf, ClassLoader parent) {
        super(moduleURLs(cf), parent);

        // create map of packages
        Set<String> pkgs = new HashSet<>();

        for (ModuleDescriptor descriptor: cf.descriptors()) {
            Set<String> contents = cf.findArtifact(descriptor.name()).packages();
            for (String pkg: contents) {
                if (pkgs.contains(pkg)) {
                    throw new Error(pkg + " defined by more than one module");
                }
                pkgs.add(pkg);
            }
        }

        this.packages = pkgs;
    }

    public MultiModuleClassLoader(Configuration cf) {
        this(cf, ClassLoader.getSystemClassLoader());
    }

    @Override
    protected Class<?> loadClass(String cn, boolean resolve)
            throws ClassNotFoundException
    {
        Class<?> c = findLoadedClass(cn);
        if (c == null) {

            boolean local = false;
            int i = cn.lastIndexOf('.');
            if (i > 0) {
                String pkg = cn.substring(0, i);
                if (packages.contains(pkg)) {
                    local = true;
                }
            }
            if (local) {
                c = findClass(cn);
            } else {
                return super.loadClass(cn, resolve);
            }
        }

        if (resolve)
            resolveClass(c);

        return c;
    }

    private static URL[] moduleURLs(Configuration cf) {
        return cf.descriptors()
                 .stream()
                 .map(md -> cf.findArtifact(md.name()).location())
                 .map(MultiModuleClassLoader::toURL)
                 .toArray(URL[]::new);
    }

    private static URL toURL(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    static {
        ClassLoader.registerAsParallelCapable();
    }
}
