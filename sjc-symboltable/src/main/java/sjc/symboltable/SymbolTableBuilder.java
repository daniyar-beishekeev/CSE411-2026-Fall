package sjc.symboltable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.Modifier.ModifierKeyword;

import sjc.annotation.NonNull;
import sjc.annotation.NonNullElements;
import sjc.annotation.ReadOnly;
import sjc.util.Pair;

/**
 * This class is used to build symbol table for an ExtendedStaticJava
 * {@link CompilationUnit}. Note that the algorithm assumes that the JDT AST
 * tree was built using the {@link esjc.ast.ExtendedStaticJavaASTBuilder}. That is, it
 * assumes certain structures on the AST, e.g., a class does not have an
 * instance method.
 *
 */
public class SymbolTableBuilder {
  /** Signals an error while building an extended symbol table. */
  public static class Error extends RuntimeException {
    private static final long serialVersionUID = 7425398360365890486L;

    public final @NonNull
    @ReadOnly
    ASTNode node;

    public Error(@NonNull final ASTNode node, @NonNull final String msg) {
      super(msg);
      assert (msg != null) && (node != null);
      this.node = node;
    }
  }

  /**
   * The visitor for {@link ASTNode} to resolve symbols.
   *
   */
  private static class Visitor extends ASTVisitor {
    public @NonNull
    Map<ASTNode, Object> result = new HashMap<>();

    protected @NonNull
    Map<String, ASTNode> nameMap = new HashMap<>();

    protected @NonNull
    Map<String, MethodDeclaration> methodMap = new HashMap<>();

    protected @NonNull
    Set<String> localNames = new HashSet<>();

    protected String className;

    private @NonNullElements
    Map<String, TypeDeclaration> classMap = new HashMap<>();

    private @NonNullElements
    Map<Pair<String, String>, FieldDeclaration> fieldMap = new HashMap<>();

    /**
     * Unlink references and clear maps/collections.
     */
    protected void dispose() {
      this.result = null;
      if (this.nameMap != null) {
        this.nameMap.clear();
        this.nameMap = null;
      }
      if (this.methodMap != null) {
        this.methodMap.clear();
        this.methodMap = null;
      }
      this.className = null;
    }

    /**
     * Determines whether a {@link List} of {@link Modifier}s has a public
     * modifier.
     * 
     * @param modifiers
     *        the list of modifiers
     *
     * @return True, if the {@link List} contains a public modifier.
     */
    private boolean hasPublicModifier(final List modifiers) {
      for (final Object o : modifiers) {
        if ((o instanceof Modifier)
            && (((Modifier) o).getKeyword() == ModifierKeyword.PUBLIC_KEYWORD)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public boolean visit(final FieldDeclaration node) {
      if (hasPublicModifier(node.modifiers())) {
        for (final Object ob : node.fragments())
          this.fieldMap.put(
                  new Pair<String, String>(this.className,
                          ((VariableDeclarationFragment) ob).getName().getIdentifier()
                  ),
                  node
          );
      }
      return false;
    }

    @Override
    public boolean visit(final MethodDeclaration node) {
      this.nameMap.clear();
      this.localNames.clear();
      for (final Object ob : node.parameters()) {
        final SingleVariableDeclaration parameter = (SingleVariableDeclaration) ob;
        final String name = parameter.getName().getIdentifier();
        this.nameMap.put(name, parameter);
        this.localNames.add(name);
      }
      if (node.getBody() != null)
        node.getBody().accept(this);
      this.nameMap.clear();
      this.localNames.clear();
      return false;
    }

    @Override
    public boolean visit(final MethodInvocation node) {
      final Expression expression = node.getExpression();
      if (expression != null) {
        if (expression instanceof SimpleName) {
          final String receiver = ((SimpleName) expression).getIdentifier();
          if (this.nameMap.containsKey(receiver)) {
            expression.accept(this);
          } else if (!"StaticJavaLib".equals(receiver)
              && !receiver.equals(this.className)
              && !this.classMap.containsKey(receiver)) {
            throw new Error(expression, "Unresolvable symbol \"" + receiver + "\"");
          }
        } else {
          expression.accept(this);
        }
      }
      for (final Object ob : node.arguments())
        ((ASTNode) ob).accept(this);

      final MethodDeclaration declaration = this.methodMap.get(node.getName().getIdentifier());
      final boolean currentClassInvocation = expression instanceof SimpleName && ((SimpleName) expression).getIdentifier().equals(this.className);
      if (declaration != null
          && (expression == null || currentClassInvocation)) {
        this.result.put(node, declaration);
      } else if (declaration == null
          && (expression == null || currentClassInvocation)) {
        throw new Error(node, "Unresolvable method \"" + node.getName().getIdentifier() + "\"");
      }
      return false;
    }

    @Override
    public boolean visit(final SimpleName node) {
      final ASTNode declaration = this.nameMap.get(node.getIdentifier());
      if (declaration == null)
        throw new Error(node, "Unresolvable symbol \"" + node.getIdentifier() + "\"");
      this.result.put(node, declaration);
      return false;
    }

    @Override
    public boolean visit(final VariableDeclarationStatement node) {
      for (final Object ob : node.fragments()) {
        final VariableDeclarationFragment fragment = (VariableDeclarationFragment) ob;
        final String name = fragment.getName().getIdentifier();
        if (!this.localNames.add(name))
          throw new Error(fragment.getName(), "Duplicate local variable \"" + name + "\"");
        this.nameMap.put(name, node);
        if (fragment.getInitializer() != null)
          fragment.getInitializer().accept(this);
      }
      return false;
    }

    @Override
    public boolean visit(final ArrayCreation node) {
      // Note that we don't visit the ArrayCreation's type
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      for (final Object o : node.dimensions()) {
        ((ASTNode) o).accept(this);
      }
      if (node.getInitializer() != null) {
        node.getInitializer().accept(this);
      }
      return false;
    }

    @Override
    public boolean visit(final ClassInstanceCreation node) {
      // Note that we don't visit the ClassInstanceCreation's name
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      return false;
    }

    @Override
    public boolean visit(final FieldAccess node) {
      // Note that we don't visit the FieldAccess's name
      // because we want visit(SimpleName) to resolve variable references
      // instead of method names.
      node.getExpression().accept(this);
      return false;
    }

    @Override
    public boolean visit(final TypeDeclaration node) {
      final String previousClassName = this.className;
      final Map<String, MethodDeclaration> previousMethodMap = new HashMap<>(this.methodMap);
      this.className = node.getName().getIdentifier();
      if (!hasPublicModifier(node.modifiers()))
        this.classMap.put(this.className, node);
      this.methodMap.clear();
      for (final MethodDeclaration method : node.getMethods())
        this.methodMap.put(method.getName().getIdentifier(), method);
      for (final Object o : node.bodyDeclarations())
        ((ASTNode) o).accept(this);
      this.className = previousClassName;
      this.methodMap.clear();
      this.methodMap.putAll(previousMethodMap);
      return false;
    }
  }

  /**
   * Builds a {@link SymbolTable} for the given ExtendedStaticJava
   * {@link CompilationUnit}.
   * 
   * @param cu
   *          The {@link CompilationUnit}.
   * @return The {@link SymbolTable} for the given
   *         {@link CompilationUnit}.
   * @throws Error
   *           If the builder encounter unresolvable symbol.
   */
  public static @NonNull
  SymbolTable build(@NonNull final CompilationUnit cu)
      throws Error {
    assert cu != null;

    final Visitor v = new Visitor();
    cu.accept(v);
    final SymbolTable result = new SymbolTable(v.result,
        v.classMap, v.fieldMap);
    v.dispose();
    return result;
  }

  /**
   * Declared as protected to disallow creation of this object outside from the
   * methods of this class.
   */
  protected SymbolTableBuilder() {
  }
}
