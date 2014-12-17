/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

module java.xml.ws {
    requires java.activation;
    requires java.annotations.common;
    requires java.base;
    requires java.desktop;
    requires java.logging;
    requires java.management;
    requires java.rmi;
    requires public java.xml;
    requires public java.xml.bind;
    requires public java.xml.soap;
    requires jdk.httpserver;
    exports javax.jws;
    exports javax.jws.soap;
    exports javax.xml.ws;
    exports javax.xml.ws.handler;
    exports javax.xml.ws.handler.soap;
    exports javax.xml.ws.http;
    exports javax.xml.ws.soap;
    exports javax.xml.ws.spi;
    exports javax.xml.ws.spi.http;
    exports javax.xml.ws.wsaddressing;
    exports com.oracle.webservices.internal.api.databinding to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.addressing to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.addressing.v200408 to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.addressing to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.databinding to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.model to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.server to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.streaming to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.wsdl.parser to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.api.wsdl.writer to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.binding to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.db to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.model to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.policy.sourcemodel.wspolicy to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.spi.db to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.streaming to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.util to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.util.exception to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.util.xml to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.wsdl.parser to
        jdk.xml.ws;
    exports com.sun.xml.internal.ws.wsdl.writer to
        jdk.xml.ws;
    uses javax.xml.ws.spi.Provider;
}

