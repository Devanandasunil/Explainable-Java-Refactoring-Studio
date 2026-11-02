package com.semanticrefactoring;

import com.github.javaparser.ast.CompilationUnit;

/** Immutable source snapshot and parsed AST supplied to a rule. */
public record RefactoringContext(String source, CompilationUnit compilationUnit) { }
