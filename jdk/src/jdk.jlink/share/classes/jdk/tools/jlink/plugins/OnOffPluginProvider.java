/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.plugins;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 *
 * On/Off plugin provider support class.
 * @param <T>
 */
public interface OnOffPluginProvider<T> extends CmdPluginProvider<T> {

    public static final String ON_ARGUMENT = "on";
    public static final String OFF_ARGUMENT = "off";

    @Override
    public default T[] newPlugins(String[] arguments,
            Map<String, String> otherOptions)
            throws IOException {
        Objects.requireNonNull(arguments);
        if (arguments.length != 1) {
            throw new IOException("Invalid number of arguments expecting "
                    + getToolArgument());
        }
        if (!OFF_ARGUMENT.equals(arguments[0])
                && !ON_ARGUMENT.equals(arguments[0])) {
            throw new IOException("Invalid argument " + arguments[0]
                    + ", expecting " + ON_ARGUMENT + " or "
                    + OFF_ARGUMENT);
        }
        if (OFF_ARGUMENT.equals(arguments[0])) {
            return null;
        }
        return createPlugins(otherOptions);
    }

    public T[] createPlugins(Map<String, String> otherOptions)
            throws IOException;

    @Override
    public default String getToolArgument() {
        return ON_ARGUMENT + "|"
                + OFF_ARGUMENT;
    }

    public default boolean isEnabledByDefault() {
        return false;
    }

}
