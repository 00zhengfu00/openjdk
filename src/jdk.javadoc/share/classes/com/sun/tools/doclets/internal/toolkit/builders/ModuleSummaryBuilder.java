/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.doclets.internal.toolkit.builders;

import java.io.IOException;
import java.util.Set;

import javax.tools.StandardLocation;

import com.sun.javadoc.*;
import com.sun.tools.doclets.internal.toolkit.*;
import com.sun.tools.doclets.internal.toolkit.util.*;

/**
 * Builds the summary for a given module.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Bhavesh Patel
 */
public class ModuleSummaryBuilder extends AbstractBuilder {
    /**
     * The root element of the module summary XML is {@value}.
     */
    public static final String ROOT = "ModuleDoc";

    /**
     * The module being documented.
     */
    private final String moduleName;

    /**
     * The doclet specific writer that will output the result.
     */
    private final ModuleSummaryWriter moduleWriter;

    /**
     * The content that will be added to the module summary documentation tree.
     */
    private Content contentTree;

    /**
     * The module package being documented.
     */
    private PackageDoc pkg;

    /**
     * Construct a new ModuleSummaryBuilder.
     *
     * @param context  the build context.
     * @param module the module being documented.
     * @param moduleWriter the doclet specific writer that will output the
     *        result.
     */
    private ModuleSummaryBuilder(Context context,
            String moduleName, ModuleSummaryWriter moduleWriter) {
        super(context);
        this.moduleName = moduleName;
        this.moduleWriter = moduleWriter;
    }

    /**
     * Construct a new ModuleSummaryBuilder.
     *
     * @param context  the build context.
     * @param module the module being documented.
     * @param moduleWriter the doclet specific writer that will output the
     *        result.
     *
     * @return an instance of a ModuleSummaryBuilder.
     */
    public static ModuleSummaryBuilder getInstance(Context context,
            String moduleName, ModuleSummaryWriter moduleWriter) {
        return new ModuleSummaryBuilder(context, moduleName, moduleWriter);
    }

    /**
     * Build the module summary.
     */
    public void build() throws IOException {
        if (moduleWriter == null) {
            //Doclet does not support this output.
            return;
        }
        build(layoutParser.parseXML(ROOT), contentTree);
    }

    /**
     * {@inheritDoc}
     */
    public String getName() {
        return ROOT;
    }

    /**
     * Build the module documentation.
     *
     * @param node the XML element that specifies which components to document
     * @param contentTree the content tree to which the documentation will be added
     */
    public void buildModuleDoc(XMLNode node, Content contentTree) throws Exception {
        contentTree = moduleWriter.getModuleHeader(moduleName);
        buildChildren(node, contentTree);
        moduleWriter.addModuleFooter(contentTree);
        moduleWriter.printDocument(contentTree);
        moduleWriter.close();
        // TEMPORARY:
        // The use of SOURCE_PATH on the next line is temporary. As we transition into the
        // modules world, this should migrate into using a location for the appropriate module
        // on the MODULE_SOURCE_PATH, or (in the old doclet) simply deleted.
        utils.copyDocFiles(configuration, StandardLocation.SOURCE_PATH, DocPaths.moduleSummary(moduleName));
    }

    /**
     * Build the content for the module doc.
     *
     * @param node the XML element that specifies which components to document
     * @param contentTree the content tree to which the module contents
     *                    will be added
     */
    public void buildContent(XMLNode node, Content contentTree) {
        Content moduleContentTree = moduleWriter.getContentHeader();
        buildChildren(node, moduleContentTree);
        moduleWriter.addModuleContent(contentTree, moduleContentTree);
    }

    /**
     * Build the module summary.
     *
     * @param node the XML element that specifies which components to document
     * @param moduleContentTree the module content tree to which the summaries will
     *                           be added
     */
    public void buildSummary(XMLNode node, Content moduleContentTree) {
        Content summaryContentTree = moduleWriter.getSummaryHeader();
        buildChildren(node, summaryContentTree);
        moduleContentTree.addContent(moduleWriter.getSummaryTree(summaryContentTree));
    }

    /**
     * Build the module package summary.
     *
     * @param node the XML element that specifies which components to document
     * @param summaryContentTree the content tree to which the summaries will
     *                           be added
     */
    public void buildPackageSummary(XMLNode node, Content summaryContentTree) {
        Set<PackageDoc> packages = configuration.modulePackages.get(moduleName);
        for (PackageDoc aPackage : packages) {
            this.pkg = aPackage;
            Content packageSummaryContentTree = moduleWriter.getPackageSummaryHeader(this.pkg);
            buildChildren(node, packageSummaryContentTree);
            summaryContentTree.addContent(moduleWriter.getPackageSummaryTree(
                    packageSummaryContentTree));
        }
    }

    /**
     * Build the summary for the interfaces in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the interface summary
     *                           will be added
     */
    public void buildInterfaceSummary(XMLNode node, Content packageSummaryContentTree) {
        String interfaceTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Interface_Summary"),
                configuration.getText("doclet.interfaces"));
        String[] interfaceTableHeader = new String[] {
            configuration.getText("doclet.Interface"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] interfaces = pkg.interfaces();
        if (interfaces.length > 0) {
            moduleWriter.addClassesSummary(
                    interfaces,
                    configuration.getText("doclet.Interface_Summary"),
                    interfaceTableSummary, interfaceTableHeader, packageSummaryContentTree);
        }
    }

    /**
     * Build the summary for the classes in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the class summary will
     *                           be added
     */
    public void buildClassSummary(XMLNode node, Content packageSummaryContentTree) {
        String classTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Class_Summary"),
                configuration.getText("doclet.classes"));
        String[] classTableHeader = new String[] {
            configuration.getText("doclet.Class"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] classes = pkg.ordinaryClasses();
        if (classes.length > 0) {
            moduleWriter.addClassesSummary(
                    classes,
                    configuration.getText("doclet.Class_Summary"),
                    classTableSummary, classTableHeader, packageSummaryContentTree);
        }
    }

    /**
     * Build the summary for the enums in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the enum summary will
     *                           be added
     */
    public void buildEnumSummary(XMLNode node, Content packageSummaryContentTree) {
        String enumTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Enum_Summary"),
                configuration.getText("doclet.enums"));
        String[] enumTableHeader = new String[] {
            configuration.getText("doclet.Enum"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] enums = pkg.enums();
        if (enums.length > 0) {
            moduleWriter.addClassesSummary(
                    enums,
                    configuration.getText("doclet.Enum_Summary"),
                    enumTableSummary, enumTableHeader, packageSummaryContentTree);
        }
    }

    /**
     * Build the summary for the exceptions in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the exception summary will
     *                           be added
     */
    public void buildExceptionSummary(XMLNode node, Content packageSummaryContentTree) {
        String exceptionTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Exception_Summary"),
                configuration.getText("doclet.exceptions"));
        String[] exceptionTableHeader = new String[] {
            configuration.getText("doclet.Exception"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] exceptions = pkg.exceptions();
        if (exceptions.length > 0) {
            moduleWriter.addClassesSummary(
                    exceptions,
                    configuration.getText("doclet.Exception_Summary"),
                    exceptionTableSummary, exceptionTableHeader, packageSummaryContentTree);
        }
    }

    /**
     * Build the summary for the errors in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the error summary will
     *                           be added
     */
    public void buildErrorSummary(XMLNode node, Content packageSummaryContentTree) {
        String errorTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Error_Summary"),
                configuration.getText("doclet.errors"));
        String[] errorTableHeader = new String[] {
            configuration.getText("doclet.Error"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] errors = pkg.errors();
        if (errors.length > 0) {
            moduleWriter.addClassesSummary(
                    errors,
                    configuration.getText("doclet.Error_Summary"),
                    errorTableSummary, errorTableHeader, packageSummaryContentTree);
        }
    }

    /**
     * Build the summary for the annotation type in the package.
     *
     * @param node the XML element that specifies which components to document
     * @param packageSummaryContentTree the tree to which the annotation type
     *                           summary will be added
     */
    public void buildAnnotationTypeSummary(XMLNode node, Content packageSummaryContentTree) {
        String annotationtypeTableSummary =
                configuration.getText("doclet.Member_Table_Summary",
                configuration.getText("doclet.Annotation_Types_Summary"),
                configuration.getText("doclet.annotationtypes"));
        String[] annotationtypeTableHeader = new String[] {
            configuration.getText("doclet.AnnotationType"),
            configuration.getText("doclet.Description")
        };
        ClassDoc[] annotationTypes = pkg.annotationTypes();
        if (annotationTypes.length > 0) {
            moduleWriter.addClassesSummary(
                    annotationTypes,
                    configuration.getText("doclet.Annotation_Types_Summary"),
                    annotationtypeTableSummary, annotationtypeTableHeader,
                    packageSummaryContentTree);
        }
    }
}
