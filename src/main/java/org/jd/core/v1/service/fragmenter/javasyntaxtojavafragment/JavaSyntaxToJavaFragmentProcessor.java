/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.model.javafragment.ImportsFragment;
import org.jd.core.v1.model.javasyntax.CompilationUnit;
import org.jd.core.v1.model.message.Message;
import org.jd.core.v1.model.processor.Processor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.visitor.CompilationUnitVisitor;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.visitor.SearchImportsVisitor;

import java.util.Map;

/**
 * Convert a Java syntax model to a list of fragments.<br><br>
 *
 * Input:  {@link org.jd.core.v1.model.javasyntax.CompilationUnit}<br>
 * Output: List<{@link org.jd.core.v1.model.fragment.Fragment}><br>
 */
public class JavaSyntaxToJavaFragmentProcessor implements Processor {

    public void process(Message message) throws Exception {
        Loader loader = message.getHeader("loader");
        String mainInternalTypeName = message.getHeader("mainInternalTypeName");
        int majorVersion = message.getHeader("majorVersion");
        CompilationUnit compilationUnit = message.getBody();

        SearchImportsVisitor importsVisitor = new SearchImportsVisitor(loader, mainInternalTypeName);
        importsVisitor.visit(compilationUnit);
        ImportsFragment importsFragment = importsVisitor.getImportsFragment();
        message.setHeader("maxLineNumber", importsVisitor.getMaxLineNumber());

        CompilationUnitVisitor visitor = new CompilationUnitVisitor(loader, mainInternalTypeName, majorVersion, importsFragment);
        Map<String, Object> configuration = message.getHeader("configuration");
        Object textBlocks = (configuration == null) ? null : configuration.get("textBlocks");
        visitor.setTextBlocksEnabled((textBlocks != null) && "true".equals(textBlocks.toString()));
        Object unnamedVariables = (configuration == null) ? null : configuration.get("unnamedVariables");
        visitor.setUnnamedVariablesEnabled((unnamedVariables != null) && "true".equals(unnamedVariables.toString()));
        Object localVariableTypeInference = (configuration == null) ? null : configuration.get("localVariableTypeInference");
        visitor.setVarEnabled((localVariableTypeInference != null) && "true".equals(localVariableTypeInference.toString()));
        visitor.visit(compilationUnit);
        message.setBody(visitor.getFragments());
    }
}
