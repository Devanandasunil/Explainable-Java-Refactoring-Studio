package com.semanticrefactoring;

import java.util.List;

/** Contract implemented by an independently testable AST transformation rule. */
public interface RefactoringRule {
  String id();
  String name();

  /** Candidate screening with a human-readable reason; must not mutate the input AST. */
  RefactoringCheck checkApplicability(RefactoringContext context);

  /** Explicit safety checks run before execution. */
  List<RefactoringCheck> preconditions(RefactoringContext context);

  /** Returns a proposed source snapshot; callers decide whether to accept it. */
  RefactoringExecution execute(RefactoringContext context);

  /** Checks that the transformation established its required structural guarantees. */
  List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution);
}
