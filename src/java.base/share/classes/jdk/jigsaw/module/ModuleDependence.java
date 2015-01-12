/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.module;

import java.io.*;
import java.util.*;


/**
 * <p> A dependence upon a module </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class ModuleDependence
    implements Comparable<ModuleDependence>, Serializable
{

    public static enum Modifier {
        OPTIONAL, PUBLIC, SYNTHETIC, SYNTHESIZED;
    }

    private final Set<Modifier> mods;
    private final ModuleId id;

    public ModuleDependence(Set<Modifier> ms, ModuleId id) {
        if (id.version() != null)
            throw new IllegalArgumentException("Version not allowed: " + id);
        if (ms == null)
            mods = Collections.emptySet();
        else
            mods = Collections.unmodifiableSet(ms);
        this.id = id;
    }

    public ModuleDependence(Set<Modifier> mods, String mids) {
        this(mods, ModuleId.parse(mids));
    }

    public Set<Modifier> modifiers() {
        return mods;
    }

    /**
     * Return the module id.
     */
    public ModuleId id() {
        return id;
    }

    @Override
    public int compareTo(ModuleDependence that) {
        return this.id.name().compareTo(that.id.name());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof ModuleDependence))
            return false;
        ModuleDependence that = (ModuleDependence)ob;
        return (id.equals(that.id) && mods.equals(that.mods));
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 43 + mods.hashCode();
    }

    @Override
    public String toString() {
        return Dependence.toString(mods, id.toString());
    }

}
