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

module jdk.jlink {
    exports jdk.tools.jlink.plugins;

    requires jdk.compiler;
    requires jdk.internal.opt;
    requires jdk.jdeps;

    uses jdk.tools.jlink.plugins.TransformerPluginProvider;
    uses jdk.tools.jlink.plugins.ImageBuilderProvider;
    uses jdk.tools.jlink.plugins.PostProcessorPluginProvider;

    provides jdk.tools.jlink.plugins.ImageBuilderProvider with jdk.tools.jlink.plugins.DefaultImageBuilderProvider;

    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.FileCopierProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.FileReplacerProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.StringSharingProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.StripDebugProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.ExcludeProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.ExcludeFilesProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.InstalledModuleDescriptorProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.StripNativeCommandsProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.SortResourcesProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.ZipCompressProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.DefaultCompressProvider;
    provides jdk.tools.jlink.plugins.TransformerPluginProvider with jdk.tools.jlink.internal.plugins.OptimizationProvider;
}
