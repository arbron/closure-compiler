/*
 * Copyright 2017 The Closure Compiler Authors.
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

import com.google.common.base.Preconditions;
import com.google.javascript.jscomp.ControlFlowGraph.Branch;
import com.google.javascript.jscomp.graph.DiGraph.DiGraphEdge;
import com.google.javascript.jscomp.graph.LatticeElement;
import com.google.javascript.rhino.Node;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compute the "liveness" of all local variables. A variable is "live" at a point of a program if
 * the value it is currently holding might be read later. Otherwise, the variable is considered
 * "dead" if we know for sure that it will no longer be read. Dead variables are candidates for dead
 * assignment elimination and variable name sharing. The worst case safe assumption is to assume
 * that all variables are live. In that case, we will have no opportunity for optimizations. This is
 * especially the case within a TRY block when an assignment is not guaranteed to take place. We
 * bail out by assuming that all variables are live.
 *
 * <p>Due to the possibility of inner functions and closures, certain "local" variables can escape
 * the function. These variables will be considered as global and they can be retrieved with {@link
 * #getEscapedLocals()}.
 *
 * @author simranarora@google.com (Simran Arora)
 */
class LiveVariablesAnalysisEs6
    extends DataFlowAnalysis<Node, LiveVariablesAnalysisEs6.LiveVariableLattice> {

  static final int MAX_VARIABLES_TO_ANALYZE = 100;

  public static final String ARGUMENT_ARRAY_ALIAS = "arguments";

  private static class LiveVariableJoinOp implements JoinOp<LiveVariableLattice> {
    @Override
    public LiveVariableLattice apply(List<LiveVariableLattice> in) {
      LiveVariableLattice result = new LiveVariableLattice(in.get(0));
      for (int i = 1; i < in.size(); i++) {
        result.liveSet.or(in.get(i).liveSet);
      }
      return result;
    }
  }

  /**
   * The lattice that stores the liveness of all local variables at a given point in the program.
   * The whole lattice is the power set of all local variables and a variable is live if it is in
   * the set.
   */
  static class LiveVariableLattice implements LatticeElement {
    private final BitSet liveSet;

    /** @param numVars Number of all local variables. */
    private LiveVariableLattice(int numVars) {
      this.liveSet = new BitSet(numVars);
    }

    private LiveVariableLattice(LiveVariableLattice other) {
      Preconditions.checkNotNull(other);
      this.liveSet = (BitSet) other.liveSet.clone();
    }

    @Override
    public boolean equals(Object other) {
      Preconditions.checkNotNull(other);
      return (other instanceof LiveVariableLattice)
          && this.liveSet.equals(((LiveVariableLattice) other).liveSet);
    }

    public boolean isLive(Var v) {
      Preconditions.checkNotNull(v);
      return liveSet.get(v.index);
    }

    public boolean isLive(int index) {
      return liveSet.get(index);
    }

    public boolean isLive(Var v, int index) {
      return liveSet.get(index);
    }

    @Override
    public String toString() {
      return liveSet.toString();
    }

    @Override
    public int hashCode() {
      return liveSet.hashCode();
    }
  }

  // The scope of the function that we are analyzing.
  private final Scope jsScope;

  // The scope of the body of the function that we are analyzing.
  private final Scope jsScopeChild;
  private final Set<Var> escaped;

  // Maps the variable name to it's position
  // in this jsScope were we to combine the function and function body scopes. The Integer
  // represents the equivalent of the variable index property within a scope
  private final Map<String, Integer> scopeVariables;

  /**
   * ******************************************************* Live Variables Analysis using the ES6
   * scope creator. Based on our preconditions, the child scope will only be passed in if the
   * jsScope is function scope.
   *
   * @param cfg
   * @param jsScope
   * @param jsScopeChild used if jsScope is function scope in order to pass along function body
   * @param compiler
   * @param scopeCreator Es6 Scope creator
   */
  LiveVariablesAnalysisEs6(
      ControlFlowGraph<Node> cfg,
      Scope jsScope,
      Scope jsScopeChild,
      Compiler compiler,
      Es6SyntacticScopeCreator scopeCreator) {
    super(cfg, new LiveVariableJoinOp());
    this.jsScope = jsScope;
    this.jsScopeChild = jsScopeChild;
    this.escaped = new HashSet<>();
    this.scopeVariables = new HashMap<>();
    computeEscaped(jsScope, jsScopeChild, escaped, compiler, scopeCreator);
    addScopeVariables();
  }

  /**
   * Parameters belong to the function scope, but variables defined in the function body belong to
   * the function body scope. Assign a unique index to each variable, regardless of which scope it's
   * in.
   */
  private void addScopeVariables() {
    int num = 0;
    for (Var v : jsScope.getVarIterable()) {
      scopeVariables.put(v.getName(), num);
      num++;
    }

    for (Var v : jsScopeChild.getVarIterable()) {
      scopeVariables.put(v.getName(), num);
      num++;
    }
  }

  public Set<? extends Var> getEscapedLocals() {
    return escaped;
  }

  public int getVarIndex(String var) {
    return scopeVariables.get(var);
  }

  @Override
  boolean isForward() {
    return false;
  }

  @Override
  LiveVariableLattice createEntryLattice() {
    return new LiveVariableLattice(jsScope.getVarCount());
  }

  @Override
  LiveVariableLattice createInitialEstimateLattice() {
    return new LiveVariableLattice(jsScope.getVarCount());
  }

  @Override
  LiveVariableLattice flowThrough(Node node, LiveVariableLattice input) {
    final BitSet gen = new BitSet(input.liveSet.size());
    final BitSet kill = new BitSet(input.liveSet.size());

    // Make kills conditional if the node can end abruptly by an exception.
    boolean conditional = false;
    List<DiGraphEdge<Node, Branch>> edgeList = getCfg().getOutEdges(node);
    for (DiGraphEdge<Node, Branch> edge : edgeList) {
      if (Branch.ON_EX.equals(edge.getValue())) {
        conditional = true;
      }
    }
    computeGenKill(node, gen, kill, conditional);
    LiveVariableLattice result = new LiveVariableLattice(input);
    // L_in = L_out - Kill + Gen
    result.liveSet.andNot(kill);
    result.liveSet.or(gen);
    return result;
  }

  /**
   * Computes the GEN and KILL set.
   *
   * @param n Root node.
   * @param gen Local variables that are live because of the instruction at {@code n} will be added
   *     to this set.
   * @param kill Local variables that are killed because of the instruction at {@code n} will be
   *     added to this set.
   * @param conditional {@code true} if any assignments encountered are conditionally executed.
   *     These assignments might not kill a variable.
   */
  private void computeGenKill(Node n, BitSet gen, BitSet kill, boolean conditional) {

    switch (n.getToken()) {
      case SCRIPT:
      case BLOCK:
      case ROOT:
      case FUNCTION:
        return;

      case WHILE:
      case DO:
      case IF:
      case FOR:
        computeGenKill(NodeUtil.getConditionExpression(n), gen, kill, conditional);
        return;

      case FOR_IN:
        {
          // for(x in y) {...}
          Node lhs = n.getFirstChild();
          if (lhs.isVar()) {
            // for(var x in y) {...}
            lhs = lhs.getLastChild();
          }

          if (lhs.isName()) {
            addToSetIfLocal(lhs, kill);
            addToSetIfLocal(lhs, gen);
          } else {
            computeGenKill(lhs, gen, kill, conditional);
          }

          // rhs is executed only once so we don't go into it every loop.
          return;
        }

      case VAR:
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
          if (c.hasChildren()) {
            computeGenKill(c.getFirstChild(), gen, kill, conditional);
            if (!conditional) {
              addToSetIfLocal(c, kill);
            }
          }
        }
        return;

      case AND:
      case OR:
        computeGenKill(n.getFirstChild(), gen, kill, conditional);
        // May short circuit.
        computeGenKill(n.getLastChild(), gen, kill, true);
        return;

      case HOOK:
        computeGenKill(n.getFirstChild(), gen, kill, conditional);
        // Assume both sides are conditional.
        computeGenKill(n.getSecondChild(), gen, kill, true);
        computeGenKill(n.getLastChild(), gen, kill, true);
        return;

      case NAME:
        if (isArgumentsName(n)) {
          markAllParametersEscaped();
        } else {
          addToSetIfLocal(n, gen);
        }
        return;

      default:
        if (NodeUtil.isAssignmentOp(n) && n.getFirstChild().isName()) {
          Node lhs = n.getFirstChild();
          if (!conditional) {
            addToSetIfLocal(lhs, kill);
          }
          if (!n.isAssign()) {
            // assignments such as a += 1 reads a.
            addToSetIfLocal(lhs, gen);
          }
          computeGenKill(lhs.getNext(), gen, kill, conditional);
        } else {
          for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            computeGenKill(c, gen, kill, conditional);
          }
        }
        return;
    }
  }

  private void addToSetIfLocal(Node node, BitSet set) {
    Preconditions.checkState(node.isName(), node);
    String name = node.getString();

    // add to the local set if the variable is declared in the function or function body because
    // ES6 separates the scope but hoists variables to the function scope
    if (jsScope.isDeclaredSloppy(name, false)) {
      Var var = jsScope.getVar(name);
      if (!escaped.contains(var)) {
        set.set(getVarIndex(var.getName()));
      }
    } else if (!jsScopeChild.isDeclaredSloppy(name, false)) {
      return;
    } else {
      Var var = jsScopeChild.getVar(name);

      if (!escaped.contains(var)) {
        set.set(getVarIndex(var.getName()));
      }
    }
  }

  /**
   * Give up computing liveness of formal parameter by putting all the parameter names in the
   * escaped set.
   */
  void markAllParametersEscaped() {
    Node lp = jsScope.getRootNode().getSecondChild();
    for (Node arg = lp.getFirstChild(); arg != null; arg = arg.getNext()) {
      escaped.add(jsScope.getVar(arg.getString()));
    }
  }

  private boolean isArgumentsName(Node n) {
    return n.isName()
        && n.getString().equals(ARGUMENT_ARRAY_ALIAS)
        && (!jsScope.isDeclaredSloppy(ARGUMENT_ARRAY_ALIAS, false)
            || !jsScopeChild.isDeclaredSloppy(ARGUMENT_ARRAY_ALIAS, false));
  }
}
