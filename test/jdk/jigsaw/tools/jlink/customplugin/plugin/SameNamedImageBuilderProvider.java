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

package plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import jdk.tools.jlink.api.plugin.PluginOption;
import jdk.tools.jlink.api.plugin.builder.ExecutableImage;

import jdk.tools.jlink.api.plugin.builder.ImageBuilder;
import jdk.tools.jlink.api.plugin.builder.ImageBuilderProvider;

public class SameNamedImageBuilderProvider extends ImageBuilderProvider {

    private static final String NAME = "custom-image-builder";

    public SameNamedImageBuilderProvider() {
        super(NAME, NAME + "-description");
    }

    @Override
    public ExecutableImage canExecute(Path root) {
        return new ExecutableImage(root, Collections.emptySet(), Collections.emptyList());
    }

    @Override
    public void storeLauncherOptions(ExecutableImage image, List<String> arguments) {

    }

    @Override
    public ImageBuilder newPlugin(Map<PluginOption, Object> config) {
        Path imageOutDir = (Path) config.get(ImageBuilderProvider.IMAGE_PATH_OPTION);
        try {
            return new CustomImageBuilder(config, imageOutDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
