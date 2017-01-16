/*
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
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

package java.lang.module;

import java.io.InputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdk.internal.module.Checks.*;
import static java.util.Objects.*;

import jdk.internal.module.Checks;
import jdk.internal.module.ModuleInfo;


/**
 * A module descriptor.
 *
 * <p> A module descriptor describes a named module and defines methods to
 * obtain each of its components. The module descriptor for a named module
 * in the Java virtual machine is obtained by invoking the module's {@link
 * java.lang.reflect.Module#getDescriptor getDescriptor} method. Module
 * descriptors can also be created using the {@link ModuleDescriptor.Builder}
 * class or by reading the binary form of a module declaration
 * ({@code module-info.class}) using the {@link #read(InputStream,Supplier)
 * read} methods defined here. </p>
 *
 * <p> A module descriptor describes a <em>normal</em>, open, or automatic
 * module. <em>Normal</em> modules and open modules describe their dependences,
 * exported packages, the services that they use or provide, and other
 * components. <em>Normal</em> modules may open specific packages. A module
 * in the Java virtual machine with an open package allows other modules to
 * access all types in the package and all their members (not just the
 * public types and their public members) using reflective APIs that
 * support private access or suppress default Java language access control
 * checks. Open modules do not declare any open packages (the {@link #opens()
 * opens} method returns an empty set) but the resulting module is treated by
 * the Java virtual machine as if all packages are open. </p>
 *
 * <p> An automatic module is defined implicitly rather than explicitly and
 * therefore does not have a module declaration. JAR files located on the
 * module path, or by the {@link ModuleFinder} returned by {@link
 * ModuleFinder#of(java.nio.file.Path[]) ModuleFinder.of}, are created as
 * automatic modules if the JAR file does not contain a {@code
 * module-info.class} file. Automatic modules export and open all packages
 * and receive <a href="Configuration.html#automaticmoduleresolution">special
 * treatment</a> during resolution so that they read all other modules in
 * the configuration. In addition, when an automatic module is instantiated
 * in the Java virtual machine then the {@link java.lang.reflect.Module} reads
 * every unnamed {@code Module} in the Java virtual machine. </p>
 *
 * <p> {@code ModuleDescriptor} objects are immutable and safe for use by
 * multiple concurrent threads.</p>
 *
 * @since 9
 */

public class ModuleDescriptor
    implements Comparable<ModuleDescriptor>
{

    /**
     * A modifier on a module.
     *
     * @see ModuleDescriptor#modifiers()
     * @since 9
     */
    public static enum Modifier {
        /**
         * An open module. An open module does not declare any open packages
         * but the resulting module is treated as if all packages are open.
         */
        OPEN,

        /**
         * An automatic module. An automatic module exports and opens all
         * packages.
         *
         * @apiNote This modifier does not correspond to a module flag in the
         * binary form of a module declaration ({@code module-info.class}).
         */
        AUTOMATIC,

        /**
         * The module was not explicitly or implicitly declared.
         */
        SYNTHETIC,

        /**
         * The module was implicitly declared.
         */
        MANDATED;
    }


    /**
     * <p> A dependence upon a module </p>
     *
     * @see ModuleDescriptor#requires()
     * @since 9
     */

    public final static class Requires
        implements Comparable<Requires>
    {

        /**
         * A modifier on a module dependence.
         *
         * @see Requires#modifiers()
         * @since 9
         */
        public static enum Modifier {

            /**
             * The dependence causes any module which depends on the <i>current
             * module</i> to have an implicitly declared dependence on the module
             * named by the {@code Requires}.
             */
            TRANSITIVE,

            /**
             * The dependence is mandatory in the static phase, during compilation,
             * but is optional in the dynamic phase, during execution.
             */
            STATIC,

            /**
             * The dependence was not explicitly or implicitly declared in the
             * source of the module declaration.
             */
            SYNTHETIC,

            /**
             * The dependence was implicitly declared in the source of the module
             * declaration.
             */
            MANDATED;

        }

        private final Set<Modifier> mods;
        private final String name;
        private final Version compiledVersion;

        private Requires(Set<Modifier> ms, String mn, Version v) {
            if (ms.isEmpty()) {
                ms = Collections.emptySet();
            } else {
                ms = Collections.unmodifiableSet(EnumSet.copyOf(ms));
            }
            this.mods = ms;
            this.name = mn;
            this.compiledVersion = v;
        }

        private Requires(Set<Modifier> ms, String mn, Version v, boolean unused) {
            this.mods = ms;
            this.name = mn;
            this.compiledVersion = v;
        }

        /**
         * Returns the set of modifiers.
         *
         * @return A possibly-empty unmodifiable set of modifiers
         */
        public Set<Modifier> modifiers() {
            return mods;
        }

        /**
         * Return the module name.
         *
         * @return The module name
         */
        public String name() {
            return name;
        }

        /**
         * Returns the version of the module if recorded at compile-time.
         *
         * @return The version of the module if recorded at compile-time
         */
        public Optional<Version> compiledVersion() {
            return Optional.ofNullable(compiledVersion);
        }

        /**
         * Compares this module dependence to another.
         *
         * <p> Two {@code Requires} objects are compared by comparing their
         * module name lexicographically.  Where the module names are equal then
         * the sets of modifiers are compared based on a value computed from the
         * ordinal of each modifier. Where the module names are equal and the
         * set of modifiers are equal then the version of the modules recorded
         * at compile-time are compared. When comparing the versions recorded
         * at compile-time then a dependence that has a recorded version is
         * considered to succeed a dependence that does not have a recorded
         * version. </p>
         *
         * @return A negative integer, zero, or a positive integer if this module
         *         dependence is less than, equal to, or greater than the given
         *         module dependence
         */
        @Override
        public int compareTo(Requires that) {
            int c = this.name().compareTo(that.name());
            if (c != 0)
                return c;

            // modifiers
            c = Long.compare(this.modsValue(), that.modsValue());
            if (c != 0)
                return c;

            // compiledVersion
            if (this.compiledVersion != null) {
                if (that.compiledVersion != null)
                    c = this.compiledVersion.compareTo(that.compiledVersion);
                else
                    c = 1;
            } else {
                if (that.compiledVersion != null)
                    c = -1;
            }

            return c;
        }

        /**
         * Return a value for the modifiers to allow sets of modifiers to be
         * compared.
         */
        private long modsValue() {
            long value = 0;
            for (Modifier m : mods) {
                value += 1 << m.ordinal();
            }
            return value;
        }

        /**
         * Tests this module dependence for equality with the given object.
         *
         * <p> If the given object is not a {@code Requires} then this method
         * returns {@code false}. Two module dependence objects are equal if
         * the module names are equal, set of modifiers are equal, and the
         * compiled version of both modules is equal or not recorded for
         * both modules. </p>
         *
         * <p> This method satisfies the general contract of the {@link
         * java.lang.Object#equals(Object) Object.equals} method. </p>
         *
         * @param   ob
         *          the object to which this object is to be compared
         *
         * @return  {@code true} if, and only if, the given object is a module
         *          dependence that is equal to this module dependence
         */
        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Requires))
                return false;
            Requires that = (Requires)ob;
            return name.equals(that.name) && mods.equals(that.mods)
                    && Objects.equals(compiledVersion, that.compiledVersion);
        }

        /**
         * Computes a hash code for this module dependence.
         *
         * <p> The hash code is based upon the module name, modifiers, and the
         * module version if recorded at compile time. It satisfies the general
         * contract of the {@link Object#hashCode Object.hashCode} method. </p>
         *
         * @return The hash-code value for this module dependence
         */
        @Override
        public int hashCode() {
            int hash = name.hashCode() * 43 + mods.hashCode();
            if (compiledVersion != null)
                hash = hash * 43 + compiledVersion.hashCode();
            return hash;
        }

        /**
         * Returns a string describing module dependence.
         *
         * @return A string describing module dependence
         */
        @Override
        public String toString() {
            String what;
            if (compiledVersion != null) {
                what = name() + " (@" + compiledVersion + ")";
            } else {
                what = name();
            }
            return ModuleDescriptor.toString(mods, what);
        }
    }



    /**
     * <p> A module export, may be qualified or unqualified. </p>
     *
     * @see ModuleDescriptor#exports()
     * @since 9
     */

    public final static class Exports {

        /**
         * A modifier on a module export.
         *
         * @see Exports#modifiers()
         * @since 9
         */
        public static enum Modifier {

            /**
             * The export was not explicitly or implicitly declared in the
             * source of the module declaration.
             */
            SYNTHETIC,

            /**
             * The export was implicitly declared in the source of the module
             * declaration.
             */
            MANDATED;

        }

        private final Set<Modifier> mods;
        private final String source;
        private final Set<String> targets;  // empty if unqualified export

        /**
         * Constructs an export
         */
        private Exports(Set<Modifier> ms, String source, Set<String> targets) {
            if (ms.isEmpty()) {
                ms = Collections.emptySet();
            } else {
                ms = Collections.unmodifiableSet(EnumSet.copyOf(ms));
            }
            this.mods = ms;
            this.source = source;
            this.targets = emptyOrUnmodifiableSet(targets);
        }

        private Exports(Set<Modifier> ms,
                        String source,
                        Set<String> targets,
                        boolean unused) {
            this.mods = ms;
            this.source = source;
            this.targets = targets;
        }

        /**
         * Returns the set of modifiers.
         *
         * @return A possibly-empty unmodifiable set of modifiers
         */
        public Set<Modifier> modifiers() {
            return mods;
        }

        /**
         * Returns {@code true} if this is a qualified export.
         *
         * @return {@code true} if this is a qualified export
         */
        public boolean isQualified() {
            return !targets.isEmpty();
        }

        /**
         * Returns the package name.
         *
         * @return The package name
         */
        public String source() {
            return source;
        }

        /**
         * For a qualified export, returns the non-empty and immutable set
         * of the module names to which the package is exported. For an
         * unqualified export, returns an empty set.
         *
         * @return The set of target module names or for an unqualified
         *         export, an empty set
         */
        public Set<String> targets() {
            return targets;
        }

        /**
         * Computes a hash code for this module export.
         *
         * <p> The hash code is based upon the modifiers, the package name,
         * and for a qualified export, the set of modules names to which the
         * package is exported. It satisfies the general contract of the
         * {@link Object#hashCode Object.hashCode} method.
         *
         * @return The hash-code value for this module export
         */
        @Override
        public int hashCode() {
            int hash = mods.hashCode();
            hash = hash * 43 + source.hashCode();
            return hash * 43 + targets.hashCode();
        }

        /**
         * Tests this module export for equality with the given object.
         *
         * <p> If the given object is not an {@code Exports} then this method
         * returns {@code false}. Two module exports objects are equal if their
         * set of modifiers is equal, the package names are equal and the set
         * of target module names is equal. </p>
         *
         * <p> This method satisfies the general contract of the {@link
         * java.lang.Object#equals(Object) Object.equals} method. </p>
         *
         * @param   ob
         *          the object to which this object is to be compared
         *
         * @return  {@code true} if, and only if, the given object is a module
         *          dependence that is equal to this module dependence
         */
        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Exports))
                return false;
            Exports other = (Exports)ob;
            return Objects.equals(this.mods, other.mods)
                    && Objects.equals(this.source, other.source)
                    && Objects.equals(this.targets, other.targets);
        }

        /**
         * Returns a string describing module export.
         *
         * @return A string describing module export
         */
        @Override
        public String toString() {
            String s = ModuleDescriptor.toString(mods, source);
            if (targets.isEmpty())
                return s;
            else
                return s + " to " + targets;
        }
    }


    /**
     * <p> Represents a module <em>opens</em> directive, may be qualified or
     * unqualified. </p>
     *
     * <p> The <em>opens</em> directive in a module declaration declares a
     * package to be open to allow all types in the package, and all their
     * members, not just public types and their public members to be reflected
     * on by APIs that support private access or a way to bypass or suppress
     * default Java language access control checks. </p>
     *
     * @see ModuleDescriptor#opens()
     * @since 9
     */

    public final static class Opens {

        /**
         * A modifier on a module <em>opens</em> directive.
         *
         * @see Opens#modifiers()
         * @since 9
         */
        public static enum Modifier {

            /**
             * The opens was not explicitly or implicitly declared in the
             * source of the module declaration.
             */
            SYNTHETIC,

            /**
             * The opens was implicitly declared in the source of the module
             * declaration.
             */
            MANDATED;

        }

        private final Set<Modifier> mods;
        private final String source;
        private final Set<String> targets;  // empty if unqualified export

        /**
         * Constructs an Opens
         */
        private Opens(Set<Modifier> ms, String source, Set<String> targets) {
            if (ms.isEmpty()) {
                ms = Collections.emptySet();
            } else {
                ms = Collections.unmodifiableSet(EnumSet.copyOf(ms));
            }
            this.mods = ms;
            this.source = source;
            this.targets = emptyOrUnmodifiableSet(targets);
        }

        private Opens(Set<Modifier> ms,
                      String source,
                      Set<String> targets,
                      boolean unused) {
            this.mods = ms;
            this.source = source;
            this.targets = targets;
        }

        /**
         * Returns the set of modifiers.
         *
         * @return A possibly-empty unmodifiable set of modifiers
         */
        public Set<Modifier> modifiers() {
            return mods;
        }

        /**
         * Returns {@code true} if this is a qualified opens.
         *
         * @return {@code true} if this is a qualified opens
         */
        public boolean isQualified() {
            return !targets.isEmpty();
        }

        /**
         * Returns the package name.
         *
         * @return The package name
         */
        public String source() {
            return source;
        }

        /**
         * For a qualified opens, returns the non-empty and immutable set
         * of the module names to which the package is open. For an
         * unqualified opens, returns an empty set.
         *
         * @return The set of target module names or for an unqualified
         *         opens, an empty set
         */
        public Set<String> targets() {
            return targets;
        }

        /**
         * Computes a hash code for this module opens.
         *
         * <p> The hash code is based upon the modifiers, the package name,
         * and for a qualified opens, the set of modules names to which the
         * package is opened. It satisfies the general contract of the
         * {@link Object#hashCode Object.hashCode} method.
         *
         * @return The hash-code value for this module opens
         */
        @Override
        public int hashCode() {
            int hash = mods.hashCode();
            hash = hash * 43 + source.hashCode();
            return hash * 43 + targets.hashCode();
        }

        /**
         * Tests this module opens for equality with the given object.
         *
         * <p> If the given object is not an {@code Opens} then this method
         * returns {@code false}. Two {@code Opens} objects are equal if their
         * set of modifiers is equal, the package names are equal and the set
         * of target module names is equal. </p>
         *
         * <p> This method satisfies the general contract of the {@link
         * java.lang.Object#equals(Object) Object.equals} method. </p>
         *
         * @param   ob
         *          the object to which this object is to be compared
         *
         * @return  {@code true} if, and only if, the given object is a module
         *          dependence that is equal to this module dependence
         */
        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Opens))
                return false;
            Opens other = (Opens)ob;
            return Objects.equals(this.mods, other.mods)
                    && Objects.equals(this.source, other.source)
                    && Objects.equals(this.targets, other.targets);
        }

        /**
         * Returns a string describing module opens.
         *
         * @return A string describing module opens
         */
        @Override
        public String toString() {
            String s = ModuleDescriptor.toString(mods, source);
            if (targets.isEmpty())
                return s;
            else
                return s + " to " + targets;
        }
    }


    /**
     * <p> A service that a module provides one or more implementations of. </p>
     *
     * @see ModuleDescriptor#provides()
     * @since 9
     */

    public final static class Provides {

        private final String service;
        private final List<String> providers;

        private Provides(String service, List<String> providers) {
            this.service = service;
            this.providers = Collections.unmodifiableList(providers);
        }

        private Provides(String service, List<String> providers, boolean unused) {
            this.service = service;
            this.providers = providers;
        }

        /**
         * Returns the fully qualified class name of the service type.
         *
         * @return The fully qualified class name of the service type.
         */
        public String service() { return service; }

        /**
         * Returns the list of the fully qualified class names of the providers
         * or provider factories.
         *
         * @return A non-empty and unmodifiable list of the fully qualified class
         *         names of the providers or provider factories
         */
        public List<String> providers() { return providers; }

        /**
         * Computes a hash code for this provides.
         *
         * <p> The hash code is based upon the service type and the set of
         * providers. It satisfies the general contract of the {@link
         * Object#hashCode Object.hashCode} method. </p>
         *
         * @return The hash-code value for this module provides
         */
        @Override
        public int hashCode() {
            return service.hashCode() * 43 + providers.hashCode();
        }

        /**
         * Tests this provides for equality with the given object.
         *
         * <p> If the given object is not a {@code Provides} then this method
         * returns {@code false}. Two {@code Provides} objects are equal if the
         * service type is equal and the list of providers is equal. </p>
         *
         * <p> This method satisfies the general contract of the {@link
         * java.lang.Object#equals(Object) Object.equals} method. </p>
         *
         * @param   ob
         *          the object to which this object is to be compared
         *
         * @return  {@code true} if, and only if, the given object is a
         *          {@code Provides} that is equal to this {@code Provides}
         */
        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Provides))
                return false;
            Provides other = (Provides)ob;
            return Objects.equals(this.service, other.service) &&
                    Objects.equals(this.providers, other.providers);
        }

        /**
         * Returns a string describing this provides.
         *
         * @return A string describing this provides
         */
        @Override
        public String toString() {
            return service + " with " + providers;
        }

    }



    /**
     * A module's version string.
     *
     * <p> A version string has three components: The version number itself, an
     * optional pre-release version, and an optional build version.  Each
     * component is a sequence of tokens; each token is either a non-negative
     * integer or a string.  Tokens are separated by the punctuation characters
     * {@code '.'}, {@code '-'}, or {@code '+'}, or by transitions from a
     * sequence of digits to a sequence of characters that are neither digits
     * nor punctuation characters, or vice versa.
     *
     * <ul>
     *
     *   <li> The <i>version number</i> is a sequence of tokens separated by
     *   {@code '.'} characters, terminated by the first {@code '-'} or {@code
     *   '+'} character. </li>
     *
     *   <li> The <i>pre-release version</i> is a sequence of tokens separated
     *   by {@code '.'} or {@code '-'} characters, terminated by the first
     *   {@code '+'} character. </li>
     *
     *   <li> The <i>build version</i> is a sequence of tokens separated by
     *   {@code '.'}, {@code '-'}, or {@code '+'} characters.
     *
     * </ul>
     *
     * <p> When comparing two version strings, the elements of their
     * corresponding components are compared in pointwise fashion.  If one
     * component is longer than the other, but otherwise equal to it, then the
     * first component is considered the greater of the two; otherwise, if two
     * corresponding elements are integers then they are compared as such;
     * otherwise, at least one of the elements is a string, so the other is
     * converted into a string if it is an integer and the two are compared
     * lexicographically.  Trailing integer elements with the value zero are
     * ignored.
     *
     * <p> Given two version strings, if their version numbers differ then the
     * result of comparing them is the result of comparing their version
     * numbers; otherwise, if one of them has a pre-release version but the
     * other does not then the first is considered to precede the second,
     * otherwise the result of comparing them is the result of comparing their
     * pre-release versions; otherwise, the result of comparing them is the
     * result of comparing their build versions.
     *
     * @see ModuleDescriptor#version()
     * @since 9
     */

    public final static class Version
        implements Comparable<Version>
    {

        private final String version;

        // If Java had disjunctive types then we'd write List<Integer|String> here
        //
        private final List<Object> sequence;
        private final List<Object> pre;
        private final List<Object> build;

        // Take a numeric token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is (decimal) numeric
        //
        private static int takeNumber(String s, int i, List<Object> acc) {
            char c = s.charAt(i);
            int d = (c - '0');
            int n = s.length();
            while (++i < n) {
                c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    d = d * 10 + (c - '0');
                    continue;
                }
                break;
            }
            acc.add(d);
            return i;
        }

        // Take a string token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is not '.'
        //
        private static int takeString(String s, int i, List<Object> acc) {
            int b = i;
            int n = s.length();
            while (++i < n) {
                char c = s.charAt(i);
                if (c != '.' && c != '-' && c != '+' && !(c >= '0' && c <= '9'))
                    continue;
                break;
            }
            acc.add(s.substring(b, i));
            return i;
        }

        // Syntax: tok+ ( '-' tok+)? ( '+' tok+)?
        // First token string is sequence, second is pre, third is build
        // Tokens are separated by '.' or '-', or by changes between alpha & numeric
        // Numeric tokens are compared as decimal integers
        // Non-numeric tokens are compared lexicographically
        // A version with a non-empty pre is less than a version with same seq but no pre
        // Tokens in build may contain '-' and '+'
        //
        private Version(String v) {

            if (v == null)
                throw new IllegalArgumentException("Null version string");
            int n = v.length();
            if (n == 0)
                throw new IllegalArgumentException("Empty version string");

            int i = 0;
            char c = v.charAt(i);
            if (!(c >= '0' && c <= '9'))
                throw new IllegalArgumentException(v
                                                   + ": Version string does not start"
                                                   + " with a number");

            List<Object> sequence = new ArrayList<>(4);
            List<Object> pre = new ArrayList<>(2);
            List<Object> build = new ArrayList<>(2);

            i = takeNumber(v, i, sequence);

            while (i < n) {
                c = v.charAt(i);
                if (c == '.') {
                    i++;
                    continue;
                }
                if (c == '-' || c == '+') {
                    i++;
                    break;
                }
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, sequence);
                else
                    i = takeString(v, i, sequence);
            }

            if (c == '-' && i >= n)
                throw new IllegalArgumentException(v + ": Empty pre-release");

            while (i < n) {
                c = v.charAt(i);
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, pre);
                else
                    i = takeString(v, i, pre);
                if (i >= n)
                    break;
                c = v.charAt(i);
                if (c == '.' || c == '-') {
                    i++;
                    continue;
                }
                if (c == '+') {
                    i++;
                    break;
                }
            }

            if (c == '+' && i >= n)
                throw new IllegalArgumentException(v + ": Empty pre-release");

            while (i < n) {
                c = v.charAt(i);
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, build);
                else
                    i = takeString(v, i, build);
                if (i >= n)
                    break;
                c = v.charAt(i);
                if (c == '.' || c == '-' || c == '+') {
                    i++;
                    continue;
                }
            }

            this.version = v;
            this.sequence = sequence;
            this.pre = pre;
            this.build = build;
        }

        /**
         * Parses the given string as a version string.
         *
         * @param  v
         *         The string to parse
         *
         * @return The resulting {@code Version}
         *
         * @throws IllegalArgumentException
         *         If {@code v} is {@code null}, an empty string, or cannot be
         *         parsed as a version string
         */
        public static Version parse(String v) {
            return new Version(v);
        }

        @SuppressWarnings("unchecked")
        private int cmp(Object o1, Object o2) {
            return ((Comparable)o1).compareTo(o2);
        }

        private int compareTokens(List<Object> ts1, List<Object> ts2) {
            int n = Math.min(ts1.size(), ts2.size());
            for (int i = 0; i < n; i++) {
                Object o1 = ts1.get(i);
                Object o2 = ts2.get(i);
                if ((o1 instanceof Integer && o2 instanceof Integer)
                    || (o1 instanceof String && o2 instanceof String))
                {
                    int c = cmp(o1, o2);
                    if (c == 0)
                        continue;
                    return c;
                }
                // Types differ, so convert number to string form
                int c = o1.toString().compareTo(o2.toString());
                if (c == 0)
                    continue;
                return c;
            }
            List<Object> rest = ts1.size() > ts2.size() ? ts1 : ts2;
            int e = rest.size();
            for (int i = n; i < e; i++) {
                Object o = rest.get(i);
                if (o instanceof Integer && ((Integer)o) == 0)
                    continue;
                return ts1.size() - ts2.size();
            }
            return 0;
        }

        /**
         * Compares this module version to another module version. Module
         * versions are compared as described in the class description.
         *
         * @param that
         *        The module version to compare
         *
         * @return A negative integer, zero, or a positive integer as this
         *         module version is less than, equal to, or greater than the
         *         given module version
         */
        @Override
        public int compareTo(Version that) {
            int c = compareTokens(this.sequence, that.sequence);
            if (c != 0) return c;
            if (this.pre.isEmpty()) {
                if (!that.pre.isEmpty()) return +1;
            } else {
                if (that.pre.isEmpty()) return -1;
            }
            c = compareTokens(this.pre, that.pre);
            if (c != 0) return c;
            return compareTokens(this.build, that.build);
        }

        /**
         * Tests this module version for equality with the given object.
         *
         * <p> If the given object is not a {@code Version} then this method
         * returns {@code false}. Two module version are equal if their
         * corresponding components are equal. </p>
         *
         * <p> This method satisfies the general contract of the {@link
         * java.lang.Object#equals(Object) Object.equals} method. </p>
         *
         * @param   ob
         *          the object to which this object is to be compared
         *
         * @return  {@code true} if, and only if, the given object is a module
         *          reference that is equal to this module reference
         */
        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Version))
                return false;
            return compareTo((Version)ob) == 0;
        }

        /**
         * Computes a hash code for this module version.
         *
         * <p> The hash code is based upon the components of the version and
         * satisfies the general contract of the {@link Object#hashCode
         * Object.hashCode} method. </p>
         *
         * @return The hash-code value for this module version
         */
        @Override
        public int hashCode() {
            return version.hashCode();
        }

        /**
         * Returns the string from which this version was parsed.
         *
         * @return The string from which this version was parsed.
         */
        @Override
        public String toString() {
            return version;
        }

    }


    private final String name;
    private final Version version;
    private final Set<Modifier> modifiers;
    private final boolean open;  // true if modifiers contains OPEN
    private final Set<Requires> requires;
    private final Set<Exports> exports;
    private final Set<Opens> opens;
    private final Set<String> uses;
    private final Set<Provides> provides;
    private final Set<String> packages;
    private final String mainClass;
    private final String osName;
    private final String osArch;
    private final String osVersion;

    private ModuleDescriptor(String name,
                             Version version,
                             Set<Modifier> modifiers,
                             Set<Requires> requires,
                             Set<Exports> exports,
                             Set<Opens> opens,
                             Set<String> uses,
                             Set<Provides> provides,
                             Set<String> packages,
                             String mainClass,
                             String osName,
                             String osArch,
                             String osVersion)
    {
        this.name = name;
        this.version = version;
        this.modifiers = emptyOrUnmodifiableSet(modifiers);
        this.open = modifiers.contains(Modifier.OPEN);
        assert (requires.stream().map(Requires::name).distinct().count()
                == requires.size());
        this.requires = emptyOrUnmodifiableSet(requires);
        this.exports = emptyOrUnmodifiableSet(exports);
        this.opens = emptyOrUnmodifiableSet(opens);
        this.uses = emptyOrUnmodifiableSet(uses);
        this.provides = emptyOrUnmodifiableSet(provides);

        this.packages = emptyOrUnmodifiableSet(packages);
        this.mainClass = mainClass;
        this.osName = osName;
        this.osArch = osArch;
        this.osVersion = osVersion;
    }

    /**
     * Creates a module descriptor from its components.
     * The arguments are pre-validated and sets are unmodifiable sets.
     */
    ModuleDescriptor(String name,
                     Version version,
                     Set<Modifier> modifiers,
                     Set<Requires> requires,
                     Set<Exports> exports,
                     Set<Opens> opens,
                     Set<String> uses,
                     Set<Provides> provides,
                     Set<String> packages,
                     String mainClass,
                     String osName,
                     String osArch,
                     String osVersion,
                     int hashCode,
                     boolean unused) {
        this.name = name;
        this.version = version;
        this.modifiers = modifiers;
        this.open = modifiers.contains(Modifier.OPEN);
        this.requires = requires;
        this.exports = exports;
        this.opens = opens;
        this.uses = uses;
        this.provides = provides;
        this.packages = packages;
        this.mainClass = mainClass;
        this.osName = osName;
        this.osArch = osArch;
        this.osVersion = osVersion;
        this.hash = hashCode;
    }

    /**
     * <p> The module name. </p>
     *
     * @return The module name
     */
    public String name() {
        return name;
    }

    /**
     * <p> The set of module modifiers. </p>
     *
     * @return A possibly-empty unmodifiable set of modifiers
     */
    public Set<Modifier> modifiers() {
        return modifiers;
    }

    /**
     * <p> Returns {@code true} if this is an open module. </p>
     *
     * <p> This method is equivalent to testing if the set of {@link #modifiers
     * modifiers} contains the {@link Modifier#OPEN OPEN} modifier. </p>
     *
     * @return  {@code true} if this is an open module
     */
    public boolean isOpen() {
        return open;
    }

    /**
     * <p> Returns {@code true} if this is an automatic module. </p>
     *
     * <p> This method is equivalent to testing if the set of {@link #modifiers
     * modifiers} contains the {@link Modifier#OPEN AUTOMATIC} modifier. </p>
     *
     * @return  {@code true} if this is an automatic module
     */
    public boolean isAutomatic() {
        return modifiers.contains(Modifier.AUTOMATIC);
    }

    /**
     * <p> The dependences of this module. </p>
     *
     * <p> The set includes a dependency on "{@code java.base}" when this
     * module is not named "{@code java.base}". </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link Requires} objects
     */
    public Set<Requires> requires() {
        return requires;
    }

    /**
     * <p> The module exports. </p>
     *
     * <p> If this module is an automatic module then the set contains an export
     * for every package in the module. </p>
     *
     * @return  A possibly-empty unmodifiable set of exported packages
     */
    public Set<Exports> exports() {
        return exports;
    }

    /**
     * <p> The module <em>opens</em> directives. </p>
     *
     * <p> This method returns an empty set when invoked on {@link #isOpen()
     * open} module. If this module is an automatic module then the set
     * contains an {@code Opens} object for every package in the module. </p>
     *
     * @return  A possibly-empty unmodifiable set of open packages
     */
    public Set<Opens> opens() {
        return opens;
    }

    /**
     * <p> The service dependences of this module. </p>
     *
     * <p> If this module is an automatic module then the set of service
     * dependences is empty. </p>
     *
     * @return  A possibly-empty unmodifiable set of the fully qualified class
     *          names of the service types used
     */
    public Set<String> uses() {
        return uses;
    }

    /**
     * <p> The services that this module provides. </p>
     *
     * @return The possibly-empty unmodifiable set of the services that this
     *         module provides
     */
    public Set<Provides> provides() {
        return provides;
    }

    /**
     * Returns this module's version.
     *
     * @return This module's version
     */
    public Optional<Version> version() {
        return Optional.ofNullable(version);
    }

    /**
     * Returns a string containing this module's name and, if present, its
     * version.
     *
     * @return A string containing this module's name and, if present, its
     *         version.
     */
    public String toNameAndVersion() {
        if (version != null) {
            return name() + "@" + version;
        } else {
            return name();
        }
    }

    /**
     * Returns the module's main class.
     *
     * @return The fully qualified class name of this module's main class
     */
    public Optional<String> mainClass() {
        return Optional.ofNullable(mainClass);
    }

    /**
     * Returns the operating system name if this module is operating system
     * specific.
     *
     * @return The operating system name or an empty {@code Optional}
     *         if this module is not operating system specific
     */
    public Optional<String> osName() {
        return Optional.ofNullable(osName);
    }

    /**
     * Returns the operating system architecture if this module is operating
     * system architecture specific.
     *
     * @return The operating system architecture or an empty {@code Optional}
     *         if this module is not operating system architecture specific
     */
    public Optional<String> osArch() {
        return Optional.ofNullable(osArch);
    }

    /**
     * Returns the operating system version if this module is operating
     * system version specific.
     *
     * @return The operating system version or an empty {@code Optional}
     *         if this module is not operating system version specific
     */
    public Optional<String> osVersion() {
        return Optional.ofNullable(osVersion);
    }

    /**
     * Returns the names of all packages in this module.
     *
     * @return A possibly-empty unmodifiable set of all packages in the module
     */
    public Set<String> packages() {
        return packages;
    }


    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     *
     * <p> {@code ModuleDescriptor} defines the {@link #newModule newModule},
     * {@link #newOpenModule newOpenModule}, and {@link #newAutomaticModule
     * newAutomaticModule} methods to create builders for building
     * <em>normal</em>, open, and automatic modules. </p>
     *
     * <p> The set of packages in the module are accumulated by the {@code
     * Builder} as the {@link ModuleDescriptor.Builder#exports(String) exports},
     * {@link ModuleDescriptor.Builder#opens(String) opens},
     * {@link ModuleDescriptor.Builder#packages(Set) packages},
     * {@link ModuleDescriptor.Builder#provides(String,List) provides}, and
     * {@link ModuleDescriptor.Builder#mainClass(String) mainClass} methods are
     * invoked. </p>
     *
     * <p> The module names, package names, and class names that are parameters
     * specified to the builder methods are the module names, package names,
     * and qualified names of classes as defined in the <cite>The Java&trade;
     * Language Specification</cite>. </p>
     *
     * <p> Example usage: </p>
     * <pre>{@code    ModuleDescriptor descriptor = ModuleDescriptor.newModule("stats.core")
     *         .requires("java.base")
     *         .exports("org.acme.stats.core.clustering")
     *         .exports("org.acme.stats.core.regression")
     *         .packages(Set.of("org.acme.stats.core.internal"))
     *         .build();
     * }</pre>
     *
     * @apiNote A {@code Builder} checks the components and invariants as
     * components are added to the builder. The rationale for this is to detect
     * errors as early as possible and not defer all validation to the
     * {@link #build build} method.
     * A {@code Builder} cannot be used to create a module with the
     * {@link ModuleDescriptor.Modifier#MANDATED MANDATED} or
     * {@link ModuleDescriptor.Modifier#SYNTHETIC SYNTHETIC} modifiers.
     *
     * @since 9
     */
    public static final class Builder {
        final String name;
        final boolean strict;
        final Set<Modifier> modifiers;
        final boolean open;
        final boolean automatic;
        final Set<String> packages = new HashSet<>();
        final Map<String, Requires> requires = new HashMap<>();
        final Map<String, Exports> exports = new HashMap<>();
        final Map<String, Opens> opens = new HashMap<>();
        final Set<String> uses = new HashSet<>();
        final Map<String, Provides> provides = new HashMap<>();
        Version version;
        String osName;
        String osArch;
        String osVersion;
        String mainClass;

        /**
         * Initializes a new builder with the given module name.
         *
         * If {@code strict} is {@code true} then module, package, and class
         * names are checked to ensure they are legal names. In addition, the
         * {@link #build buid} method will add "{@code requires java.base}" if
         * the dependency is not declared.
         */
        Builder(String name, boolean strict, Set<Modifier> modifiers) {
            this.name = (strict) ? requireModuleName(name) : name;
            this.strict = strict;
            this.modifiers = modifiers;
            this.open = modifiers.contains(Modifier.OPEN);
            this.automatic = modifiers.contains(Modifier.AUTOMATIC);
            assert !open || !automatic;
        }

        /**
         * Returns a snapshot of the packages in the module.
         */
        /* package */ Set<String> packages() {
            return Collections.unmodifiableSet(packages);
        }

        /**
         * Adds a dependence on a module.
         *
         * @param  req
         *         The dependence
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the dependence is on the module that this builder was
         *         initialized to build
         * @throws IllegalStateException
         *         If the dependence on the module has already been declared
         *         or this builder is for an automatic module
         */
        public Builder requires(Requires req) {
            if (automatic)
                throw new IllegalStateException("Automatic modules cannot declare"
                                                + " dependences");
            String mn = req.name();
            if (name.equals(mn))
                throw new IllegalArgumentException("Dependence on self");
            if (requires.containsKey(mn))
                throw new IllegalStateException("Dependence upon " + mn
                                                + " already declared");
            requires.put(mn, req);
            return this;
        }

        /**
         * Adds a dependence on a module with the given (and possibly empty)
         * set of modifiers. The dependence includes the version of the
         * module that that was recorded at compile-time.
         *
         * @param  ms
         *         The set of modifiers
         * @param  mn
         *         The module name
         * @param  compiledVersion
         *         The version of the module recorded at compile-time
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal module
         *         name, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If the dependence on the module has already been declared
         *         or this builder is for an automatic module
         */
        public Builder requires(Set<Requires.Modifier> ms,
                                String mn,
                                Version compiledVersion) {
            Objects.requireNonNull(compiledVersion);
            if (strict)
                mn = requireModuleName(mn);
            return requires(new Requires(ms, mn, compiledVersion));
        }

        /**
         * Adds a dependence on a module with the given (and possibly empty)
         * set of modifiers.
         *
         * @param  ms
         *         The set of modifiers
         * @param  mn
         *         The module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal module
         *         name, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If the dependence on the module has already been declared
         *         or this builder is for an automatic module
         */
        public Builder requires(Set<Requires.Modifier> ms, String mn) {
            if (strict)
                mn = requireModuleName(mn);
            return requires(new Requires(ms, mn, null));
        }

        /**
         * Adds a dependence on a module with an empty set of modifiers.
         *
         * @param  mn
         *         The module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal module
         *         name, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If the dependence on the module has already been declared
         *         or this builder is for an automatic module
         */
        public Builder requires(String mn) {
            return requires(EnumSet.noneOf(Requires.Modifier.class), mn);
        }

        /**
         * Adds an exported package.
         *
         * @param  e
         *         The export
         *
         * @return This builder
         *
         * @throws IllegalStateException
         *         If the {@link Exports#source package} is already declared as
         *         exported or this builder is for an automatic module
         */
        public Builder exports(Exports e) {
            if (automatic) {
                throw new IllegalStateException("Automatic modules cannot declare"
                                                 + " exported packages");
            }
            String source = e.source();
            if (exports.containsKey(source)) {
                throw new IllegalStateException("Exported package " + source
                                                 + " already declared");
            }
            exports.put(source, e);
            packages.add(source);
            return this;
        }

        /**
         * Adds an exported package with the given (and possibly empty) set of
         * modifiers. The package is exported to a set of target modules.
         *
         * @param  ms
         *         The set of modifiers
         * @param  pn
         *         The package name
         * @param  targets
         *         The set of target modules names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name, the set of target modules is empty, or the set
         *         of target modules contains a name that is not a legal module
         *         name
         * @throws IllegalStateException
         *         If the package is already declared as exported
         *         or this builder is for an automatic module
         */
        public Builder exports(Set<Exports.Modifier> ms,
                               String pn,
                               Set<String> targets)
        {
            Exports e = new Exports(ms, pn, targets);

            // check targets
            targets = e.targets();
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            if (strict) {
                requirePackageName(e.source());
                targets.stream().forEach(Checks::requireModuleName);
            }
            return exports(e);
        }

        /**
         * Adds an exported package with the given (and possibly empty) set of
         * modifiers. The package is exported to all modules.
         *
         * @param  ms
         *         The set of modifiers
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name
         * @throws IllegalStateException
         *         If the package is already declared as exported
         *         or this builder is for an automatic module
         */
        public Builder exports(Set<Exports.Modifier> ms, String pn) {
            if (strict) {
                requirePackageName(pn);
            }
            Exports e = new Exports(ms, pn, Collections.emptySet());
            return exports(e);
        }

        /**
         * Adds an exported package. The package is exported to a set of target
         * modules.
         *
         * @param  pn
         *         The package name
         * @param  targets
         *         The set of target modules names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name, the set of target modules is empty, or the set
         *         of target modules contains a name that is not a legal module
         *         name
         * @throws IllegalStateException
         *         If the package is already declared as exported
         *         or this builder is for an automatic module
         */
        public Builder exports(String pn, Set<String> targets) {
            return exports(Collections.emptySet(), pn, targets);
        }

        /**
         * Adds an exported package. The package is exported to all modules.
         *
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name
         * @throws IllegalStateException
         *         If the package is already declared as exported
         *         or this builder is for an automatic module
         */
        public Builder exports(String pn) {
            return exports(Collections.emptySet(), pn);
        }

        /**
         * Adds an open package.
         *
         * @param  obj
         *         The {@code Opens} object
         *
         * @return This builder
         *
         * @throws IllegalStateException
         *         If the package is already declared as open, or this is a
         *         builder for an open module or automatic module
         */
        public Builder opens(Opens obj) {
            if (open || automatic) {
                throw new IllegalStateException("Open or automatic modules cannot"
                                                + " declare open packages");
            }
            String source = obj.source();
            if (opens.containsKey(source)) {
                throw new IllegalStateException("Open package " + source
                                                + " already declared");
            }
            opens.put(source, obj);
            packages.add(source);
            return this;
        }


        /**
         * Adds an open package with the given (and possibly empty) set of
         * modifiers. The package is open to a set of target modules.
         *
         * @param  ms
         *         The set of modifiers
         * @param  pn
         *         The package name
         * @param  targets
         *         The set of target modules names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name, the set of target modules is empty, or the set
         *         of target modules contains a name that is not a legal module
         *         name
         * @throws IllegalStateException
         *         If the package is already declared as open, or this is a
         *         builder for an open module or automatic module
         */
        public Builder opens(Set<Opens.Modifier> ms,
                             String pn,
                             Set<String> targets)
        {
            Opens opens = new Opens(ms, pn, targets);

            // check targets
            targets = opens.targets();
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            if (strict) {
                requirePackageName(opens.source());
                targets.stream().forEach(Checks::requireModuleName);
            }
            return opens(opens);
        }

        /**
         * Adds an open package with the given (and possibly empty) set of
         * modifiers. The package is open to all modules.
         *
         * @param  ms
         *         The set of modifiers
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name
         * @throws IllegalStateException
         *         If the package is already declared as open, or this is a
         *         builder for an open module or automatic module
         */
        public Builder opens(Set<Opens.Modifier> ms, String pn) {
            if (strict) {
                requirePackageName(pn);
            }
            Opens e = new Opens(ms, pn, Collections.emptySet());
            return opens(e);
        }

        /**
         * Adds an open package. The package is open to a set of target modules.
         *
         * @param  pn
         *         The package name
         * @param  targets
         *         The set of target modules names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name, the set of target modules is empty, or the set
         *         of target modules contains a name that is not a legal module
         *         name
         * @throws IllegalStateException
         *         If the package is already declared as open, or this is a
         *         builder for an open module or automatic module
         */
        public Builder opens(String pn, Set<String> targets) {
            return opens(Collections.emptySet(), pn, targets);
        }

        /**
         * Adds an open package. The package is open to all modules.
         *
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal
         *         package name
         * @throws IllegalStateException
         *         If the package is already declared as open, or this is a
         *         builder for an open module or automatic module
         */
        public Builder opens(String pn) {
            return opens(Collections.emptySet(), pn);
        }

        /**
         * Adds a service dependence.
         *
         * @param  service
         *         The service type
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the service type is {@code null} or not a qualified name of
         *         a Java class
         * @throws IllegalStateException
         *         If a dependency on the service type has already been declared
         *          or this is a builder for an an automatic module
         */
        public Builder uses(String service) {
            if (automatic)
                throw new IllegalStateException("Automatic modules can not declare"
                                                + " service dependences");
            if (uses.contains(requireServiceTypeName(service)))
                throw new IllegalStateException("Dependence upon service "
                                                + service + " already declared");
            uses.add(service);
            return this;
        }

        /**
         * Provides a service with one or more implementations. The package for
         * each {@link Provides#providers provider} (or provider factory) is
         * added to the module if not already added.
         *
         * @param  p
         *         The provides
         *
         * @return This builder
         *
         * @throws IllegalStateException
         *         If the providers for the service type have already been
         *         declared
         */
        public Builder provides(Provides p) {
            String service = p.service();
            if (provides.containsKey(service))
                throw new IllegalStateException("Providers of service "
                                                + service + " already declared");
            provides.put(service, p);
            p.providers().forEach(name -> packages.add(packageName(name)));
            return this;
        }

        /**
         * Provides implementations of a service. The package for each provider
         * (or provider factory) is added to the module if not already added.
         *
         * @param  service
         *         The service type
         * @param  providers
         *         The list of provider or provider factory class names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the service type or any of the provider class names is
         *         {@code null} or not a qualified name of a Java class, or
         *         the list of provider class names is empty
         * @throws IllegalStateException
         *         If the providers for the service type have already been
         *         declared
         */
        public Builder provides(String service, List<String> providers) {
            Provides p = new Provides(service, providers);

            // check providers after the set has been copied.
            List<String> providerNames = p.providers();
            if (providerNames.isEmpty())
                throw new IllegalArgumentException("Empty providers set");
            if (strict) {
                requireServiceTypeName(p.service());
                providerNames.forEach(Checks::requireServiceProviderName);
            } else {
                // Disallow service/providers in unnamed package
                String pn = packageName(service);
                if (pn.isEmpty()) {
                    throw new IllegalArgumentException(service
                                                       + ": unnamed package");
                }
                for (String name : providerNames) {
                    pn = packageName(name);
                    if (pn.isEmpty()) {
                        throw new IllegalArgumentException(name
                                                           + ": unnamed package");
                    }
                }
            }
            return provides(p);
        }

        /**
         * Adds packages to the module. All packages in the set of package names
         * that are not in the module are added to module.
         *
         * @param  pns
         *         The (possibly empty) set of package names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If any of the package names is {@code null} or is not a
         *         legal package name
         */
        public Builder packages(Set<String> pns) {
            if (strict) {
                pns = new HashSet<>(pns);
                pns.forEach(Checks::requirePackageName);
            }
            this.packages.addAll(pns);
            return this;
        }

        /**
         * Sets the module version.
         *
         * @param  v
         *         The version
         *
         * @return This builder
         */
        public Builder version(Version v) {
            version = requireNonNull(v);
            return this;
        }

        /**
         * Sets the module version.
         *
         * @param  v
         *         The version string to parse
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code v} is {@code null} or cannot be parsed as a
         *         version string
         *
         * @see Version#parse(String)
         */
        public Builder version(String v) {
            return version(Version.parse(v));
        }

        /**
         * Sets the module main class. The package for the main class is added
         * to the module if not already added.
         *
         * @param  mc
         *         The module main class
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code mainClass} is {@code null} or not a qualified
         *         name of a Java class
         */
        public Builder mainClass(String mc) {
            String pn;
            if (strict) {
                mc = requireQualifiedClassName("main class name", mc);
                pn = packageName(mc);
                assert !pn.isEmpty();
            } else {
                // Disallow main class in unnamed package
                pn = packageName(mc);
                if (pn.isEmpty()) {
                    throw new IllegalArgumentException(mc + ": unnamed package");
                }
            }
            mainClass = mc;
            packages.add(pn);
            return this;
        }

        /**
         * Sets the operating system name.
         *
         * @param  name
         *         The operating system name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is {@code null} or the empty String
         */
        public Builder osName(String name) {
            if (name == null || name.isEmpty())
                throw new IllegalArgumentException("OS name is null or empty");
            osName = name;
            return this;
        }

        /**
         * Sets the operating system architecture.
         *
         * @param  arch
         *         The operating system architecture
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is {@code null} or the empty String
         */
        public Builder osArch(String arch) {
            if (arch == null || arch.isEmpty())
                throw new IllegalArgumentException("OS arch is null or empty");
            osArch = arch;
            return this;
        }

        /**
         * Sets the operating system version.
         *
         * @param  version
         *         The operating system version
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is {@code null} or the empty String
         */
        public Builder osVersion(String version) {
            if (version == null || version.isEmpty())
                throw new IllegalArgumentException("OS version is null or empty");
            osVersion = version;
            return this;
        }

        /**
         * Builds and returns a {@code ModuleDescriptor} from its components.
         *
         * <p> The module will require "{@code java.base}" even if the dependence
         * has not been declared (the exception is when building a module named
         * "{@code java.base}" as it cannot require itself). </p>
         *
         * <p> If building an automatic module then all packages will be
         * exported and opened to all modules. </p>
         *
         * @return The module descriptor
         */
        public ModuleDescriptor build() {
            Set<Requires> requires;
            Set<Exports> exports;
            Set<Opens> opens;

            if (automatic) {
                requires = new HashSet<>();
                exports = new HashSet<>();
                opens = new HashSet<>();
                for (String pn : packages) {
                    exports.add(new Exports(Set.of(), pn, Set.of()));
                    opens.add(new Opens(Set.of(), pn, Set.of()));
                }
            } else {
                requires = new HashSet<>(this.requires.values());
                exports = new HashSet<>(this.exports.values());
                opens = new HashSet<>(this.opens.values());
            }

            // add dependency on java.base
            if (strict
                    && !name.equals("java.base")
                    && !this.requires.containsKey("java.base")) {
                requires.add(new Requires(Set.of(Requires.Modifier.MANDATED),
                                          "java.base",
                                          null));
            }

            Set<Provides> provides = new HashSet<>(this.provides.values());

            return new ModuleDescriptor(name,
                                        version,
                                        modifiers,
                                        requires,
                                        exports,
                                        opens,
                                        uses,
                                        provides,
                                        packages,
                                        mainClass,
                                        osName,
                                        osArch,
                                        osVersion);
        }

    }

    /**
     * Compares this module descriptor to another.
     *
     * <p> Two {@code ModuleDescriptor} objects are compared by comparing their
     * module name lexicographically.  Where the module names are equal then
     * the versions, if present, are compared. </p>
     *
     * @apiNote For now, the natural ordering is not consistent with equals.
     * If two module descriptors have equal module names, equal versions if
     * present, but their corresponding components are not equal, then they
     * will be considered equal by this method.
     *
     * @param  that
     *         The object to which this module descriptor is to be compared
     *
     * @return A negative integer, zero, or a positive integer if this module
     *         descriptor is less than, equal to, or greater than the given
     *         module descriptor
     */
    @Override
    public int compareTo(ModuleDescriptor that) {
        int c = this.name().compareTo(that.name());
        if (c != 0) return c;
        if (version == null) {
            if (that.version == null)
                return 0;
            return -1;
        }
        if (that.version == null)
            return +1;
        return version.compareTo(that.version);
    }

    /**
     * Tests this module descriptor for equality with the given object.
     *
     * <p> If the given object is not a {@code ModuleDescriptor} then this
     * method returns {@code false}. Two module descriptors are equal if each
     * of their corresponding components is equal. </p>
     *
     * <p> This method satisfies the general contract of the {@link
     * java.lang.Object#equals(Object) Object.equals} method. </p>
     *
     * @param   ob
     *          the object to which this object is to be compared
     *
     * @return  {@code true} if, and only if, the given object is a module
     *          descriptor that is equal to this module descriptor
     */
    @Override
    public boolean equals(Object ob) {
        if (ob == this)
            return true;
        if (!(ob instanceof ModuleDescriptor))
            return false;
        ModuleDescriptor that = (ModuleDescriptor)ob;
        return (name.equals(that.name)
                && modifiers.equals(that.modifiers)
                && requires.equals(that.requires)
                && exports.equals(that.exports)
                && opens.equals(that.opens)
                && uses.equals(that.uses)
                && provides.equals(that.provides)
                && Objects.equals(version, that.version)
                && Objects.equals(mainClass, that.mainClass)
                && Objects.equals(osName, that.osName)
                && Objects.equals(osArch, that.osArch)
                && Objects.equals(osVersion, that.osVersion)
                && Objects.equals(packages, that.packages));
    }

    private transient int hash;  // cached hash code

    /**
     * Computes a hash code for this module descriptor.
     *
     * <p> The hash code is based upon the components of the module descriptor,
     * and satisfies the general contract of the {@link Object#hashCode
     * Object.hashCode} method. </p>
     *
     * @return The hash-code value for this module descriptor
     */
    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = name.hashCode();
            hc = hc * 43 + Objects.hashCode(modifiers);
            hc = hc * 43 + requires.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + opens.hashCode();
            hc = hc * 43 + uses.hashCode();
            hc = hc * 43 + provides.hashCode();
            hc = hc * 43 + Objects.hashCode(version);
            hc = hc * 43 + Objects.hashCode(mainClass);
            hc = hc * 43 + Objects.hashCode(osName);
            hc = hc * 43 + Objects.hashCode(osArch);
            hc = hc * 43 + Objects.hashCode(osVersion);
            hc = hc * 43 + Objects.hashCode(packages);
            if (hc == 0)
                hc = -1;
            hash = hc;
        }
        return hc;
    }

    /**
     * Returns a string describing this descriptor.
     *
     * @return A string describing this descriptor
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (isOpen())
            sb.append("open ");
        sb.append("module { name: ").append(toNameAndVersion());
        if (!requires.isEmpty())
            sb.append(", ").append(requires);
        if (!uses.isEmpty())
            sb.append(", uses: ").append(uses);
        if (!exports.isEmpty())
            sb.append(", exports: ").append(exports);
        if (!opens.isEmpty())
            sb.append(", opens: ").append(opens);
        if (!provides.isEmpty()) {
            sb.append(", provides: ").append(provides);
        }
        sb.append(" }");
        return sb.toString();
    }


    /**
     * Instantiates a builder to build a module descriptor.
     *
     * @param  name
     *         The module name
     *
     * @return A new builder
     *
     * @throws IllegalArgumentException
     *         If the module name is {@code null} or is not a legal module
     *         name
     */
    public static Builder newModule(String name) {
        return new Builder(name, true, Set.of());
    }

    /**
     * Instantiates a builder to build a module descriptor for an open module.
     *
     * <p> The builder for an open module cannot be used to declare any open
     * packages. </p>
     *
     * @param  name
     *         The module name
     *
     * @return A new builder that builds an open module
     *
     * @throws IllegalArgumentException
     *         If the module name is {@code null} or is not a legal module
     *         name
     */
    public static Builder newOpenModule(String name) {
        return new Builder(name, true, Set.of(Modifier.OPEN));
    }

    /**
     * Instantiates a builder to build a module descriptor for an automatic
     * module.
     *
     * <p> The builder for an automatic module cannot be used to declare module
     * or service dependences, or exported or open packages. </p>
     *
     * @param  name
     *         The module name
     *
     * @return A new builder that builds an automatic module
     *
     * @throws IllegalArgumentException
     *         If the module name is {@code null} or is not a legal module
     *         name
     *
     * @see ModuleFinder#of(Path[])
     */
    public static Builder newAutomaticModule(String name) {
        return new Builder(name, true, Set.of(Modifier.AUTOMATIC));
    }


    /**
     * Reads the binary form of a module declaration from an input stream
     * as a module descriptor.
     *
     * <p> If the descriptor encoded in the input stream does not indicate a
     * set of packages in the module then the {@code packageFinder} will be
     * invoked. If the {@code packageFinder} throws an {@link UncheckedIOException}
     * then {@link IOException} cause will be re-thrown. </p>
     *
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with an {@code InvalidModuleDescriptorException} or {@code
     * IOException} then it may do so after some, but not all, bytes have
     * been read from the input stream. It is strongly recommended that the
     * stream be promptly closed and discarded if an exception occurs. </p>
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of packages in the descriptor itself.
     *
     * @param  in
     *         The input stream
     * @param  packageFinder
     *         A supplier that can produce the set of packages
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     * @throws IOException
     *         If an I/O error occurs reading from the input stream or {@code
     *         UncheckedIOException} is thrown by the package finder
     */
    public static ModuleDescriptor read(InputStream in,
                                        Supplier<Set<String>> packageFinder)
        throws IOException
    {
        return ModuleInfo.read(in, requireNonNull(packageFinder)).descriptor();
    }

    /**
     * Reads the binary form of a module declaration from an input stream
     * as a module descriptor.
     *
     * @param  in
     *         The input stream
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     * @throws IOException
     *         If an I/O error occurs reading from the input stream
     */
    public static ModuleDescriptor read(InputStream in) throws IOException {
        return ModuleInfo.read(in, null).descriptor();
    }

    /**
     * Reads the binary form of a module declaration from a byte buffer
     * as a module descriptor.
     *
     * <p> If the descriptor encoded in the byte buffer does not indicate a
     * set of packages then the {@code packageFinder} will be invoked. </p>
     *
     * <p> The module descriptor is read from the buffer stating at index
     * {@code p}, where {@code p} is the buffer's {@link ByteBuffer#position()
     * position} when this method is invoked. Upon return the buffer's position
     * will be equal to {@code p + n} where {@code n} is the number of bytes
     * read from the buffer. </p>
     *
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with an {@code InvalidModuleDescriptorException} then it
     * may do so after some, but not all, bytes have been read. </p>
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of packages in the descriptor itself.
     *
     * @param  bb
     *         The byte buffer
     * @param  packageFinder
     *         A supplier that can produce the set of packages
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb,
                                        Supplier<Set<String>> packageFinder)
    {
        return ModuleInfo.read(bb, requireNonNull(packageFinder)).descriptor();
    }

    /**
     * Reads the binary form of a module declaration from a byte buffer
     * as a module descriptor.
     *
     * @param  bb
     *         The byte buffer
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb) {
        return ModuleInfo.read(bb, null).descriptor();
    }

    private static <K,V> Map<K,V> emptyOrUnmodifiableMap(Map<K,V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        } else if (map.size() == 1) {
            Map.Entry<K, V> entry = map.entrySet().iterator().next();
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        } else {
            return Collections.unmodifiableMap(map);
        }
    }

    private static <T> Set<T> emptyOrUnmodifiableSet(Set<T> set) {
        if (set.isEmpty()) {
            return Collections.emptySet();
        } else if (set.size() == 1) {
            return Collections.singleton(set.iterator().next());
        } else {
            return Collections.unmodifiableSet(set);
        }
    }

    private static String packageName(String cn) {
        int index = cn.lastIndexOf('.');
        return (index == -1) ? "" : cn.substring(0, index);
    }

    /**
     * Returns a string containing the given set of modifiers and label.
     */
    private static <M> String toString(Set<M> mods, String what) {
        return (Stream.concat(mods.stream().map(e -> e.toString().toLowerCase()),
                              Stream.of(what)))
                .collect(Collectors.joining(" "));
    }

    static {
        /**
         * Setup the shared secret to allow code in other packages access
         * private package methods in java.lang.module.
         */
        jdk.internal.misc.SharedSecrets
            .setJavaLangModuleAccess(new jdk.internal.misc.JavaLangModuleAccess() {
                @Override
                public Builder newModuleBuilder(String mn,
                                                boolean strict,
                                                Set<ModuleDescriptor.Modifier> modifiers) {
                    return new Builder(mn, strict, modifiers);
                }

                @Override
                public Set<String> packages(ModuleDescriptor.Builder builder) {
                    return builder.packages();
                }

                @Override
                public Requires newRequires(Set<Requires.Modifier> ms, String mn, Version v) {
                    return new Requires(ms, mn, v, true);
                }

                @Override
                public Exports newExports(Set<Exports.Modifier> ms, String source) {
                    return new Exports(ms, source, Collections.emptySet(), true);
                }

                @Override
                public Exports newExports(Set<Exports.Modifier> ms,
                                          String source,
                                          Set<String> targets) {
                    return new Exports(ms, source, targets, true);
                }

                @Override
                public Opens newOpens(Set<Opens.Modifier> ms,
                                      String source,
                                      Set<String> targets) {
                    return new Opens(ms, source, targets, true);
                }

                @Override
                public Opens newOpens(Set<Opens.Modifier> ms, String source) {
                    return new Opens(ms, source, Collections.emptySet(), true);
                }

                @Override
                public Provides newProvides(String service, List<String> providers) {
                    return new Provides(service, providers, true);
                }

                @Override
                public ModuleDescriptor newModuleDescriptor(String name,
                                                            Version version,
                                                            Set<ModuleDescriptor.Modifier> modifiers,
                                                            Set<Requires> requires,
                                                            Set<Exports> exports,
                                                            Set<Opens> opens,
                                                            Set<String> uses,
                                                            Set<Provides> provides,
                                                            Set<String> packages,
                                                            String mainClass,
                                                            String osName,
                                                            String osArch,
                                                            String osVersion,
                                                            int hashCode) {
                    return new ModuleDescriptor(name,
                                                version,
                                                modifiers,
                                                requires,
                                                exports,
                                                opens,
                                                uses,
                                                provides,
                                                packages,
                                                mainClass,
                                                osName,
                                                osArch,
                                                osVersion,
                                                hashCode,
                                                false);
                }

                @Override
                public Configuration resolveRequiresAndUses(ModuleFinder finder,
                                                            Collection<String> roots,
                                                            boolean check,
                                                            PrintStream traceOutput)
                {
                    return Configuration.resolveRequiresAndUses(finder, roots, check, traceOutput);
                }
            });
    }

}
