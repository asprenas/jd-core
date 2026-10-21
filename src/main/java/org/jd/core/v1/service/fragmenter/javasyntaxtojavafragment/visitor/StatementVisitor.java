/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.visitor;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.model.javafragment.ImportsFragment;
import org.jd.core.v1.model.javafragment.StartBlockFragment;
import org.jd.core.v1.model.javafragment.StartSingleStatementBlockFragment;
import org.jd.core.v1.model.javafragment.StartStatementsBlockFragment;
import org.jd.core.v1.model.javafragment.TokensFragment;
import org.jd.core.v1.model.javasyntax.expression.Expression;
import org.jd.core.v1.model.javasyntax.expression.SwitchExpression;
import org.jd.core.v1.model.javasyntax.statement.*;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.type.BaseType;
import org.jd.core.v1.model.token.*;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.ArrowSwitchUtil;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.JavaFragmentFactory;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.UnnamedVariableUtil;
import org.jd.core.v1.model.javasyntax.declaration.ExpressionVariableInitializer;
import org.jd.core.v1.model.javasyntax.declaration.LocalVariableDeclarator;
import org.jd.core.v1.model.javasyntax.expression.CastExpression;
import org.jd.core.v1.model.javasyntax.expression.MethodInvocationExpression;
import org.jd.core.v1.model.javasyntax.expression.NewExpression;
import org.jd.core.v1.model.javasyntax.type.PrimitiveType;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

public class StatementVisitor extends ExpressionVisitor {
    public static final KeywordToken ASSERT = new KeywordToken("assert");
    public static final KeywordToken BREAK = new KeywordToken("break");
    public static final KeywordToken VAR = new KeywordToken("var");
    public static final KeywordToken WHEN = new KeywordToken("when");
    public static final KeywordToken CASE = new KeywordToken("case");
    public static final KeywordToken CATCH = new KeywordToken("catch");
    public static final KeywordToken CONTINUE = new KeywordToken("continue");
    public static final KeywordToken DEFAULT = new KeywordToken("default");
    public static final KeywordToken DO = new KeywordToken("do");
    public static final KeywordToken ELSE = new KeywordToken("else");
    public static final KeywordToken FINAL = new KeywordToken("final");
    public static final KeywordToken FINALLY = new KeywordToken("finally");
    public static final KeywordToken FOR = new KeywordToken("for");
    public static final KeywordToken IF = new KeywordToken("if");
    public static final KeywordToken RETURN = new KeywordToken("return");
    public static final KeywordToken STRICT = new KeywordToken("strictfp");
    public static final KeywordToken SYNCHRONIZED = new KeywordToken("synchronized");
    public static final KeywordToken SWITCH = new KeywordToken("switch");
    public static final KeywordToken THROW = new KeywordToken("throw");
    public static final KeywordToken YIELD = new KeywordToken("yield");
    public static final KeywordToken TRANSIENT = new KeywordToken("transient");
    public static final KeywordToken TRY = new KeywordToken("try");
    public static final KeywordToken VOLATILE = new KeywordToken("volatile");
    public static final KeywordToken WHILE = new KeywordToken("while");

    public static final TextToken SPACE_ARROW = new TextToken(" ->");

    protected boolean varSupported;
    protected boolean varEnabled;

    // Declarator of the statement being printed, written '_'
    protected LocalVariableDeclarator unnamedDeclarator;

    protected boolean arrowSwitchSupported;

    public StatementVisitor(Loader loader, String mainInternalTypeName, int majorVersion, ImportsFragment importsFragment) {
        super(loader, mainInternalTypeName, majorVersion, importsFragment);
        this.arrowSwitchSupported = (majorVersion >= ArrowSwitchUtil.JAVA_14);
        this.varSupported = (majorVersion >= 54); // (majorVersion >= Java 10)
    }

    /**
     * Java 10+ 'var' for the local variables ('localVariableTypeInference' configuration, off by default: the
     * declared types are in the class file, the source may not use 'var')
     */
    public void setVarEnabled(boolean enabled) {
        this.varEnabled = enabled && varSupported;
    }

    /**
     * 'var' declares the type of the initializer: only when it is the declared type, and the initializer does not
     * need a target type (generic method invocations are excluded, they would infer other type arguments)
     */
    protected static boolean canUseVar(LocalVariableDeclarationStatement statement) {
        if (!(statement.getLocalVariableDeclarators() instanceof LocalVariableDeclarator)) {
            return false;
        }

        LocalVariableDeclarator declarator = (LocalVariableDeclarator)statement.getLocalVariableDeclarators();

        if ((declarator.getDimension() != 0) || !(declarator.getVariableInitializer() instanceof ExpressionVariableInitializer)) {
            return false;
        }

        Expression expression = ((ExpressionVariableInitializer)declarator.getVariableInitializer()).getExpression();

        if (!statement.getType().equals(expression.getType())) {
            return false;
        }

        if (expression.isNewExpression()) {
            return ((NewExpression)expression).getBodyDeclaration() == null;
        }
        if (expression.isMethodInvocationExpression()) {
            // 'var a = list.get(0)'. Not a generic method: without target type, its type arguments may be inferred
            // differently ('Collections.emptyList()' would be 'List<Object>')
            return !((MethodInvocationExpression)expression).isGenericMethod();
        }

        if (expression.isCastExpression()) {
            // An implicit cast is not written: 'long l = i' is not 'var l = i'
            return ((CastExpression)expression).isExplicit();
        }
        if (expression.isIntegerConstantExpression()) {
            // 'byte b = 1' is not 'var b = 1'
            return expression.getType() == PrimitiveType.TYPE_INT;
        }

        return expression.isStringConstantExpression() ||
               expression.isLocalVariableReferenceExpression() || expression.isFieldReferenceExpression() ||
               expression.isLongConstantExpression() ||
               expression.isDoubleConstantExpression() || expression.isFloatConstantExpression() ||
               expression.isBooleanExpression();
    }

    @Override
    public void visit(AssertStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);
        tokens.add(ASSERT);
        tokens.add(TextToken.SPACE);
        statement.getCondition().accept(this);

        Expression msg = statement.getMessage();

        if (msg != null) {
            tokens.add(TextToken.SPACE_COLON_SPACE);
            msg.accept(this);
        }

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(BreakStatement statement) {
        tokens = new Tokens();
        tokens.add(BREAK);

        if (statement.getLabel() != null) {
            tokens.add(TextToken.SPACE);
            tokens.add(newTextToken(statement.getLabel()));
        }

        tokens.add(TextToken.SEMICOLON);
        fragments.addTokensFragment(tokens);
    }

    @Override public void visit(ByteCodeStatement statement) {
        visitComment(statement.getText());
    }

    @Override public void visit(CommentStatement statement) {
        visitComment(statement.getText());
    }

    protected void visitComment(String text) {
        tokens = new Tokens();
        tokens.add(StartMarkerToken.COMMENT);

        StringTokenizer st = new StringTokenizer(text, "\n");

        while (st.hasMoreTokens()) {
            tokens.add(new TextToken(st.nextToken()));
            tokens.add(NewLineToken.NEWLINE_1);
        }

        tokens.remove(tokens.size()-1);
        tokens.add(EndMarkerToken.COMMENT);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(ContinueStatement statement) {
        tokens = new Tokens();
        tokens.add(CONTINUE);

        if (statement.getLabel() != null) {
            tokens.add(TextToken.SPACE);
            tokens.add(newTextToken(statement.getLabel()));
        }

        tokens.add(TextToken.SEMICOLON);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(DoWhileStatement statement) {
        StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsDoWhileBlock(fragments);

        safeAccept(statement.getStatements());

        JavaFragmentFactory.addEndStatementsBlock(fragments, group);

        tokens = new Tokens();
        tokens.add(WHILE);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        tokens.add(TextToken.SEMICOLON);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(ExpressionStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);

        statement.getExpression().accept(this);

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(ForStatement statement) {
        tokens = new Tokens();
        tokens.add(FOR);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        safeAccept(statement.getDeclaration());
        safeAccept(statement.getInit());

        if (statement.getCondition() == null) {
            tokens.add(TextToken.SEMICOLON);
        } else {
            tokens.add(TextToken.SEMICOLON_SPACE);
            statement.getCondition().accept(this);
        }

        if (statement.getUpdate() == null) {
            tokens.add(TextToken.SEMICOLON);
        } else {
            tokens.add(TextToken.SEMICOLON_SPACE);
            statement.getUpdate().accept(this);
        }

        visitLoopStatements(statement.getStatements());
    }

    @Override
    public void visit(ForEachStatement statement) {
        tokens = new Tokens();
        tokens.add(FOR);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        BaseType type = statement.getType();

        type.accept(this);

        tokens.add(TextToken.SPACE);
        tokens.add(newTextToken(getVariableName(statement.getName(), statement.getStatements())));
        tokens.add(TextToken.SPACE_COLON_SPACE);

        statement.getExpression().accept(this);

        visitLoopStatements(statement.getStatements());
    }

    protected void visitLoopStatements(BaseStatement statements) {
        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        fragments.addTokensFragment(tokens);

        if (statements == null) {
            tokens.add(TextToken.SEMICOLON);
        } else {
            Fragments tmp = fragments;
            fragments = new Fragments();

            statements.accept(this);

            switch (fragments.size()) {
                case 0:
                    tokens.add(TextToken.SEMICOLON);
                    break;
                case 1:
                    StartSingleStatementBlockFragment start = JavaFragmentFactory.addStartSingleStatementBlock(tmp);
                    tmp.addAll(fragments);
                    JavaFragmentFactory.addEndSingleStatementBlock(tmp, start);
                    break;
                default:
                    StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(tmp);
                    tmp.addAll(fragments);
                    JavaFragmentFactory.addEndStatementsBlock(tmp, group);
                    break;
            }

            fragments = tmp;
        }
    }

    @Override
    public void visit(IfStatement statement) {
        tokens = new Tokens();
        tokens.add(IF);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        fragments.addTokensFragment(tokens);

        BaseStatement stmt = statement.getStatements();

        if (stmt == null) {
            fragments.add(TokensFragment.SEMICOLON);
        } else {
            Fragments tmp = fragments;
            fragments = new Fragments();

            stmt.accept(this);

            switch (stmt.size()) {
                case 0:
                    tmp.add(TokensFragment.SEMICOLON);
                    break;
                case 1:
                    StartSingleStatementBlockFragment start = JavaFragmentFactory.addStartSingleStatementBlock(tmp);
                    tmp.addAll(fragments);
                    JavaFragmentFactory.addEndSingleStatementBlock(tmp, start);
                    break;
                default:
                    StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(tmp);
                    tmp.addAll(fragments);
                    JavaFragmentFactory.addEndStatementsBlock(tmp, group);
                    break;
            }

            fragments = tmp;
        }
    }

    @Override
    public void visit(IfElseStatement statement) {
        tokens = new Tokens();
        tokens.add(IF);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        fragments.addTokensFragment(tokens);

        StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(fragments);
        statement.getStatements().accept(this);
        JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        visitElseStatements(statement.getElseStatements(), group);
    }

    protected void visitElseStatements(BaseStatement elseStatements, StartStatementsBlockFragment.Group group) {
        BaseStatement statementList = elseStatements;

        if (elseStatements.isList()) {
            if (elseStatements.size() == 1) {
                statementList = elseStatements.getFirst();
            }
        }

        tokens = new Tokens();
        tokens.add(ELSE);

        if (statementList.isIfElseStatement()) {
            tokens.add(TextToken.SPACE);
            tokens.add(IF);
            tokens.add(TextToken.SPACE);
            tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

            statementList.getCondition().accept(this);

            tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
            fragments.addTokensFragment(tokens);

            JavaFragmentFactory.addStartStatementsBlock(fragments, group);
            statementList.getStatements().accept(this);
            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
            visitElseStatements(statementList.getElseStatements(), group);
        } else if (statementList.isIfStatement()) {
            tokens.add(TextToken.SPACE);
            tokens.add(IF);
            tokens.add(TextToken.SPACE);
            tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

            statementList.getCondition().accept(this);

            tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
            fragments.addTokensFragment(tokens);

            JavaFragmentFactory.addStartStatementsBlock(fragments, group);

            statementList.getStatements().accept(this);

            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        } else {
            fragments.addTokensFragment(tokens);

            JavaFragmentFactory.addStartStatementsBlock(fragments, group);

            elseStatements.accept(this);

            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        }
    }

    @Override
    public void visit(LabelStatement statement) {
        tokens = new Tokens();
        tokens.add(newTextToken(statement.getLabel()));
        tokens.add(TextToken.COLON);

        if (statement.getStatement() == null) {
            fragments.addTokensFragment(tokens);
        } else {
            tokens.add(TextToken.SPACE);
            fragments.addTokensFragment(tokens);
            statement.getStatement().accept(this);
        }
    }

    @Override
    public void visit(LambdaExpressionStatement statement) {
        statement.getExpression().accept(this);
    }

    @Override
    public void visit(LocalVariableDeclarationStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);

        if (statement.isFinal()) {
            tokens.add(FINAL);
            tokens.add(TextToken.SPACE);
        }

        boolean var = statement.isVar() || (varEnabled && canUseVar(statement));
        boolean diamond = diamondOperatorSupported;

        if (var) {
            tokens.add(VAR);
            // 'var list = new ArrayList<String>()': with '<>', the type arguments would be inferred as Object
            diamondOperatorSupported = statement.isVar() && diamondOperatorSupported;
        } else {
            BaseType type = statement.getType();
            // '@A String local = ...': the annotations of the variable ('localvar_target')
            TypeAnnotationReferences references = (statement.getLocalVariableDeclarators() instanceof LocalVariableDeclarator) ?
                ((LocalVariableDeclarator)statement.getLocalVariableDeclarators()).getTypeAnnotationReferences() : null;

            visitAnnotatedType(type, references, TypeAnnotationReferences.LOCAL_VARIABLE, -1, "");
        }

        tokens.add(TextToken.SPACE);

        statement.getLocalVariableDeclarators().accept(this);
        diamondOperatorSupported = diamond;

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(ReturnExpressionStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);
        tokens.addLineNumberToken(statement.getLineNumber());
        tokens.add(RETURN);
        tokens.add(TextToken.SPACE);

        statement.getExpression().accept(this);

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(YieldStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);
        tokens.addLineNumberToken(statement.getLineNumber());
        tokens.add(YIELD);
        tokens.add(TextToken.SPACE);

        statement.getExpression().accept(this);

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(SwitchExpression expression) {
        // Printed like a lambda body: the case blocks are statements inside an expression
        SwitchStatement statement = expression.getSwitchStatement();

        tokens.add(SWITCH);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        tokens.add(TextToken.SPACE);
        fragments.addTokensFragment(tokens);

        StartBlockFragment start = JavaFragmentFactory.addStartStatementsInLambdaBlock(fragments);

        tokens = new Tokens();
        visitSwitchBlocks(statement, true);

        if (inExpressionFlag) {
            JavaFragmentFactory.addEndStatementsInLambdaBlockInParameter(fragments, start);
        } else {
            JavaFragmentFactory.addEndStatementsInLambdaBlock(fragments, start);
        }

        tokens = new Tokens();
    }

    @Override
    public void visit(ReturnStatement statement) {
        fragments.add(TokensFragment.RETURN_SEMICOLON);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(Statements list) {
        int size = list.size();

        for (int i = 0; i < size; i++) {
            if (i > 0) {
                JavaFragmentFactory.addSpacerBetweenStatements(fragments);
            }

            Statement statement = list.get(i);

            if (unnamedVariablesEnabled) {
                // 'T _ = init;': the variable is not read by the next statements of the block
                LocalVariableDeclarator declarator = UnnamedVariableUtil.getUnnamedCandidate(statement);

                if ((declarator != null) && !UnnamedVariableUtil.isReferenced(list.subList(i + 1, size), declarator.getName())) {
                    unnamedDeclarator = declarator;
                }
            }

            statement.accept(this);
            unnamedDeclarator = null;
        }
    }

    @Override
    public void visit(SwitchStatement statement) {
        tokens = new Tokens();
        tokens.add(SWITCH);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
        fragments.addTokensFragment(tokens);

        StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(fragments);

        visitSwitchBlocks(statement, false);

        JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        JavaFragmentFactory.addSpacerAfterEndStatementsBlock(fragments);
    }

    protected void visitSwitchBlocks(SwitchStatement statement, boolean expression) {
        boolean arrows = arrowSwitchSupported && ArrowSwitchUtil.canUseArrows(statement, expression);
        Iterator<SwitchStatement.Block> iterator = statement.getBlocks().iterator();

        while (iterator.hasNext()) {
            SwitchStatement.Block block = iterator.next();

            if (arrows) {
                visitArrowBlock(block, expression);
            } else {
                block.accept(this);
            }

            if (iterator.hasNext()) {
                JavaFragmentFactory.addSpacerBetweenSwitchLabelBlock(fragments);
            }
        }
    }

    /** 'case 1, 2 -> expression;', 'case 3 -> throw ...;', 'case 4 -> statement;' or 'default -> { ... }' */
    protected void visitArrowBlock(SwitchStatement.Block block, boolean expression) {
        int savedMaxLineNumber = maxLineNumber;

        tokens = new Tokens();

        List<SwitchStatement.Label> labels = (block instanceof SwitchStatement.MultiLabelsBlock) ?
            ((SwitchStatement.MultiLabelsBlock)block).getLabels() :
            Collections.singletonList(((SwitchStatement.LabelBlock)block).getLabel());

        if (labels.get(0) == SwitchStatement.DEFAULT_LABEL) {
            tokens.add(DEFAULT);
        } else {
            tokens.add(CASE);
            tokens.add(TextToken.SPACE);

            for (int i = 0, size = labels.size(); i < size; i++) {
                if (i > 0) {
                    tokens.add(TextToken.COMMA_SPACE);
                }
                if (labels.get(i) == SwitchStatement.DEFAULT_LABEL) {
                    // 'case null, default'
                    tokens.add(DEFAULT);
                } else {
                    SwitchStatement.ExpressionLabel label = (SwitchStatement.ExpressionLabel)labels.get(i);

                    label.getExpression().accept(this);

                    if (label.getGuard() != null) {
                        tokens.add(TextToken.SPACE);
                        tokens.add(WHEN);
                        tokens.add(TextToken.SPACE);
                        label.getGuard().accept(this);
                    }
                }
            }
        }

        // The label and its body are on one line: the line of the body, not the line of the label constants
        // (javac gives them the line of 'switch')
        tokens.removeIf(token -> token instanceof LineNumberToken);
        tokens.currentLineNumber = UNKNOWN_LINE_NUMBER;
        maxLineNumber = savedMaxLineNumber;
        tokens.add(TextToken.SPACE_ARROW_SPACE);

        BaseStatement statements = ArrowSwitchUtil.getArrowStatements(block);

        if (statements == null) {
            tokens.add(TextToken.LEFTRIGHTCURLYBRACKETS);
            fragments.addTokensFragment(tokens);
        } else if ((statements.size() == 1) && expression && statements.getFirst().isYieldStatement()) {
            // 'case 1 -> value;'
            YieldStatement yield = (YieldStatement)statements.getFirst();

            tokens.addLineNumberToken(yield.getLineNumber());
            yield.getExpression().accept(this);
            tokens.add(TextToken.SEMICOLON);
            fragments.addTokensFragment(tokens);
        } else if ((statements.size() == 1) && (statements.getFirst().isThrowStatement() || statements.getFirst().isExpressionStatement())) {
            // 'case 1 -> throw ...;' or 'case 1 -> System.out.println();'
            fragments.addTokensFragment(tokens);
            statements.getFirst().accept(this);
        } else {
            // The block adds ' {'
            tokens.remove(tokens.size() - 1);
            tokens.add(SPACE_ARROW);
            fragments.addTokensFragment(tokens);
            StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(fragments);
            statements.accept(this);
            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        }

        tokens = new Tokens();
    }

    @Override
    public void visit(SwitchStatement.LabelBlock statement) {
        statement.getLabel().accept(this);
        JavaFragmentFactory.addSpacerAfterSwitchLabel(fragments);
        statement.getStatements().accept(this);
        JavaFragmentFactory.addSpacerAfterSwitchBlock(fragments);
    }

    @Override
    public void visit(SwitchStatement.DefaultLabel statement) {
        tokens = new Tokens();
        tokens.add(DEFAULT);
        tokens.add(TextToken.COLON);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(SwitchStatement.ExpressionLabel statement) {
        tokens = new Tokens();
        tokens.add(CASE);
        tokens.add(TextToken.SPACE);

        statement.getExpression().accept(this);

        if (statement.getGuard() != null) {
            tokens.add(TextToken.SPACE);
            tokens.add(WHEN);
            tokens.add(TextToken.SPACE);
            statement.getGuard().accept(this);
        }

        tokens.add(TextToken.COLON);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(SwitchStatement.MultiLabelsBlock statement) {
        Iterator<SwitchStatement.Label> iterator = statement.getLabels().iterator();

        if (iterator.hasNext()) {
            iterator.next().accept(this);

            while (iterator.hasNext()) {
                JavaFragmentFactory.addSpacerBetweenSwitchLabels(fragments);
                iterator.next().accept(this);
            }
        }

        JavaFragmentFactory.addSpacerAfterSwitchLabel(fragments);
        statement.getStatements().accept(this);
        JavaFragmentFactory.addSpacerAfterSwitchBlock(fragments);
    }

    @Override
    public void visit(SynchronizedStatement statement) {
        tokens = new Tokens();
        tokens.add(SYNCHRONIZED);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getMonitor().accept(this);

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);

        BaseStatement statements = statement.getStatements();

        if (statements == null) {
            tokens.add(TextToken.SPACE);
            tokens.add(TextToken.LEFTRIGHTCURLYBRACKETS);
            fragments.addTokensFragment(tokens);
        } else {
            fragments.addTokensFragment(tokens);
            StartStatementsBlockFragment.Group group = JavaFragmentFactory.addStartStatementsBlock(fragments);
            statements.accept(this);
            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        }
    }

    @Override
    public void visit(ThrowStatement statement) {
        tokens = new Tokens();
        tokens.add(StartBlockToken.START_DECLARATION_OR_STATEMENT_BLOCK);
        tokens.add(THROW);
        tokens.add(TextToken.SPACE);

        statement.getExpression().accept(this);

        tokens.add(TextToken.SEMICOLON);
        tokens.add(EndBlockToken.END_DECLARATION_OR_STATEMENT_BLOCK);
        fragments.addTokensFragment(tokens);
    }

    @Override
    public void visit(TryStatement statement) {
        List<TryStatement.Resource> resources = statement.getResources();
        StartStatementsBlockFragment.Group group;

        if (resources == null) {
            group = JavaFragmentFactory.addStartStatementsTryBlock(fragments);
        } else {
            int size = resources.size();

            assert size > 0;

            tokens = new Tokens();
            tokens.add(TRY);
            if (size == 1) {
                tokens.add(TextToken.SPACE);
            }
            tokens.add(StartBlockToken.START_RESOURCES_BLOCK);

            resources.get(0).accept(this);

            for (int i=1; i<size; i++) {
                tokens.add(TextToken.SEMICOLON_SPACE);
                resources.get(i).accept(this);
            }

            tokens.add(EndBlockToken.END_RESOURCES_BLOCK);
            fragments.addTokensFragment(tokens);
            group = JavaFragmentFactory.addStartStatementsBlock(fragments);
        }

        visitTryStatement(statement, group);
    }

    @Override
    public void visit(TryStatement.Resource resource) {
        Expression expression = resource.getExpression();

        tokens.addLineNumberToken(expression);

        BaseType type = resource.getType();

        if (type == null) {
            // Java 9+: 'try (reader)', an effectively final variable or a final field
            expression.accept(this);
            return;
        }

        type.accept(this);
        tokens.add(TextToken.SPACE);
        // Java 22+: 'try (var _ = ...)'
        tokens.add(newTextToken((resource.getName() == null) ? "_" : resource.getName()));
        tokens.add(TextToken.SPACE_EQUAL_SPACE);
        expression.accept(this);
    }

    protected void visitTryStatement(TryStatement statement, StartStatementsBlockFragment.Group group) {
        int fragmentCount1 = fragments.size(), fragmentCount2 = fragmentCount1;

        statement.getTryStatements().accept(this);

        if (statement.getCatchClauses() != null) {
            for (TryStatement.CatchClause cc : statement.getCatchClauses()) {
                JavaFragmentFactory.addEndStatementsBlock(fragments, group);

                BaseType type = cc.getType();

                tokens = new Tokens();
                tokens.add(CATCH);
                tokens.add(TextToken.SPACE_LEFTROUNDBRACKET);
                type.accept(this);

                if (cc.getOtherTypes() != null) {
                    for (BaseType otherType : cc.getOtherTypes()) {
                        tokens.add(TextToken.VERTICALLINE);
                        otherType.accept(this);
                    }
                }

                tokens.add(TextToken.SPACE);
                tokens.add(newTextToken(getVariableName(cc.getName(), cc.getStatements())));
                tokens.add(TextToken.RIGHTROUNDBRACKET);

                int lineNumber = cc.getLineNumber();

                if (lineNumber == Expression.UNKNOWN_LINE_NUMBER) {
                    fragments.addTokensFragment(tokens);
                } else {
                    tokens.addLineNumberToken(lineNumber);
                    fragments.addTokensFragment(tokens);
                }

                fragmentCount1 = fragments.size();
                JavaFragmentFactory.addStartStatementsBlock(fragments, group);
                fragmentCount2 = fragments.size();
                cc.getStatements().accept(this);
            }
        }

        if (statement.getFinallyStatements() != null) {
            JavaFragmentFactory.addEndStatementsBlock(fragments, group);

            tokens = new Tokens();
            tokens.add(FINALLY);
            fragments.addTokensFragment(tokens);

            fragmentCount1 = fragments.size();
            JavaFragmentFactory.addStartStatementsBlock(fragments, group);
            fragmentCount2 = fragments.size();
            statement.getFinallyStatements().accept(this);
        }

        if (fragmentCount2 == fragments.size()) {
            fragments.subList(fragmentCount1, fragmentCount2).clear();
            tokens.add(TextToken.SPACE);
            tokens.add(TextToken.LEFTRIGHTCURLYBRACKETS);
        } else {
            JavaFragmentFactory.addEndStatementsBlock(fragments, group);
        }
    }

    @Override
    public void visit(TypeDeclarationStatement statement) {
        statement.getTypeDeclaration().accept(this);
        fragments.add(TokensFragment.SEMICOLON);
    }

    @Override
    public void visit(WhileStatement statement) {
        tokens = new Tokens();
        tokens.add(WHILE);
        tokens.add(TextToken.SPACE);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        statement.getCondition().accept(this);

        visitLoopStatements(statement.getStatements());
    }
}
