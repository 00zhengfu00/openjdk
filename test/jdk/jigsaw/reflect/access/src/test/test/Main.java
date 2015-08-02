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

package test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Layer;
import java.lang.reflect.Method;
import java.lang.reflect.Module;
import java.util.Optional;

/**
 * Test access to public members in exported and non-exported packages.
 */

public class Main {

    public static void main(String[] args) throws Exception {

        Module thisModule = Main.class.getModule();
        assertTrue(thisModule.isNamed());

        Optional<Module> om = Layer.boot().findModule("target");
        assertTrue(om.isPresent());

        Module target = om.get();
        assertFalse(thisModule.canRead(target));

        tryAccessPublicMembers("p.Exported", false);
        tryAccessPublicMembers("q.Internal", false);

        thisModule.addReads(target);
        assertTrue(thisModule.canRead(target));

        tryAccessPublicMembers("p.Exported", true);
        tryAccessPublicMembers("q.Internal", false);
    }


    /**
     * Attempt to access public members in a target class.
     */
    static void tryAccessPublicMembers(String cn, boolean shouldSucceed)
        throws Exception
    {

        Class<?> clazz = Class.forName(cn);

        Module thisModule = Main.class.getModule();
        Module targetModule = clazz.getModule();

        // check if the target module is readable and whether the target
        // class is in an exported package or not.
        String pn = cn.substring(0, cn.lastIndexOf('.'));
        boolean reads = thisModule.canRead(targetModule);
        boolean exported = targetModule.isExported(pn, thisModule);

        assertTrue((reads && exported) == shouldSucceed);
        boolean shouldFail = !shouldSucceed;


        // Class.newInstance

        try {
            clazz.newInstance();
            assertTrue(shouldSucceed);
        } catch (IllegalAccessException e) {
            assertTrue(shouldFail);
        }


        // Constructor.newInstance and Constructor.setAccessible

        Constructor<?> ctor = clazz.getConstructor();
        try {
            ctor.newInstance();
            assertTrue(shouldSucceed);
        } catch (IllegalAccessException e) {
            assertTrue(shouldFail);
        }
        try {
            ctor.setAccessible(true);
            assertTrue(shouldSucceed);
            ctor.newInstance();
        } catch (InaccessibleObjectException e) {
            assertTrue(shouldFail);
        }


        // Method.invoke and Method.setAccessible

        Method m = clazz.getDeclaredMethod("run");
        try {
            m.invoke(null);
            assertTrue(shouldSucceed);
        } catch (IllegalAccessException e) {
            assertTrue(shouldFail);
        }
        try {
            m.setAccessible(true);
            assertTrue(shouldSucceed);
            m.invoke(null);
        } catch (InaccessibleObjectException e) {
            assertTrue(shouldFail);
        }

        // Field.get, Field.set and Field.setAccessible

        Field f = clazz.getDeclaredField("field");
        try {
            f.get(null);
            assertTrue(shouldSucceed);
        } catch (IllegalAccessException e) {
            assertTrue(shouldFail);
        }
        try {
            f.set(null, 100);
            assertTrue(shouldSucceed);
        } catch (IllegalAccessException e) {
            assertTrue(shouldFail);
        }
        try {
            f.setAccessible(true);
            f.get(null);
            f.set(null, 100);
            assertTrue(shouldSucceed);
        } catch (InaccessibleObjectException e) {
            assertTrue(shouldFail);
        }

    }


    static void assertTrue(boolean expr) {
        if (!expr) throw new RuntimeException();
    }

    static void assertFalse(boolean expr) {
        assertTrue(!expr);
    }
}
