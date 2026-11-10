package sjc.type.checker;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.WhileStatement;

import sjc.annotation.NonNull;
import sjc.annotation.NonNullElements;
import sjc.annotation.ReadOnly;
import sjc.annotation.ReadOnlyElements;
import sjc.symboltable.SymbolTable;
import sjc.type.ArrayType;
import sjc.type.BaseType;
import sjc.type.ClassType;
import sjc.type.NonPrimitiveType;
import sjc.type.NullType;
import sjc.type.Type;
import sjc.type.TypeFactory;
import sjc.util.Pair;

/**
 * This class is used to type check a StaticJava {@link CompilationUnit} with a
 * given {@link SymbolTable}.
 *
 */
public class TypeChecker {
  public static class Error extends RuntimeException {
    private static final long serialVersionUID = 5322515711212690043L;
    public final @NonNull @ReadOnly ASTNode node;
    public Error(@NonNull final ASTNode node, @NonNull final String message) {
      super(message);
      this.node = node;
    }
  }

  /** Visitor for type checking an ExtendedStaticJava compilation unit. */
  protected static class Visitor extends ASTVisitor {
    protected Map<ASTNode, Object> symbolMap;
    public Map<ASTNode, Type> resultTypeMap = new HashMap<>();
    public Map<Object, Pair<Type, List<Type>>> resultMethodTypeMap =
        new HashMap<>();
    protected TypeFactory tf;
    protected ArrayType stringArrayType;
    protected String className;
    protected Type methodReturnType, resultingType;
    protected @NonNullElements
    @ReadOnlyElements
    Map<String, TypeDeclaration> classMap;
    protected @NonNullElements
    @ReadOnlyElements
    Map<Pair<String, String>, FieldDeclaration> fieldMap;
    protected Visitor(final TypeFactory tf, final SymbolTable st) {
      this.tf = tf;
      this.symbolMap = st.symbolMap;
      this.stringArrayType =
          tf.getArrayType(tf.getClassType("java.lang.String"));
      this.classMap = st.classMap;
      this.fieldMap = st.fieldMap;
      for (final Pair<String, String> p : st.fieldMap.keySet()) {
        final FieldDeclaration fd = st.fieldMap.get(p);
        final Type t = convertType(fd, fd.getType());
        final ClassType ct = tf.getClassType(p.first);
        ct.fieldTypeMap.put(p.second, t);
      }
    }
    protected Type getResult() {
      Type t = resultingType;
      resultingType = null;
      return t;
    }
    protected void setResult(Expression n, Type t) {
      resultTypeMap.put(n, t);
      resultingType = t;
    }
    protected Type convertType(ASTNode n, Class c) {
      if (c == void.class)
        return tf.Void;
      if (c == boolean.class)
        return tf.Boolean;
      if (c == int.class)
        return tf.Int;
      if (c == String[].class)
        return stringArrayType;
      if (!c.isArray())
        return tf.getClassType(c.getName());
      return tf.getArrayType((BaseType) convertType(n, c.getComponentType()));
    }
    protected Type convertType(ASTNode n, org.eclipse.jdt.core.dom.Type t) {
      if (t instanceof PrimitiveType) {
        PrimitiveType.Code c = ((PrimitiveType) t).getPrimitiveTypeCode();
        if (c == PrimitiveType.BOOLEAN)
          return tf.Boolean;
        if (c == PrimitiveType.INT)
          return tf.Int;
        if (c == PrimitiveType.VOID)
          return tf.Void;
      }
      if (t instanceof SimpleType) {
        String s = ((SimpleType) t).getName().getFullyQualifiedName();
        if (classMap.containsKey(s))
          return tf.getClassType(s);
        if ("String".equals(s) || "java.lang.String".equals(s))
          return stringArrayType.baseType;
      }
      if (t instanceof org.eclipse.jdt.core.dom.ArrayType)
        return tf.getArrayType((BaseType) convertType(
            n, ((org.eclipse.jdt.core.dom.ArrayType) t).getElementType()));
      throw new Error(n, "Unexpected Type: '" + t + "'");
    }
    protected void dispose() {
      symbolMap = null;
      resultTypeMap = null;
      resultMethodTypeMap = null;
      tf = null;
      stringArrayType = null;
      className = null;
      methodReturnType = resultingType = null;
      classMap = null;
      fieldMap = null;
    }
    protected Method resolveMethod(
        MethodInvocation n, String c, String m, Type[] a) {
      try {
        Class[] p = new Class[a.length];
        for (int i = 0; i < a.length; i++) p[i] = convertClass(n, a[i]);
        Method r = Class.forName(c).getMethod(m, p);
        symbolMap.put(n, r);
        return r;
      } catch (Exception e) {
        throw new Error(n, "Unresolvable method \"" + c + "." + m + "\"");
      }
    }
    protected Class convertClass(ASTNode n, Type t) {
      if (t == tf.Void)
        return void.class;
      if (t == tf.Boolean)
        return boolean.class;
      if (t == tf.Int)
        return int.class;
      if (t == stringArrayType)
        return String[].class;
      if (t instanceof ClassType)
        try {
          return Class.forName(((ClassType) t).name);
        } catch (Exception e) {
        }
      throw new Error(n, "Unresolvable Type: '" + t + "'");
    }
    protected void typeCheckMethodInvocation(
        MethodInvocation n, String c, String m, Type[] a, Method d) {
      if (a.length != d.getParameterTypes().length)
        throw new Error(n,
            "Wrong number of arguments to invoke method \"" + m + "\" in \"" + n
                + "\"");
      List<Type> p = new ArrayList<>();
      for (int i = 0; i < a.length; i++) {
        Type t = convertType(n, d.getParameterTypes()[i]);
        if (!((a[i] instanceof NullType) && (t instanceof NonPrimitiveType))
            && t != a[i])
          throw new Error(
              n, "Type mismatch the " + i + " argument in \"" + n + "\"");
        p.add(t);
      }
      Type r = convertType(n, d.getReturnType());
      if (!resultMethodTypeMap.containsKey(d))
        resultMethodTypeMap.put(d, new Pair<Type, List<Type>>(r, p));
      setResult(n, r);
    }
    protected void typeCheckMethodInvocation(
        MethodInvocation n, String c, String m, Type[] a, MethodDeclaration d) {
      if (a.length != d.parameters().size())
        throw new Error(n,
            "Wrong number of arguments to invoke method \"" + m + "\" in \"" + n
                + "\"");
      for (int i = 0; i < a.length; i++)
        if (!((a[i] instanceof NullType)
                && (convertType(n, ((SingleVariableDeclaration) d.parameters()
                    .get(i)).getType()) instanceof NonPrimitiveType))
            && convertType(n,
                ((SingleVariableDeclaration) d.parameters().get(i)).getType())
            != a[i])
          throw new Error(
              n, "Type mismatch the " + i + " argument in \"" + n + "\"");
      setResult(n, convertType(n, d.getReturnType2()));
    }
    @Override
    public boolean visit(BooleanLiteral n) {
      setResult(n, tf.Boolean);
      return false;
    }
    @Override
    public boolean visit(NumberLiteral n) {
      setResult(n, tf.Int);
      return false;
    }
    @Override
    public boolean visit(ParenthesizedExpression n) {
      n.getExpression().accept(this);
      setResult(n, getResult());
      return false;
    }
    @Override
    public boolean visit(FieldDeclaration n) {
      resultTypeMap.put(n, convertType(n, n.getType()));
      return false;
    }
    @Override
    public boolean visit(MethodDeclaration n) {
      final Type previousReturnType = methodReturnType;
      final Type returnType = convertType(n, n.getReturnType2());
      final List<Type> parameterTypes = new ArrayList<>();
      for (final Object o : n.parameters()) {
        final SingleVariableDeclaration parameter = (SingleVariableDeclaration) o;
        final Type parameterType = convertType(parameter, parameter.getType());
        parameterTypes.add(parameterType);
        resultTypeMap.put(parameter, parameterType);
      }
      resultMethodTypeMap.put(n, new Pair<Type, List<Type>>(returnType, parameterTypes));
      methodReturnType = returnType;
      if (n.getBody() != null)
        n.getBody().accept(this);
      methodReturnType = previousReturnType;
      return false;
    }
    @Override
    public boolean visit(MethodInvocation n) {
      final Type[] argumentTypes = new Type[n.arguments().size()];
      for (int i = 0; i < argumentTypes.length; i++) {
        ((Expression) n.arguments().get(i)).accept(this);
        argumentTypes[i] = getResult();
      }

      final Expression receiver = n.getExpression();
      final Object declaration = symbolMap.get(n);
      if (declaration instanceof MethodDeclaration) {
        typeCheckMethodInvocation(n, className, n.getName().getIdentifier(), argumentTypes, (MethodDeclaration) declaration);
      } else {
        String owner;
        if (receiver == null) {
          owner = className;
        } else if (receiver instanceof SimpleName && !symbolMap.containsKey(receiver)) {
          owner = ((SimpleName) receiver).getIdentifier();
        } else {
          receiver.accept(this);
          final Type receiverType = getResult();
          if (!(receiverType instanceof ClassType))
            throw new Error(n, "Unresolvable method \"" + receiverType + "." + n.getName().getIdentifier() + "\"");
          owner = ((ClassType) receiverType).name;
        }
        final Method method = resolveMethod(n, owner, n.getName().getIdentifier(), argumentTypes);
        typeCheckMethodInvocation(n, owner, n.getName().getIdentifier(), argumentTypes, method);
      }
      return false;
    }
    @Override
    public boolean visit(SimpleName n) {
      final Object declaration = symbolMap.get(n);
      if (declaration instanceof VariableDeclarationStatement) {
        setResult(n, convertType((ASTNode) declaration, ((VariableDeclarationStatement) declaration).getType()));
      } else if (declaration instanceof SingleVariableDeclaration) {
        final SingleVariableDeclaration variable = (SingleVariableDeclaration) declaration;
        setResult(n, convertType(variable, variable.getType()));
      } else {
        throw new Error(n, "Unresolvable symbol \"" + n + "\"");
      }
      return false;
    }
    @Override
    public boolean visit(TypeDeclaration n) {
      final String previousClassName = className;
      className = n.getName().getIdentifier();
      resultTypeMap.put(n, tf.getClassType(className));
      for (final Object o : n.bodyDeclarations())
        ((ASTNode) o).accept(this);
      className = previousClassName;
      return false;
    }
    @Override
    public boolean visit(VariableDeclarationStatement n) {
      final Type declaredType = convertType(n, n.getType());
      resultTypeMap.put(n, declaredType);
      for (final Object o : n.fragments()) {
        final VariableDeclarationFragment fragment = (VariableDeclarationFragment) o;
        if (fragment.getInitializer() != null) {
          fragment.getInitializer().accept(this);
          final Type initializerType = getResult();
          if (!isAssignable(declaredType, initializerType))
            throw new Error(fragment.getInitializer(), "Type mismatch in \"" + fragment.getName() + "=" + fragment.getInitializer() + "\": " + declaredType + " = " + initializerType);
        }
      }
      return false;
    }
    @Override
    public boolean visit(Assignment n) {
      n.getLeftHandSide().accept(this);
      final Type leftType = getResult();
      n.getRightHandSide().accept(this);
      final Type rightType = getResult();
      if (!isAssignable(leftType, rightType))
        throw new Error(n, "Type mismatch in \"" + n + "\": " + leftType + " = " + rightType);
      return false;
    }
    @Override
    public boolean visit(ExpressionStatement n) {
      n.getExpression().accept(this);
      return false;
    }
    @Override
    public boolean visit(InfixExpression n) {
      final InfixExpression.Operator operator = n.getOperator();
      n.getLeftOperand().accept(this);
      Type leftType = getResult();
      n.getRightOperand().accept(this);
      Type rightType = getResult();

      if (operator == InfixExpression.Operator.CONDITIONAL_AND || operator == InfixExpression.Operator.CONDITIONAL_OR) {
        if (leftType != tf.Boolean || rightType != tf.Boolean)
          throw new Error(n, "Expecting a boolean type expression as the " + (leftType != tf.Boolean ? "left" : "right") + "-hand operand of \"" + operator + "\" in \"" + n + "\"");
        for (final Object o : n.extendedOperands()) {
          ((Expression) o).accept(this);
          if (getResult() != tf.Boolean)
            throw new Error(n, "Expecting a boolean type expression as the right-hand operand of \"" + operator + "\" in \"" + n + "\"");
        }
        setResult(n, tf.Boolean);
      } else if (operator == InfixExpression.Operator.EQUALS || operator == InfixExpression.Operator.NOT_EQUALS) {
        if (!isAssignable(leftType, rightType) && !isAssignable(rightType, leftType))
          throw new Error(n, "Type mismatch in \"" + n + "\": " + leftType + " " + operator + " " + rightType);
        for (final Object o : n.extendedOperands()) {
          ((Expression) o).accept(this);
          final Type extendedType = getResult();
          if (!isAssignable(leftType, extendedType) && !isAssignable(extendedType, leftType))
            throw new Error(n, "Type mismatch in \"" + n + "\": " + leftType + " " + operator + " " + extendedType);
          leftType = rightType;
          rightType = extendedType;
        }
        setResult(n, tf.Boolean);
      } else if (operator == InfixExpression.Operator.LESS
          || operator == InfixExpression.Operator.LESS_EQUALS
          || operator == InfixExpression.Operator.GREATER
          || operator == InfixExpression.Operator.GREATER_EQUALS) {
        if (leftType != tf.Int || rightType != tf.Int)
          throw new Error(n, "Expecting int type expressions for \"" + operator + "\" in \"" + n + "\"");
        for (final Object o : n.extendedOperands()) {
          ((Expression) o).accept(this);
          if (getResult() != tf.Int)
            throw new Error(n, "Expecting int type expressions for \"" + operator + "\" in \"" + n + "\"");
        }
        setResult(n, tf.Boolean);
      } else {
        final boolean booleanOperator = operator == InfixExpression.Operator.AND || operator == InfixExpression.Operator.OR || operator == InfixExpression.Operator.XOR;
        final Type expected = booleanOperator ? tf.Boolean : tf.Int;
        if (leftType != expected || rightType != expected)
          throw new Error(n, "Expecting " + (expected == tf.Int ? "an " : "a ") + expected + " type expression as the " + (leftType != expected ? "left" : "right") + "-hand operand of \"" + operator + "\" in \"" + n + "\"");
        for (final Object o : n.extendedOperands()) {
          ((Expression) o).accept(this);
          if (getResult() != expected)
            throw new Error(n, "Expecting " + (expected == tf.Int ? "an " : "a ") + expected + " type expression as the right-hand operand of \"" + operator + "\" in \"" + n + "\"");
        }
        setResult(n, expected);
      }
      return false;
    }
    @Override
    public boolean visit(PrefixExpression n) {
      n.getOperand().accept(this);
      final Type operandType = getResult();
      final PrefixExpression.Operator operator = n.getOperator();
      final Type expected = (operator == PrefixExpression.Operator.NOT) ? tf.Boolean : tf.Int;
      if (operandType != expected)
        throw new Error(n, "Expecting " + (expected == tf.Int ? "an int" : "a boolean") + " type expression as the operand of \"" + operator + "\" in \"" + n + "\"");
      setResult(n, expected);
      return false;
    }
    @Override
    public boolean visit(ReturnStatement n) {
      if (n.getExpression() == null) {
        if (methodReturnType != tf.Void)
          throw new Error(n, "Expecting " + methodReturnType + " return expression in \"" + n + "\"");
      } else {
        if (methodReturnType == tf.Void)
          throw new Error(n, "Unexpected return's expression in \"" + n + "\"");
        n.getExpression().accept(this);
        final Type actualType = getResult();
        if (!isAssignable(methodReturnType, actualType))
          throw new Error(n, "Expecting " + methodReturnType + " return expression in \"" + n + "\"");
      }
      return false;
    }
    @Override
    public boolean visit(IfStatement n) {
      n.getExpression().accept(this);
      if (getResult() != tf.Boolean)
        throw new Error(n, "Expecting a boolean type expression as the condition of an if-statement: \"" + n.getExpression() + "\"");
      n.getThenStatement().accept(this);
      if (n.getElseStatement() != null)
        n.getElseStatement().accept(this);
      return false;
    }
    @Override
    public boolean visit(WhileStatement n) {
      n.getExpression().accept(this);
      if (getResult() != tf.Boolean)
        throw new Error(n, "Expecting a boolean type expression as the condition of a while-statement: \"" + n.getExpression() + "\"");
      n.getBody().accept(this);
      return false;
    }

    @Override
    public boolean visit(final ArrayAccess node) {
      node.getArray().accept(this);
      final Type arrayType = getResult();
      node.getIndex().accept(this);
      if (getResult() != tf.Int)
        throw new Error(node, "Expecting an int type for the index of '" + node + "\"");
      if (!(arrayType instanceof ArrayType))
        throw new Error(node, "Expecting an array type for \"" + node.getArray() + "\": " + arrayType);
      setResult(node, ((ArrayType) arrayType).baseType);
      return false;
    }

    @Override
    public boolean visit(final ArrayCreation node) {
      final Type elementType = convertType(node, node.getType().getElementType());
      final ArrayType resultType = tf.getArrayType((BaseType) elementType);
      for (final Object o : node.dimensions()) {
        final Expression dimension = (Expression) o;
        dimension.accept(this);
        if (getResult() != tf.Int)
          throw new Error(node, "Expecting an int type expression for the array length in '" + node + "'");
      }
      if (node.getInitializer() != null) {
        final List expressions = node.getInitializer().expressions();
        for (int i = 0; i < expressions.size(); i++) {
          final Expression expression = (Expression) expressions.get(i);
          expression.accept(this);
          final Type initializerType = getResult();
          if (!isAssignable(elementType, initializerType))
            throw new Error(node, "Type mismatch in array initializer element " + i + " in '" + node + "': " + elementType + " : " + initializerType);
        }
      }
      setResult(node, resultType);
      return false;
    }

    @Override
    public boolean visit(final ClassInstanceCreation node) {
      final Type type = convertType(node, node.getType());
      if (!(type instanceof ClassType) || !classMap.containsKey(((ClassType) type).name))
        throw new Error(node, "Unresolvable Type: '" + type + "'");
      setResult(node, type);
      return false;
    }

    @Override
    public boolean visit(final ConditionalExpression node) {
      node.getExpression().accept(this);
      if (getResult() != tf.Boolean)
        throw new Error(node, "Expecting a boolean type expression as the condition in '" + node + "\"");
      node.getThenExpression().accept(this);
      final Type thenType = getResult();
      node.getElseExpression().accept(this);
      final Type elseType = getResult();
      if (isAssignable(thenType, elseType))
        setResult(node, thenType);
      else if (isAssignable(elseType, thenType))
        setResult(node, elseType);
      else
        throw new Error(node, "Type mismatch for conditional expression '" + node + "\": " + thenType + " : " + elseType);
      return false;
    }

    @Override
    public boolean visit(final DoStatement node) {
      node.getBody().accept(this);
      node.getExpression().accept(this);
      if (getResult() != tf.Boolean)
        throw new Error(node, "Expecting a boolean type expression as the condition of a while-statement: \"" + node.getExpression() + "\"");
      return false;
    }

    @Override
    public boolean visit(final FieldAccess node) {
      node.getExpression().accept(this);
      final Type receiverType = getResult();
      if (!(receiverType instanceof ClassType))
        throw new Error(node, "Unresolvable field \"" + node.getName() + "\" in \"" + receiverType + "\"");
      final Type fieldType = ((ClassType) receiverType).fieldTypeMap.get(node.getName().getIdentifier());
      if (fieldType == null)
        throw new Error(node, "Unresolvable field \"" + node.getName() + "\" in \"" + receiverType + "\"");
      setResult(node, fieldType);
      return false;
    }

    @Override
    public boolean visit(final ForStatement node) {
      for (final Object o : node.initializers())
        ((Expression) o).accept(this);
      if (node.getExpression() != null) {
        node.getExpression().accept(this);
        if (getResult() != tf.Boolean)
          throw new Error(node, "Expecting a boolean type expression as the condition of a for-statement: \"" + node.getExpression() + "\"");
      }
      node.getBody().accept(this);
      for (final Object o : node.updaters())
        ((Expression) o).accept(this);
      return false;
    }

    @Override
    public boolean visit(final NullLiteral node) {
      setResult(node, tf.Null);
      return false;
    }

    @Override
    public boolean visit(final PostfixExpression node) {
      node.getOperand().accept(this);
      final Type t = getResult();
      if (t != this.tf.Int) {
        throw new Error(node,
            "Expecting an int type expression as the operand of \'"
                + node.getOperator() + "\': \"" + node.getOperand() + "\"");
      }
      // no need to set the type result for postfix expressions since
      // postfix expressions in StaticJava are statements,
      // i.e., they are evaluated for their side-effects.
      return false;
    }

    protected boolean isAssignable(final Type target, final Type source) {
      return target == source || (source instanceof NullType && target instanceof NonPrimitiveType);
    }

  }

  /**
   * Type checks an ExtendedStaticJava {@link CompilationUnit} with the given
   * {@link SymbolTable} and the given {@link TypeFactory}. It also
   * resolves {@link MethodInvocation} of library call (and put its mapping in
   * the {@link SymbolTable}).
   * 
   * @param tf
   *          The {@link TypeFactory}.
   * @param cu
   *          The StaticJava {@link CompilationUnit}.
   * @param symbolTable
   *          The {@link SymbolTable} of the {@link CompilationUnit}
   * @return The {@link TypeTable}.
   * @throws Error
   *           If the type checker encounter type error in the
   *           {@link CompilationUnit}.
   */
  public static @NonNull
  TypeTable check(@NonNull final TypeFactory tf,
      @NonNull final CompilationUnit cu,
      @NonNull final SymbolTable symbolTable) throws Error {
    assert (tf != null) && (cu != null) && (symbolTable != null);

    final Visitor v = new Visitor(tf, symbolTable);
    cu.accept(v);
    final TypeTable result = new TypeTable(v.resultTypeMap,
        v.resultMethodTypeMap);
    v.dispose();
    return result;
  }

  /**
   * Declared as protected to disallow creation of this object outside from the
   * methods of this class.
   */
  protected TypeChecker() {
  }
}
