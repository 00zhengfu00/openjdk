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

package jdk.test;

import java.lang.reflect.Layer;
import java.lang.reflect.Module;
import java.lang.module.Configuration;
import javax.annotation.Resource;
import javax.annotation.more.BigResource;

public class Main {
    public static void main(String[] args) {
        Module m1 = Resource.class.getModule();
        Module m2 = BigResource.class.getModule();

        Configuration cf = Layer.boot().configuration().get();
        System.out.format("%s loaded from %s%n", m1,
                          cf.findModule(m1.getName()).get().location().get());

        if (m1 != m2 || !m1.getName().equals("java.annotations.common"))
            throw new RuntimeException("java.annotations.common not upgraded");
    }
}
