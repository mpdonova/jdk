/*
 * Copyright (c) 2015, 2024, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jshell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.ModuleElement.ExportsDirective;
import javax.lang.model.element.ModuleElement.RequiresDirective;
import javax.lang.model.util.ElementFilter;

import jdk.jshell.Snippet.SubKind;
import jdk.jshell.TaskFactory.AnalyzeTask;

import static jdk.jshell.Util.PREFIX_PATTERN;
import static jdk.jshell.Util.REPL_PACKAGE;
import static jdk.internal.jshell.debug.InternalDebugControl.DBG_DEP;

/**
 * Maintain relationships between the significant entities: Snippets,
 * internal snippet index, Keys, etc.
 * @author Robert Field
 */
final class SnippetMaps {

    private final List<Snippet> keyIndexToSnippet = new ArrayList<>();
    private final Set<Snippet> snippets = new LinkedHashSet<>();
    private final Map<String, Set<Integer>> dependencies = new HashMap<>();
    private final Map<String, Set<String>> module2PackagesForImport = new HashMap<>();
    private final JShell state;

    SnippetMaps(JShell proc) {
        this.state = proc;
    }

    void installSnippet(Snippet sn) {
        if (sn != null && snippets.add(sn)) {
            if (sn.key() != null) {
                sn.setId((state.idGenerator != null)
                        ? state.idGenerator.apply(sn, sn.key().index())
                        : "" + sn.key().index());
                setSnippet(sn.key().index(), sn);
            }
        }
    }

    private void setSnippet(int ki, Snippet snip) {
        while (ki >= keyIndexToSnippet.size()) {
            keyIndexToSnippet.add(null);
        }
        keyIndexToSnippet.set(ki, snip);
    }

    Snippet getSnippet(Key key) {
        return getSnippet(key.index());
    }

    Snippet getSnippet(int ki) {
        Snippet sn = getSnippetDeadOrAlive(ki);
        return (sn != null && !sn.status().isActive())
                ? null
                : sn;
    }

    Snippet getSnippetDeadOrAlive(int ki) {
        if (ki >= keyIndexToSnippet.size()) {
            return null;
        }
        return keyIndexToSnippet.get(ki);
    }

    List<Snippet> snippetList() {
        return new ArrayList<>(snippets);
    }

    String packageAndImportsExcept(Set<Key> except, Collection<Snippet> plus) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(REPL_PACKAGE).append(";\n");
        for (Snippet si : keyIndexToSnippet) {
            if (si != null && si.status().isDefined() && (except == null || !except.contains(si.key())) && si.name() != null && !si.name().isEmpty()) {
                sb.append(si.importLine(state));
            }
        }
        if (plus != null) {
            plus.forEach(psi -> sb.append(psi.importLine(state)));
        }
        return sb.toString();
    }

    List<Snippet> getDependents(Snippet snip) {
        if (!snip.kind().isPersistent()) {
            return Collections.emptyList();
        }
        Set<Integer> depset;
        if (snip.unitName.equals("*")) {
            // star import
            depset = new HashSet<>();
            for (Set<Integer> as : dependencies.values()) {
                depset.addAll(as);
            }
        } else {
            depset = dependencies.get(snip.name());
        }
        if (depset == null) {
            return Collections.emptyList();
        }
        List<Snippet> deps = new ArrayList<>();
        for (Integer dss : depset) {
            Snippet dep = getSnippetDeadOrAlive(dss);
            if (dep != null) {
                deps.add(dep);
                state.debug(DBG_DEP, "Found dependency %s -> %s\n", snip.name(), dep.name());
            }
        }
        return deps;
    }

    void mapDependencies(Snippet snip) {
        addDependencies(snip.declareReferences(), snip);
        addDependencies(snip.bodyReferences(),    snip);
    }

    private void addDependencies(Collection<String> refs, Snippet snip) {
        if (refs == null) return;
        for (String ref : refs) {
            dependencies.computeIfAbsent(ref, k -> new HashSet<>())
                        .add(snip.key().index());
            state.debug(DBG_DEP, "Added dependency %s -> %s\n", ref, snip.name());
        }
    }

    String fullClassNameAndPackageToClass(String full, String pkg) {
        Matcher mat = PREFIX_PATTERN.matcher(full);
        if (mat.lookingAt()) {
            return full.substring(mat.end());
        }
        String simpleName = full.substring(full.lastIndexOf(".") + 1);
        Stream<String> declaredInSnippets = state.keyMap.typeDeclKeys()
                .map(key -> (TypeDeclSnippet) getSnippet(key))
                .map(decl -> decl.name());
        if (declaredInSnippets.anyMatch(clazz -> simpleName.equals(clazz))) {
            //simple name of full clashes with a name of a user-defined class,
            //use the fully-qualified name:
            return full;
        }
        state.debug(DBG_DEP, "SM %s %s\n", full, pkg);
        List<String> klasses = importSnippets()
                               .filter(isi -> !isi.isStar)
                               .map(isi -> isi.fullname)
                               .toList();
        for (String k : klasses) {
            if (k.equals(full)) {
                return simpleName;
            }
        }
        if (pkg.isEmpty()) {
            return full;
        }
        Stream<String> pkgs = importSnippets()
                               .filter(isi -> isi.isStar)
                               .map(isi -> isi.fullname.substring(0, isi.fullname.lastIndexOf(".")));
        if (Stream.concat(Stream.of("java.lang"), pkgs).anyMatch(pkg::equals)) {
            return full.substring(pkg.length() + 1);
        }
        Stream<String> modPkgs = importSnippets()
                                   .filter(isi -> isi.subKind() == SubKind.MODULE_IMPORT_SUBKIND)
                                   .map(isi -> isi.fullname)
                                   .flatMap(this::module2PackagesForImport);
        if (modPkgs.anyMatch(pkg::equals)) {
            return full.substring(pkg.length() + 1);
        }
        return full;
    }

    /**
     * Compute the set of imports to prepend to a snippet
     * @return a stream of the import needed
     */
    private Stream<ImportSnippet> importSnippets() {
        return state.keyMap.importKeys()
                .map(key -> (ImportSnippet)getSnippet(key))
                .filter(sn -> sn != null && state.status(sn).isDefined());
    }

    private Stream<String> module2PackagesForImport(String module) {
        return module2PackagesForImport.computeIfAbsent(module, mod -> {
            return state.taskFactory
                        .analyze(new OuterWrap(Wrap.identityWrap(" ")),
                                 at -> computeImports(at, mod));
        }).stream();
    }

    private Set<String> computeImports(AnalyzeTask at, String mod) {
        List<ModuleElement> todo = new ArrayList<>();
        Set<ModuleElement> seenModules = new HashSet<>();
        Set<String> exportedPackages = new HashSet<>();
        todo.add(at.getElements().getModuleElement(mod));
        while (!todo.isEmpty()) {
            ModuleElement current = todo.remove(todo.size() - 1);
            if (current == null || !seenModules.add(current)) {
                continue;
            }
            for (ExportsDirective exp : ElementFilter.exportsIn(current.getDirectives())) {
                if (exp.getTargetModules() != null) {
                    continue;
                }
                exportedPackages.add(exp.getPackage().getQualifiedName().toString());
            }
            for (RequiresDirective req : ElementFilter.requiresIn(current.getDirectives())) {
                if (!req.isTransitive()) {
                    continue;
                }
                todo.add(req.getDependency());
            }
        }
        return exportedPackages;
    }
}
