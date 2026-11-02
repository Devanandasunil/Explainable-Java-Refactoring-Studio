package com.semanticrefactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Extracts a repeated method-body string literal into a private static constant. */
public final class ExtractStringConstantRule implements RefactoringRule {
  @Override public String id() { return "extract-repeated-string-constant"; }
  @Override public String name() { return "Extract repeated string to constant"; }

  @Override
  public RefactoringCheck checkApplicability(RefactoringContext context) {
    return findCandidate(context.compilationUnit()).isPresent()
        ? RefactoringCheck.pass("repeated-method-string-present", "Found a repeated string literal in methods of a class.")
        : RefactoringCheck.fail("repeated-method-string-present", "No repeated string literal was found in methods of a class.");
  }

  @Override
  public List<RefactoringCheck> preconditions(RefactoringContext context) {
    Optional<Candidate> candidate = findCandidate(context.compilationUnit());
    if (candidate.isEmpty()) {
      return List.of(RefactoringCheck.fail("repeated-method-string-present", "A repeated method-body string literal is required."));
    }
    Candidate found = candidate.orElseThrow();
    return List.of(
        RefactoringCheck.pass("class-field-context", "The containing type is a class, so a private static field can be added."),
        RefactoringCheck.pass("repeat-count", "The exact string value appears " + found.occurrences().size() + " times in method bodies."),
        RefactoringCheck.pass("constant-name-unique", "Selected a field name that does not collide with existing identifiers: " + availableName(found.type()) + "."));
  }

  @Override
  public RefactoringExecution execute(RefactoringContext context) {
    CompilationUnit transformed = context.compilationUnit().clone();
    Candidate candidate = findCandidate(transformed).orElseThrow(
        () -> new IllegalStateException("The repeated string literal candidate disappeared before execution."));
    String constantName = availableName(candidate.type());
    StringLiteralExpr initializer = candidate.occurrences().get(0).clone();
    int replaced = 0;
    for (StringLiteralExpr occurrence : candidate.occurrences()) {
      if (!occurrence.replace(new NameExpr(constantName))) {
        throw new IllegalStateException("A selected string literal could not be replaced in the AST.");
      }
      replaced++;
    }

    VariableDeclarator variable = new VariableDeclarator(
        StaticJavaParser.parseType("String"), constantName, initializer);
    FieldDeclaration field = new FieldDeclaration();
    field.addVariable(variable);
    field.addModifier(Modifier.Keyword.PRIVATE, Modifier.Keyword.STATIC, Modifier.Keyword.FINAL);
    candidate.type().addMember(field);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("constantName", constantName);
    details.put("constantValue", candidate.value());
    details.put("ownerType", candidate.type().getNameAsString());
    details.put("replacementCount", replaced);
    details.put("changes", List.of("Adds a private static final String field.",
        "Replaces " + replaced + " identical method-body string literals with the field name."));
    return new RefactoringExecution(transformed.toString(),
        "Extracted a repeated string literal in " + candidate.type().getNameAsString() + " to " + constantName + ".",
        details);
  }

  @Override
  public List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution) {
    ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()).parse(execution.sourceAfter());
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
      return List.of(RefactoringCheck.fail("transformed-source-parses", "The transformed source could not be parsed."));
    }
    CompilationUnit after = parsed.getResult().orElseThrow();
    String constantName = String.valueOf(execution.details().get("constantName"));
    String constantValue = String.valueOf(execution.details().get("constantValue"));
    String ownerType = String.valueOf(execution.details().get("ownerType"));
    int replacementCount = (int) execution.details().get("replacementCount");
    Optional<TypeDeclaration<?>> owner = after.getTypes().stream()
        .filter(type -> type.getNameAsString().equals(ownerType)).findFirst();
    if (owner.isEmpty()) return List.of(RefactoringCheck.fail("owner-type-preserved", "The containing class was not found after transformation."));

    TypeDeclaration<?> type = owner.orElseThrow();
    Optional<VariableDeclarator> field = type.getFields().stream()
        .filter(declaration -> declaration.isPrivate() && declaration.isStatic() && declaration.isFinal())
        .flatMap(declaration -> declaration.getVariables().stream())
        .filter(variable -> variable.getNameAsString().equals(constantName))
        .findFirst();
    boolean hasConstant = field.filter(variable -> variable.getInitializer().filter(Expression ->
        Expression.isStringLiteralExpr() && Expression.asStringLiteralExpr().asString().equals(constantValue)).isPresent()).isPresent();

    long references = type.getMethods().stream()
        .map(MethodDeclaration::getBody)
        .flatMap(Optional::stream)
        .flatMap(body -> body.findAll(NameExpr.class).stream())
        .filter(name -> name.getNameAsString().equals(constantName)).count();
    long remainingLiterals = type.getMethods().stream()
        .map(MethodDeclaration::getBody)
        .flatMap(Optional::stream)
        .flatMap(body -> body.findAll(StringLiteralExpr.class).stream())
        .filter(literal -> literal.asString().equals(constantValue)).count();

    return List.of(
        hasConstant
            ? RefactoringCheck.pass("constant-field-created", "A private static final String field was created with the selected literal value.")
            : RefactoringCheck.fail("constant-field-created", "The expected private static final String field was not found."),
        references >= replacementCount && remainingLiterals == 0
            ? RefactoringCheck.pass("literal-occurrences-replaced", "All " + replacementCount + " selected occurrences now reference the constant.")
            : RefactoringCheck.fail("literal-occurrences-replaced", "The expected number of constant references was not found or matching literals remain in methods."));
  }

  private Optional<Candidate> findCandidate(CompilationUnit unit) {
    for (TypeDeclaration<?> type : unit.getTypes()) {
      if (!type.isClassOrInterfaceDeclaration() || type.asClassOrInterfaceDeclaration().isInterface()) continue;
      Map<String, List<StringLiteralExpr>> literals = new LinkedHashMap<>();
      for (MethodDeclaration method : type.getMethods()) {
        Optional<BlockStmt> body = method.getBody();
        if (body.isEmpty()) continue;
        for (StringLiteralExpr literal : body.orElseThrow().findAll(StringLiteralExpr.class)) {
          literals.computeIfAbsent(literal.asString(), ignored -> new ArrayList<>()).add(literal);
        }
      }
      Optional<Map.Entry<String, List<StringLiteralExpr>>> repeated = literals.entrySet().stream()
          .filter(entry -> entry.getValue().size() >= 2)
          .max((left, right) -> Integer.compare(left.getValue().size(), right.getValue().size()));
      if (repeated.isPresent()) {
        Map.Entry<String, List<StringLiteralExpr>> item = repeated.orElseThrow();
        return Optional.of(new Candidate(type, item.getKey(), List.copyOf(item.getValue())));
      }
    }
    return Optional.empty();
  }

  private String availableName(TypeDeclaration<?> type) {
    String base = "EXTRACTED_STRING";
    String candidate = base;
    int suffix = 2;
    while (containsIdentifier(type, candidate)) candidate = base + "_" + suffix++;
    return candidate;
  }

  private boolean containsIdentifier(TypeDeclaration<?> type, String name) {
    return type.findAll(com.github.javaparser.ast.expr.SimpleName.class).stream()
        .anyMatch(simpleName -> simpleName.asString().equals(name));
  }

  private record Candidate(TypeDeclaration<?> type, String value, List<StringLiteralExpr> occurrences) { }
}
