/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.visitor;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.api.printer.Printer;
import org.jd.core.v1.model.fragment.Fragment;
import org.jd.core.v1.model.javafragment.*;
import org.jd.core.v1.model.javasyntax.declaration.BaseFormalParameter;
import org.jd.core.v1.model.javasyntax.declaration.BodyDeclaration;
import org.jd.core.v1.model.javasyntax.declaration.FormalParameter;
import org.jd.core.v1.model.javasyntax.expression.*;
import org.jd.core.v1.model.javasyntax.reference.TypeAnnotationReferences;
import org.jd.core.v1.model.javasyntax.statement.BaseStatement;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.model.token.*;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.CharacterUtil;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.JavaFragmentFactory;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.StringUtil;
import org.jd.core.v1.service.fragmenter.javasyntaxtojavafragment.util.UnnamedVariableUtil;
import org.jd.core.v1.util.DefaultList;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.FLAG_BOOLEAN;
import static org.jd.core.v1.model.javasyntax.type.PrimitiveType.FLAG_CHAR;

public class ExpressionVisitor extends TypeVisitor {
    public static final KeywordToken CLASS = new KeywordToken("class");
    public static final KeywordToken FALSE = new KeywordToken("false");
    public static final KeywordToken INSTANCEOF = new KeywordToken("instanceof");
    public static final KeywordToken LENGTH = new KeywordToken("length");
    public static final KeywordToken NEW = new KeywordToken("new");
    public static final KeywordToken NULL = new KeywordToken("null");
    public static final KeywordToken THIS = new KeywordToken("this");
    public static final KeywordToken TRUE = new KeywordToken("true");

    protected static final int UNKNOWN_LINE_NUMBER = Printer.UNKNOWN_LINE_NUMBER;

    protected LinkedList<Context> contextStack = new LinkedList<>();
    protected Fragments fragments = new Fragments();
    protected boolean diamondOperatorSupported;
    protected boolean inExpressionFlag = false;
    protected HashSet<String> currentMethodParamNames = new HashSet<>();
    protected String currentTypeName;
    protected HexaExpressionVisitor hexaExpressionVisitor = new HexaExpressionVisitor();
    protected boolean textBlocksSupported;
    protected boolean textBlocksEnabled;
    protected boolean unnamedVariablesSupported;
    protected boolean unnamedVariablesEnabled;
    // Unread formal parameters of the lambda being printed, written '_'
    protected HashSet<String> unnamedFormalParameterNames;

    public ExpressionVisitor(Loader loader, String mainInternalTypeName, int majorVersion, ImportsFragment importsFragment) {
        super(loader, mainInternalTypeName, majorVersion, importsFragment);
        this.diamondOperatorSupported = (majorVersion >= 51); // (majorVersion >= Java 7)
        this.textBlocksSupported = (majorVersion >= 59); // (majorVersion >= Java 15)
        this.unnamedVariablesSupported = (majorVersion >= UnnamedVariableUtil.JAVA_22);
    }

    public DefaultList<Fragment> getFragments() {
        return fragments;
    }

    @Override
    public void visit(ArrayExpression expression) {
        visit(expression, expression.getExpression());
        tokens.add(StartBlockToken.START_ARRAY_BLOCK);
        expression.getIndex().accept(this);
        tokens.add(EndBlockToken.END_ARRAY_BLOCK);
    }

    @Override
    public void visit(BinaryOperatorExpression expression) {
        switch (expression.getOperator()) {
            case "&":
            case "|":
            case "^":
            case "&=":
            case "|=":
            case "^=":
                visitHexa(expression, expression.getLeftExpression());
                tokens.add(TextToken.SPACE);
                tokens.add(newTextToken(expression.getOperator()));
                tokens.add(TextToken.SPACE);
                visitHexa(expression, expression.getRightExpression());
                break;
            default:
                visit(expression, expression.getLeftExpression());
                tokens.add(TextToken.SPACE);
                tokens.add(newTextToken(expression.getOperator()));
                tokens.add(TextToken.SPACE);

                Expression rightExpression = expression.getRightExpression();

                if (needsParenthesesOnRight(expression, rightExpression)) {
                    tokens.add(TextToken.LEFTROUNDBRACKET);
                    rightExpression.accept(this);
                    tokens.add(TextToken.RIGHTROUNDBRACKET);
                } else {
                    visit(expression, rightExpression);
                }
                break;
        }
    }

    /**
     * The binary operators from '*' (5) to '||' (14) are left-associative: 'a - (b - c)' is not 'a - b - c',
     * 's + (i + 1)' is not 's + i + 1'. Same associative operators keep their output: 'a && b && c', 'i + j + k'.
     */
    protected static boolean needsParenthesesOnRight(BinaryOperatorExpression parent, Expression child) {
        int priority = parent.getPriority();

        if ((priority < 5) || (priority > 14) || !child.isBinaryOperatorExpression() || (child.getPriority() != priority)) {
            return false;
        }

        String operator = parent.getOperator();

        if (!operator.equals(child.getOperator())) {
            return true;
        }

        switch (operator) {
            case "&&": case "||": case "&": case "|": case "^":
                return false;
            case "+": case "*":
                // Integer arithmetic is associative, not the string concatenation nor the floating point arithmetic
                return !isIntegral(parent.getType()) || !isIntegral(child.getType());
            default:
                return true;
        }
    }

    protected static boolean isIntegral(Type type) {
        if (type.isPrimitiveType() && (type.getDimension() == 0)) {
            int flags = ((PrimitiveType)type).getFlags();
            return (flags & (PrimitiveType.FLAG_BOOLEAN | PrimitiveType.FLAG_FLOAT | PrimitiveType.FLAG_DOUBLE | PrimitiveType.FLAG_VOID)) == 0;
        }
        return false;
    }

    @Override
    public void visit(BooleanExpression expression) {
        if (expression.isTrue()) {
            tokens.add(TRUE);
        } else {
            tokens.add(FALSE);
        }
    }

    @Override
    public void visit(CastExpression expression) {
        if (expression.isExplicit()) {
            tokens.addLineNumberToken(expression.getLineNumber());
            tokens.add(TextToken.LEFTROUNDBRACKET);

            BaseType type = expression.getType();

            // '(@A String)o'
            visitAnnotatedType(type, expression.getTypeAnnotationReferences(), TypeAnnotationReferences.CAST, 0, "");
            tokens.add(TextToken.RIGHTROUNDBRACKET);
        }

        visit(expression, expression.getExpression());
    }

    @Override public void visit(CommentExpression expression) {
        tokens.add(StartMarkerToken.COMMENT);
        tokens.add(newTextToken(expression.getText()));
        tokens.add(EndMarkerToken.COMMENT);
    }

    @Override
    public void visit(ConstructorInvocationExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(THIS);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        BaseExpression parameters = expression.getParameters();

        if (parameters != null) {
            parameters.accept(this);
        }

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
    }

    @Override
    public void visit(ConstructorReferenceExpression expression) {
        ObjectType ot = expression.getObjectType();

        tokens.addLineNumberToken(expression);
        if (expression.getTypeAnnotationReferences() == null) {
            tokens.add(newTypeReferenceToken(ot, currentInternalTypeName));
        } else {
            // '@A Foo::new'
            visitAnnotatedType(ot, expression.getTypeAnnotationReferences(), TypeAnnotationReferences.CONSTRUCTOR_REFERENCE, 0, "");
        }
        tokens.add(TextToken.COLON_COLON);
        //tokens.add(new ReferenceToken(ReferenceToken.CONSTRUCTOR, ot.getInternalName(), "new", expression.getDescriptor(), currentInternalTypeName));
        visitTypeArgumentsOfReference(expression.getNonWildcardTypeArguments(), expression.getTypeAnnotationReferences(), TypeAnnotationReferences.CONSTRUCTOR_REFERENCE_TYPE_ARGUMENT);
        tokens.add(NEW);
    }

    @Override
    public void visit(DoubleConstantExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(new NumericConstantToken(String.valueOf(expression.getDoubleValue()) + 'D'));
    }

    @Override
    public void visit(EnumConstantReferenceExpression expression) {
        tokens.addLineNumberToken(expression);

        ObjectType type = expression.getObjectType();

        tokens.add(new ReferenceToken(ReferenceToken.FIELD, type.getInternalName(), expression.getName(), type.getDescriptor(), currentInternalTypeName));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(Expressions list) {
        if (list != null) {
            int size = list.size();

            if (size > 0) {
                boolean ief = inExpressionFlag;
                Iterator<Expression> iterator = list.iterator();

                while (size-- > 1) {
                    inExpressionFlag = true;
                    iterator.next().accept(this);

                    if (!tokens.isEmpty()) {
                        tokens.add(TextToken.COMMA_SPACE);
                    }
                }

                inExpressionFlag = false;
                iterator.next().accept(this);
                inExpressionFlag = ief;
            }
        }
    }

    @Override
    public void visit(FieldReferenceExpression expression) {
        if (expression.getExpression() == null) {
            tokens.addLineNumberToken(expression);
            tokens.add(new TextToken(expression.getName()));
        } else {
            tokens.addLineNumberToken(expression.getExpression());

            int delta = tokens.size();

            visit(expression, expression.getExpression());
            delta -= tokens.size();
            tokens.addLineNumberToken(expression);

            if (delta != 0) {
                tokens.add(TextToken.DOT);
            }

            tokens.add(new ReferenceToken(ReferenceToken.FIELD, expression.getInternalTypeName(), expression.getName(), expression.getDescriptor(), currentInternalTypeName));
        }
    }

    @Override
    public void visit(FloatConstantExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(new NumericConstantToken(String.valueOf(expression.getFloatValue()) + 'F'));
    }

    @Override
    public void visit(IntegerConstantExpression expression) {
        tokens.addLineNumberToken(expression);

        PrimitiveType pt = (PrimitiveType)expression.getType();

        switch (pt.getJavaPrimitiveFlags()) {
            case FLAG_CHAR:
                tokens.add(new CharacterConstantToken(CharacterUtil.escapeChar((char)expression.getIntegerValue()), currentInternalTypeName));
                break;
            case FLAG_BOOLEAN:
                tokens.add(new BooleanConstantToken(expression.getIntegerValue() != 0));
                break;
            default:
                tokens.add(new NumericConstantToken(String.valueOf(expression.getIntegerValue())));
                break;
        }
    }

    @Override
    public void visit(InstanceOfExpression expression) {
        expression.getExpression().accept(this);
        tokens.add(TextToken.SPACE);
        tokens.add(INSTANCEOF);
        tokens.add(TextToken.SPACE);

        if (expression.getPattern() != null) {
            expression.getPattern().accept(this);
        } else {
            BaseType type = expression.getInstanceOfType();

            // 'o instanceof @A String'
            qualifyRawInnerTypes = true;
            visitAnnotatedType(type, expression.getTypeAnnotationReferences(), TypeAnnotationReferences.INSTANCEOF, 0, "");
            qualifyRawInnerTypes = false;
        }
    }

    @Override
    public void visit(LambdaFormalParametersExpression expression) {
        BaseFormalParameter parameters = expression.getFormalParameters();

        if (parameters == null) {
            tokens.add(TextToken.LEFTRIGHTROUNDBRACKETS);
        } else {
            int size = parameters.size();

            switch (size) {
                case 0:
                    tokens.add(TextToken.LEFTRIGHTROUNDBRACKETS);
                    break;
                case 1:
                    unnamedFormalParameterNames = getUnnamedParameterNames(parameters, expression.getStatements());
                    parameters.getFirst().accept(this);
                    unnamedFormalParameterNames = null;
                    break;
                default:
                    unnamedFormalParameterNames = getUnnamedParameterNames(parameters, expression.getStatements());
                    tokens.add(TextToken.LEFTROUNDBRACKET);
                    Iterator<FormalParameter> iterator = parameters.iterator();
                    iterator.next().accept(this);

                    while (iterator.hasNext()) {
                        tokens.add(TextToken.COMMA_SPACE);
                        iterator.next().accept(this);
                    }

                    tokens.add(TextToken.RIGHTROUNDBRACKET);
                    unnamedFormalParameterNames = null;
                    break;
            }
        }

        visitLambdaBody(expression.getStatements());
    }

    @Override
    public void visit(LambdaIdentifiersExpression expression) {
        List<String> parameters = expression.getParameterNames();

        if (parameters == null) {
            tokens.add(TextToken.LEFTRIGHTROUNDBRACKETS);
        } else {
            int size = parameters.size();

            switch (size) {
                case 0:
                    tokens.add(TextToken.LEFTRIGHTROUNDBRACKETS);
                    break;
                case 1:
                    tokens.add(newTextToken(getVariableName(parameters.get(0), expression.getStatements())));
                    break;
                default:
                    tokens.add(TextToken.LEFTROUNDBRACKET);
                    tokens.add(newTextToken(getVariableName(parameters.get(0), expression.getStatements())));

                    for (int i = 1; i < size; i++) {
                        tokens.add(TextToken.COMMA_SPACE);
                        tokens.add(newTextToken(getVariableName(parameters.get(i), expression.getStatements())));
                    }
                    tokens.add(TextToken.RIGHTROUNDBRACKET);
                    break;
            }
        }

        visitLambdaBody(expression.getStatements());
    }

    protected void visitLambdaBody(BaseStatement statementList) {
        if (statementList != null) {
            tokens.add(TextToken.SPACE_ARROW_SPACE);

            if (statementList.isLambdaExpressionStatement()) {
                statementList.accept(this);
            } else {
                fragments.addTokensFragment(tokens);

                StartBlockFragment start = JavaFragmentFactory.addStartStatementsInLambdaBlock(fragments);

                tokens = new Tokens();
                statementList.accept(this);

                if (inExpressionFlag) {
                    JavaFragmentFactory.addEndStatementsInLambdaBlockInParameter(fragments, start);
                } else {
                    JavaFragmentFactory.addEndStatementsInLambdaBlock(fragments, start);
                }

                tokens = new Tokens();
            }
        }
    }

    @Override
    public void visit(TypePatternExpression expression) {
        // 'Type name', 'Type _' or '_'
        BaseType type = expression.getType();

        if (type != null) {
            type.accept(this);
            tokens.add(TextToken.SPACE);
        }

        if (expression.getVariable() == null) {
            tokens.add(newTextToken("_"));
        } else {
            expression.getVariable().accept(this);
        }
    }

    @Override
    public void visit(RecordPatternExpression expression) {
        // 'Type(pattern, pattern)'
        BaseType type = expression.getType();
        type.accept(this);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        boolean first = true;

        for (Expression component : expression.getComponents()) {
            if (first) {
                first = false;
            } else {
                tokens.add(TextToken.COMMA_SPACE);
            }
            component.accept(this);
        }

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
    }

    @Override
    public void visit(LengthExpression expression) {
        visit(expression, expression.getExpression());
        tokens.add(TextToken.DOT);
        tokens.add(LENGTH);
    }

    @Override
    public void visit(LocalVariableReferenceExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(newTextToken(expression.getName()));
    }

    @Override
    public void visit(LongConstantExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(new NumericConstantToken(String.valueOf(expression.getLongValue()) + 'L'));
    }

    @Override
    public void visit(MethodInvocationExpression expression) {
        Expression exp = expression.getExpression();
        BaseTypeArgument nonWildcardTypeArguments = expression.getNonWildcardTypeArguments();
        BaseExpression parameters = expression.getParameters();
        boolean dot = false;
        // 'this.<@A String>m()': annotated type arguments need a receiver
        boolean annotatedTypeArguments = (nonWildcardTypeArguments != null) && (expression.getTypeAnnotationReferences() != null);

        if (exp.isThisExpression() && !annotatedTypeArguments) {
            // Nothing to do : do not print 'this.method(...)'
        } else if (exp.isObjectTypeReferenceExpression()) {
            ObjectType ot = exp.getObjectType();

            if (annotatedTypeArguments || !ot.getInternalName().equals(currentInternalTypeName)) {
                visit(expression, exp);
                tokens.addLineNumberToken(expression);
                tokens.add(TextToken.DOT);
                dot = true;
            }
        } else {
            if (exp.isFieldReferenceExpression() || exp.isLocalVariableReferenceExpression()) {
                tokens.addLineNumberToken(expression);
                visit(expression, exp);
            } else {
                visit(expression, exp);
                tokens.addLineNumberToken(expression);
            }

            tokens.add(TextToken.DOT);
            dot = true;
        }

        tokens.addLineNumberToken(expression);

        if ((nonWildcardTypeArguments != null) && dot) {
            tokens.add(TextToken.LEFTANGLEBRACKET);
            if (annotatedTypeArguments) {
                visitAnnotatedTypeArguments(nonWildcardTypeArguments, expression.getTypeAnnotationReferences(), TypeAnnotationReferences.METHOD_INVOCATION_TYPE_ARGUMENT);
            } else {
                nonWildcardTypeArguments.accept(this);
            }
            tokens.add(TextToken.RIGHTANGLEBRACKET);
        }

        tokens.add(new ReferenceToken(ReferenceToken.METHOD, expression.getInternalTypeName(), expression.getName(), expression.getDescriptor(), currentInternalTypeName));
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        if (parameters != null) {
            boolean ief = inExpressionFlag;
            inExpressionFlag = false;
            parameters.accept(this);
            inExpressionFlag = ief;
        }

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
    }

    /** '<@A String>': only the annotated type arguments of references and 'new' are kept (see ByteCodeParser) */
    protected void visitTypeArgumentsOfReference(BaseTypeArgument typeArguments, TypeAnnotationReferences references, int target) {
        if ((typeArguments != null) && (references != null)) {
            tokens.add(TextToken.LEFTANGLEBRACKET);
            visitAnnotatedTypeArguments(typeArguments, references, target);
            tokens.add(TextToken.RIGHTANGLEBRACKET);
        }
    }

    protected void visitAnnotatedTypeArguments(BaseTypeArgument typeArguments, TypeAnnotationReferences references, int target) {
        int size = typeArguments.typeArgumentSize();

        for (int i = 0; i < size; i++) {
            if (i > 0) {
                tokens.add(TextToken.COMMA_SPACE);
            }
            // 'type_argument_index' i
            TypeArgument typeArgument = typeArguments.isTypeArgumentList() ? typeArguments.getTypeArgumentList().get(i) : typeArguments.getTypeArgumentFirst();
            visitAnnotatedType((BaseType)typeArgument, references, target, i, "");
        }
    }

    @Override
    public void visit(MethodReferenceExpression expression) {
        Expression reference = expression.getExpression();

        if ((expression.getTypeAnnotationReferences() != null) && reference.isObjectTypeReferenceExpression()) {
            // '@A Foo::m'
            tokens.addLineNumberToken(reference);
            visitAnnotatedType(reference.getType(), expression.getTypeAnnotationReferences(), TypeAnnotationReferences.METHOD_REFERENCE, 0, "");
        } else {
            reference.accept(this);
        }
        tokens.addLineNumberToken(expression);
        tokens.add(TextToken.COLON_COLON);
        visitTypeArgumentsOfReference(expression.getNonWildcardTypeArguments(), expression.getTypeAnnotationReferences(), TypeAnnotationReferences.METHOD_REFERENCE_TYPE_ARGUMENT);
        tokens.add(new ReferenceToken(ReferenceToken.METHOD, expression.getInternalTypeName(), expression.getName(), expression.getDescriptor(), currentInternalTypeName));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void visit(NewArray expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(NEW);
        tokens.add(TextToken.SPACE);

        BaseType type = expression.getType();

        type.accept(this);

        BaseExpression dimensionExpressionList = expression.getDimensionExpressionList();
        int dimension = expression.getType().getDimension();

        if (dimension > 0) {
            tokens.remove(tokens.size()-1);
        }

        if (dimensionExpressionList != null) {
            if (dimensionExpressionList.isList()) {
                Iterator<Expression> iterator = dimensionExpressionList.iterator();

                while (iterator.hasNext()) {
                    tokens.add(StartBlockToken.START_ARRAY_BLOCK);
                    iterator.next().accept(this);
                    tokens.add(EndBlockToken.END_ARRAY_BLOCK);
                    dimension--;
                }
            } else {
                tokens.add(StartBlockToken.START_ARRAY_BLOCK);
                dimensionExpressionList.accept(this);
                tokens.add(EndBlockToken.END_ARRAY_BLOCK);
                dimension--;
            }
        }

        visitDimension(dimension);
    }

    @Override
    public void visit(NewInitializedArray expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(NEW);
        tokens.add(TextToken.SPACE);

        BaseType type = expression.getType();

        type.accept(this);
        tokens.add(TextToken.SPACE);
        expression.getArrayInitializer().accept(this);
    }

    @Override
    public void visit(NewExpression expression) {
        BodyDeclaration bodyDeclaration = expression.getBodyDeclaration();

        tokens.addLineNumberToken(expression);
        tokens.add(NEW);
        tokens.add(TextToken.SPACE);
        // 'new <@A String>G("x")'
        visitTypeArgumentsOfReference(expression.getNonWildcardTypeArguments(), expression.getTypeAnnotationReferences(), TypeAnnotationReferences.CONSTRUCTOR_INVOCATION_TYPE_ARGUMENT);

        ObjectType objectType = expression.getObjectType();

        if ((objectType.getTypeArguments() != null) && (bodyDeclaration == null) && diamondOperatorSupported) {
            objectType = objectType.createType(DiamondTypeArgument.DIAMOND);
        }

        BaseType type = objectType;

        // 'new @A Foo()'
        visitAnnotatedType(type, expression.getTypeAnnotationReferences(), TypeAnnotationReferences.NEW, 0, "");
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        BaseExpression parameters = expression.getParameters();
        if (parameters != null) {
            parameters.accept(this);
        }

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);

        if (bodyDeclaration != null) {
            fragments.addTokensFragment(tokens);

            StartBodyFragment start = JavaFragmentFactory.addStartTypeBody(fragments);
            ObjectType ot = expression.getObjectType();

            storeContext();
            currentInternalTypeName = bodyDeclaration.getInternalTypeName();
            currentTypeName = ot.getName();
            bodyDeclaration.accept(this);

            if (! tokens.isEmpty()) {
                tokens = new Tokens();
            }

            restoreContext();

            if (inExpressionFlag) {
                JavaFragmentFactory.addEndSubTypeBodyInParameter(fragments, start);
            } else {
                JavaFragmentFactory.addEndSubTypeBody(fragments, start);
            }

            tokens = new Tokens();
        }
    }

    @Override
    public void visit(NullExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(NULL);
    }

    @Override
    public void visit(ObjectTypeReferenceExpression expression) {
        if (expression.isExplicit()) {
            tokens.addLineNumberToken(expression);

            BaseType type = expression.getType();

            type.accept(this);
        }
    }

    @Override
    public void visit(ParenthesesExpression expression) {
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);
        expression.getExpression().accept(this);
        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
    }

    @Override
    public void visit(PostOperatorExpression expression) {
        visit(expression, expression.getExpression());
        tokens.add(newTextToken(expression.getOperator()));
    }

    @Override
    public void visit(PreOperatorExpression expression) {
        tokens.addLineNumberToken(expression.getExpression());
        tokens.add(newTextToken(expression.getOperator()));
        visit(expression, expression.getExpression());
    }

    @Override
    public void visit(StringConstantExpression expression) {
        tokens.addLineNumberToken(expression);

        String textBlock = textBlocksEnabled ? StringUtil.toTextBlock(expression.getStringValue()) : null;

        if (textBlock == null) {
            tokens.add(new StringConstantToken(StringUtil.escapeString(expression.getStringValue()), currentInternalTypeName));
        } else {
            // One token: the lines of the text block are not lines of the layout. The writer adds the outer '"'
            tokens.add(new StringConstantToken(textBlock.substring(1, textBlock.length() - 1), currentInternalTypeName));
        }
    }

    /**
     * Java 15+ text blocks for the strings of several lines ('textBlocks' configuration, off by default: the line
     * breaks of a text block are not known by the layout, the following lines of the output are shifted)
     */
    public void setUnnamedVariablesEnabled(boolean enabled) {
        this.unnamedVariablesEnabled = enabled && unnamedVariablesSupported;
    }

    protected HashSet<String> getUnnamedParameterNames(BaseFormalParameter parameters, BaseStatement body) {
        if (!unnamedVariablesEnabled) {
            return null;
        }

        HashSet<String> names = new HashSet<>();

        for (FormalParameter parameter : parameters) {
            if (!UnnamedVariableUtil.isReferenced(body, parameter.getName())) {
                names.add(parameter.getName());
            }
        }

        return names;
    }

    /** @return '_' for a variable not read in its scope ('unnamedVariables' configuration) */
    protected String getVariableName(String name, BaseStatement scope) {
        return (unnamedVariablesEnabled && !UnnamedVariableUtil.isReferenced(scope, name)) ? UnnamedVariableUtil.UNNAMED : name;
    }

    public void setTextBlocksEnabled(boolean enabled) {
        this.textBlocksEnabled = enabled && textBlocksSupported;
    }

    @Override
    public void visit(SuperConstructorInvocationExpression expression) {
        tokens.addLineNumberToken(expression);
        tokens.add(SUPER);
        tokens.add(StartBlockToken.START_PARAMETERS_BLOCK);

        BaseExpression parameters = expression.getParameters();

        if (parameters != null) {
            parameters.accept(this);
        }

        tokens.add(EndBlockToken.END_PARAMETERS_BLOCK);
    }

    @Override
    public void visit(SuperExpression expression) {
        tokens.addLineNumberToken(expression);
        if (expression.getQualifier() != null) {
            ((BaseType)expression.getQualifier()).accept(this);
            tokens.add(TextToken.DOT);
        }
        tokens.add(SUPER);
    }

    @Override
    public void visit(TernaryOperatorExpression expression) {
        tokens.addLineNumberToken(expression.getCondition());

        if (expression.getTrueExpression().isBooleanExpression() && expression.getFalseExpression().isBooleanExpression()) {
            BooleanExpression be1 = (BooleanExpression)expression.getTrueExpression();
            BooleanExpression be2 = (BooleanExpression)expression.getFalseExpression();

            if (be1.isTrue() && be2.isFalse()) {
                printTernaryOperatorExpression(expression.getCondition());
                return;
            }

            if (be1.isFalse() && be2.isTrue()) {
                tokens.add(TextToken.EXCLAMATION);
                printTernaryOperatorExpression(expression.getCondition());
                return;
            }
        }

        printTernaryOperatorExpression(expression.getCondition());
        tokens.add(TextToken.SPACE_QUESTION_SPACE);
        printTernaryOperatorExpression(expression.getTrueExpression());
        tokens.add(TextToken.SPACE_COLON_SPACE);
        printTernaryOperatorExpression(expression.getFalseExpression());
    }

    protected void printTernaryOperatorExpression(Expression expression) {
        if (expression.getPriority() > 3) {
            tokens.add(TextToken.LEFTROUNDBRACKET);
            expression.accept(this);
            tokens.add(TextToken.RIGHTROUNDBRACKET);
        } else {
            expression.accept(this);
        }
    }

    @Override
    public void visit(ThisExpression expression) {
        if (expression.isExplicit()) {
            tokens.addLineNumberToken(expression);
            tokens.add(THIS);
        }
    }

    @Override
    public void visit(TypeReferenceDotClassExpression expression) {
        tokens.addLineNumberToken(expression);

        BaseType type = expression.getTypeDotClass();

        type.accept(this);
        tokens.add(TextToken.DOT);
        tokens.add(CLASS);
    }

    protected void storeContext() {
        contextStack.add(new Context(currentInternalTypeName, currentTypeName, currentMethodParamNames));
    }

    protected void restoreContext() {
        Context currentContext = contextStack.removeLast();
        currentInternalTypeName = currentContext.currentInternalTypeName;
        currentTypeName = currentContext.currentTypeName;
        currentMethodParamNames = currentContext.currentMethodParamNames;
    }

    protected void visit(Expression parent, Expression child) {
        if ((parent.getPriority() < child.getPriority()) || ((parent.getPriority() == 14) && (child.getPriority() == 13))) {
            tokens.add(TextToken.LEFTROUNDBRACKET);
            child.accept(this);
            tokens.add(TextToken.RIGHTROUNDBRACKET);
        } else {
            child.accept(this);
        }
    }

    protected void visitHexa(Expression parent, Expression child) {
        if ((parent.getPriority() < child.getPriority()) || ((parent.getPriority() == 14) && (child.getPriority() == 13))) {
            tokens.add(TextToken.LEFTROUNDBRACKET);
            child.accept(hexaExpressionVisitor);
            tokens.add(TextToken.RIGHTROUNDBRACKET);
        } else {
            child.accept(hexaExpressionVisitor);
        }
    }

    protected static class Context {
        public final String currentInternalTypeName;
        public final String currentTypeName;
        public final HashSet<String> currentMethodParamNames;

        public Context(String currentInternalTypeName, String currentTypeName, HashSet<String> currentMethodParamNames) {
            this.currentInternalTypeName = currentInternalTypeName;
            this.currentTypeName = currentTypeName;
            this.currentMethodParamNames = new HashSet<>(currentMethodParamNames);
        }
    }

    protected static class Fragments extends DefaultList<Fragment> {
        public void addTokensFragment(Tokens tokens) {
            if (! tokens.isEmpty()) {
                if (tokens.getCurrentLineNumber() == UNKNOWN_LINE_NUMBER) {
                    super.add(new TokensFragment(tokens));
                } else {
                    super.add(new LineNumberTokensFragment(tokens));
                }
            }
        }
    }

    protected class HexaExpressionVisitor implements org.jd.core.v1.model.javasyntax.expression.ExpressionVisitor {
        @Override
        public void visit(IntegerConstantExpression expression) {
            tokens.addLineNumberToken(expression);

            PrimitiveType pt = (PrimitiveType)expression.getType();

            switch (pt.getJavaPrimitiveFlags()) {
                case FLAG_BOOLEAN:
                    tokens.add(new BooleanConstantToken(expression.getIntegerValue() == 1));
                    break;
                default:
                    tokens.add(new NumericConstantToken("0x" + Integer.toHexString(expression.getIntegerValue()).toUpperCase()));
                    break;
            }
        }

        @Override
        public void visit(LongConstantExpression expression) {
            tokens.addLineNumberToken(expression);
            tokens.add(new NumericConstantToken("0x" + Long.toHexString(expression.getLongValue()).toUpperCase() + 'L'));
        }

        @Override public void visit(ArrayExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(BinaryOperatorExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(BooleanExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(CastExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(CommentExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(ConstructorInvocationExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(ConstructorReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(DoubleConstantExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(EnumConstantReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(Expressions expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(FieldReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(FloatConstantExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(InstanceOfExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(LambdaFormalParametersExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(LambdaIdentifiersExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(LengthExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(LocalVariableReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(MethodInvocationExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(MethodReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(NewArray expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(NewExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(NewInitializedArray expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(NoExpression expression) {}
        @Override public void visit(NullExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(ObjectTypeReferenceExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(ParenthesesExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(PostOperatorExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(PreOperatorExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(StringConstantExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(SuperConstructorInvocationExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(SuperExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(SwitchExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(TypePatternExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(RecordPatternExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(TernaryOperatorExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(ThisExpression expression) { ExpressionVisitor.this.visit(expression); }
        @Override public void visit(TypeReferenceDotClassExpression expression) { ExpressionVisitor.this.visit(expression); }
    }
}
