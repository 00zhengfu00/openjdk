/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import java.util.*;
import static java.lang.System.out;

import java.lang.module.*;
import java.lang.module.ModuleDependence.Modifier;
import static java.lang.module.ModuleDependence.Modifier.*;

import org.testng.annotations.*;
import static org.testng.Assert.*;


@Test
public class _ModuleDependence {

    private static ModuleDependence build(Set<Modifier> mods, String dn) {
        ModuleDependence md = new ModuleDependence(mods,
                                                   ModuleId.parse(dn));
        out.println(md);
        return md;
    }

    public void none() {
        ModuleDependence md = build(null, "foo");
        assertTrue(md.modifiers().isEmpty());
        assertEquals(md.id(), ModuleId.parse("foo"));
    }

    public void one() {
        ModuleDependence md = build(EnumSet.of(PUBLIC), "foo");
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC));
        assertEquals(md.id(), ModuleId.parse("foo"));
    }

    public void two() {
        ModuleDependence md = build(EnumSet.of(PUBLIC), "foo");
        assertEquals(md.modifiers(), EnumSet.of(PUBLIC));
    }

}
