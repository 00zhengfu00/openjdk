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
package jdk.tools.jlink.internal.plugins;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.plugins.StringTable;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.util.CheckClassAdapter;
import jdk.tools.jlink.internal.plugins.asm.AsmModulePool;
import jdk.tools.jlink.internal.plugins.optim.ForNameFolding;

/**
 *
 * Optimize Classes following various strategies. Strategies are implementation
 * of <code>ClassOptimizer</code> and <code>MethodOptimizer</code>.
 */
public final class OptimizationPlugin extends AsmPlugin {

    public interface Optimizer {

        void close() throws IOException;
    }

    public interface ClassOptimizer extends Optimizer {

        boolean optimize(Consumer<String> logger, AsmPools pools,
                AsmModulePool modulePool,
                ClassNode cn) throws Exception;
    }

    public interface MethodOptimizer extends Optimizer {

        boolean optimize(Consumer<String> logger, AsmPools pools,
                AsmModulePool modulePool,
                ClassNode cn, MethodNode m) throws Exception;
    }

    private List<Optimizer> optimizers = new ArrayList<>();

    private OutputStream stream;
    private int numMethods;

    OptimizationPlugin(String[] arguments, Map<String, String> options) {
        String strategies = arguments[0];
        String[] arr = strategies.split(":");
        for (String s : arr) {
            if (s.equals(OptimizationProvider.ALL)) {
                optimizers.clear();
                optimizers.add(new ForNameFolding());
                break;
            } else {
                if (s.equals(OptimizationProvider.FORNAME_REMOVAL)) {
                    optimizers.add(new ForNameFolding());
                } else {
                    throw new RuntimeException("Unknown optimization");
                }
            }
        }
        String f = options.get(OptimizationProvider.LOG_FILE);
        if (f != null) {
            try {
                stream = new FileOutputStream(f);
            } catch (FileNotFoundException ex) {
                System.err.println(ex);
            }
        }
    }

    private void log(String content) {
        if (stream != null) {
            try {
                content = content + "\n";
                stream.write(content.getBytes());
            } catch (IOException ex) {
                System.err.println(ex);
            }
        }
    }

    private void close() throws IOException {
        log("Num analyzed methods " + numMethods);

        for (Optimizer optimizer : optimizers) {
            try {
                optimizer.close();
            } catch (IOException ex) {
                System.err.println("Error closing optimizer " + ex);
            }
        }
        if (stream != null) {
            stream.close();
        }
    }

    @Override
    public String getName() {
        return OptimizationProvider.NAME;
    }

    @Override
    public void visit(AsmPools pools, StringTable strings) throws IOException {
        try {
            for (AsmModulePool p : pools.getModulePools()) {

                p.visitClassReaders((reader) -> {
                    ClassWriter w = null;
                    try {
                        w = optimize(pools, p, reader);
                    } catch (IOException ex) {
                        throw new RuntimeException("Problem optimizing "
                                + reader.getClassName(), ex);
                    }
                    return w;
                });
            }
        } finally {
            close();
        }
    }

    private ClassWriter optimize(AsmPools pools, AsmModulePool modulePool,
            ClassReader reader)
            throws IOException {
        ClassNode cn = new ClassNode();
        ClassWriter writer = null;
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) == 0) {
            reader.accept(cn, ClassReader.EXPAND_FRAMES);
            boolean optimized = false;
            for (Optimizer optimizer : optimizers) {
                if (optimizer instanceof ClassOptimizer) {
                    try {
                        boolean optim = ((ClassOptimizer) optimizer).
                                optimize(this::log, pools, modulePool, cn);
                        if (optim) {
                            optimized = true;
                        }
                    } catch (Throwable ex) {
                        throw new RuntimeException("Exception optimizing "
                                + reader.getClassName(), ex);
                    }
                } else {
                    MethodOptimizer moptimizer = (MethodOptimizer) optimizer;
                    for (MethodNode m : cn.methods) {
                        if ((m.access & Opcodes.ACC_ABSTRACT) == 0
                                && (m.access & Opcodes.ACC_NATIVE) == 0) {
                            numMethods += 1;
                            try {
                                boolean optim = moptimizer.
                                        optimize(this::log, pools, modulePool, cn, m);
                                if (optim) {
                                    optimized = true;
                                }
                            } catch (Throwable ex) {
                                throw new RuntimeException("Exception optimizing "
                                        + reader.getClassName() + "." + m.name, ex);
                            }

                        }
                    }
                }
            }

            if (optimized) {
                writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                try {
                    // add a validation layer in between to check for class vallidity
                    CheckClassAdapter ca = new CheckClassAdapter(writer);
                    cn.accept(ca);
                } catch (Exception ex) {
                    throw new RuntimeException("Exception optimizing class " + cn.name, ex);
                }
            }
        }
        return writer;
    }
}
