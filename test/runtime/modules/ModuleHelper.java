/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Module;
import java.net.URI;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.util.*;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReader;
import jdk.test.lib.*;
import sun.hotspot.WhiteBox;

public class ModuleHelper {

    public static void DefineModule(Object module, String version, String location,
                                    String[] pkgs) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        wb.DefineModule(module, version, location, pkgs);
    }

    public static void AddModuleExports(Object from, String pkg, Object to) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        wb.AddModuleExports(from, pkg, to);
        invoke(findMethod("addExportsNoSync"), from, pkg, to);
    }

    public static void AddReadsModule(Object from, Object to) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        wb.AddReadsModule(from, to);
        invoke(findMethod("addReadsNoSync"), from, to);
    }

    public static void AddModulePackage(Object m, String pkg) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        wb.AddModulePackage(m, pkg);
        invoke(findMethod("addPackageNoSync"), m, pkg);
    }
    public static void AddModuleExportsToAllUnnamed(Object m, String pkg) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        wb.AddModuleExportsToAllUnnamed(m, pkg);
        // TBD: invoke(findMethod("addModExpAllUnnNoSync"), m, pkg);
    }

    public static boolean CanReadModule(Object from, Object to) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        return wb.CanReadModule(from, to);
    }

    public static boolean IsExportedToModule(Object from, String pkg,
                                             Object to) throws Throwable {
        WhiteBox wb = WhiteBox.getWhiteBox();
        return wb.IsExportedToModule(from, pkg, to);
    }

    public static Module ModuleObject(String name, ClassLoader loader, String[] pkgs) throws Throwable {
        Set<String> pkg_set = new HashSet<>();
        if (pkgs != null) {
            for (String pkg: pkgs) {
                pkg_set.add(pkg.replace('/', '.'));
           }
        } else {
            pkg_set = Collections.emptySet();
        }

        ModuleDescriptor descriptor =
            new ModuleDescriptor.Builder(name).conceals(pkg_set).build();
        URI uri = URI.create("module:/" + name);

        Class[] cArg = new Class[2];
        cArg[0] = ClassLoader.class;
        cArg[1] = java.lang.module.ModuleDescriptor.class;
        Constructor ctor = findCtor(cArg);
        return (Module)invokeCtor(ctor, loader, descriptor);
    }

    private static Object invokeCtor(Constructor c, Object... args) throws Throwable {
        try {
            return c.newInstance(args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Constructor findCtor(Class[] cArg) throws Throwable {
        Constructor ctor = java.lang.reflect.Module.class.getDeclaredConstructor(cArg);
        if (ctor != null) {
            ctor.setAccessible(true);
            return ctor;
        }
        throw new RuntimeException("Failed to find constructor in java.lang.reflect.Module");
    }

    private static Object invoke(Method m, Object obj, Object... args) throws Throwable {
        try {
            return m.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Method findMethod(String name) {
        for (Method m : java.lang.reflect.Module.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new RuntimeException("Failed to find method " + name + " in java.lang.reflect.Module");
    }
}
