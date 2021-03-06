/*
 * Copyright 2004 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.StaticScope;
import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Scope contains information about a variable scope in JavaScript.
 * Scopes can be nested, a scope points back to its parent scope.
 * A Scope contains information about variables defined in that scope.
 *
 * @see NodeTraversal
 *
 */
public class Scope implements StaticScope, Serializable {
  protected final Map<String, Var> vars = new LinkedHashMap<>();
  protected Scope parent;
  protected int depth;
  protected final Node rootNode;
  private Var arguments;

  /**
   * Creates a Scope given the parent Scope and the root node of the current scope.
   *
   * @param parent The parent Scope. Cannot be null.
   * @param rootNode The root node of the curent scope. Cannot be null.
   */
  Scope(Scope parent, Node rootNode) {
    checkNotNull(parent);
    checkArgument(NodeUtil.createsScope(rootNode), rootNode);
    checkArgument(
        rootNode != parent.rootNode, "rootNode should not be the parent's root node", rootNode);

    this.parent = parent;
    this.rootNode = rootNode;
    this.depth = parent.depth + 1;
  }

  protected Scope(Node rootNode) {
    // TODO(tbreisacher): Can we tighten this to just NodeUtil.createsScope?
    checkArgument(
        NodeUtil.createsScope(rootNode) || rootNode.isScript() || rootNode.isRoot(), rootNode);
    this.parent = null;
    this.rootNode = rootNode;
    this.depth = 0;
  }

  @Override
  public String toString() {
    return "Scope@" + rootNode;
  }

  static Scope createGlobalScope(Node rootNode) {
    // TODO(tbreisacher): Can we tighten this to allow only ROOT nodes?
    checkArgument(rootNode.isRoot() || rootNode.isScript(), rootNode);
    return new Scope(rootNode);
  }

  /** The depth of the scope. The global scope has depth 0. */
  public int getDepth() {
    return depth;
  }

  /**
   * Gets the container node of the scope. This is typically the FUNCTION
   * node or the global BLOCK/SCRIPT node.
   */
  @Override
  public Node getRootNode() {
    return rootNode;
  }

  public Scope getParent() {
    return parent;
  }

  Scope getGlobalScope() {
    Scope result = this;
    while (result.getParent() != null) {
      result = result.getParent();
    }
    return result;
  }

  @Override
  public StaticScope getParentScope() {
    return parent;
  }

  /**
   * Declares a variable.
   *
   * @param name name of the variable
   * @param nameNode the NAME node declaring the variable
   * @param input the input in which this variable is defined.
   */
  Var declare(String name, Node nameNode, CompilerInput input) {
    checkState(name != null && !name.isEmpty());
    // Make sure that it's declared only once
    checkState(vars.get(name) == null);
    Var var = new Var(name, nameNode, this, vars.size(), input);
    vars.put(name, var);
    return var;
  }

  /**
   * Undeclares a variable, to be used when the compiler optimizes out
   * a variable and removes it from the scope.
   */
  void undeclare(Var var) {
    checkState(var.scope == this);
    checkState(vars.get(var.name).equals(var));
    undeclareInteral(var);
  }

  /** Without any safety checks */
  void undeclareInteral(Var var) {
     vars.remove(var.name);
  }

  @Override
  public Var getSlot(String name) {
    return getVar(name);
  }

  @Override
  public Var getOwnSlot(String name) {
    return vars.get(name);
  }

  /**
   * Returns the variable, may be null
   */
  public Var getVar(String name) {
    Scope scope = this;
    while (scope != null) {
      Var var = scope.vars.get(name);
      if (var != null) {
        return var;
      }
      if ("arguments".equals(name) && NodeUtil.isVanillaFunction(scope.getRootNode())) {
        return scope.getArgumentsVar();
      }
      // Recurse up the parent Scope
      scope = scope.parent;
    }
    return null;
  }

  /**
   * Get a unique VAR object to represents "arguments" within this scope
   */
  public Var getArgumentsVar() {
    if (isGlobal() || isModuleScope()) {
      throw new IllegalStateException("No arguments var for scope: " + this);
    }
    if (!isFunctionScope() || rootNode.isArrowFunction()) {
      return parent.getArgumentsVar();
    }

    if (arguments == null) {
      arguments = Var.makeArgumentsVar(this);
    }
    return arguments;
  }

  /**
   * @deprecated use #isDeclared instead
   */
  @Deprecated
  public boolean isDeclaredSloppy(String name, boolean recurse) {
    // In ES6, we create a separate "function parameter scope" above the function block scope to
    // handle default parameters. Since nothing in the function block scope is allowed to shadow
    // the variables in the function scope, we treat the two scopes as one in this method.
    checkState(recurse == false);
    if (!isDeclared(name, false)) {
      if (parent != null && isFunctionBlockScope()) {
        return parent.isDeclared(name, false);
      }
      return false;
    } else {
      return true;
    }
  }

  /**
   * Returns true if a variable is declared.
   */
  public boolean isDeclared(String name, boolean recurse) {
    Scope scope = this;
    while (true) {
      if (scope.vars.containsKey(name)) {
        return true;
      }

      if (scope.parent != null && recurse) {
        scope = scope.parent;
        continue;
      }
      return false;
    }
  }

  /**
   * Return an iterable over all of the variables declared in this scope
   * (except the special 'arguments' variable).
   */
  public Iterable<? extends Var> getVarIterable() {
    return vars.values();
  }

  public Iterable<? extends Var> getAllSymbols() {
    return Collections.unmodifiableCollection(vars.values());
  }

  /**
   * Returns number of variables in this scope (excluding the special 'arguments' variable)
   */
  public int getVarCount() {
    return vars.size();
  }

  /**
   * Returns whether this is the global scope.
   */
  public boolean isGlobal() {
    return parent == null;
  }

  /**
   * Returns whether this is a local scope (i.e. not the global scope).
   */
  public boolean isLocal() {
    return parent != null;
  }

  public boolean isBlockScope() {
    return NodeUtil.createsBlockScope(rootNode);
  }

  public boolean isFunctionBlockScope() {
    return NodeUtil.isFunctionBlock(getRootNode());
  }

  public boolean isFunctionScope() {
    return getRootNode().isFunction();
  }

  public boolean isModuleScope() {
    return getRootNode().isModuleBody();
  }

  public boolean isCatchScope() {
    return getRootNode().isNormalBlock()
        && getRootNode().hasOneChild()
        && getRootNode().getFirstChild().isCatch();
  }

  /**
   * If a var were declared in this scope, would it belong to this scope (as opposed to some
   * enclosing scope)?
   *
   * We consider function scopes to be hoist scopes. Even though it's impossible to declare a var
   * inside function parameters, it would make less sense to say that if you did declare one in
   * the function parameters, it would be hoisted somewhere else.
   */
  boolean isHoistScope() {
    return isFunctionScope() || isFunctionBlockScope() || isGlobal() || isModuleScope();
  }

  /**
   * If a var were declared in this scope, return the scope it would be hoisted to.
   *
   * For function scopes, we return back the scope itself, since even though there is no way
   * to declare a var inside function parameters, it would make even less sense to say that
   * such declarations would be "hoisted" somewhere else.
   */
  public Scope getClosestHoistScope() {
    Scope current = this;
    while (current != null) {
      if (current.isHoistScope()) {
        return current;
      }
      current = current.parent;
    }
    return null;
  }
}
