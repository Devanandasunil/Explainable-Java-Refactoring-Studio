package com.semanticrefactoring;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Safely scoped Extract Method rule for two final literal println statements. */
public class ExtractMethodService implements RefactoringRule {
  @Override public String id() { return "extract-two-literal-println-statements"; }
  @Override public String name() { return "Extract Method"; }

  /** Compatibility entry point used by earlier versions of the local workbench. */
  public Map<String, Object> propose(String source) {
    return new RefactoringEngine(List.of(this)).propose(source);
  }

  @Override
  public RefactoringCheck checkApplicability(RefactoringContext context) {
    return findCandidate(context.compilationUnit()).isPresent()
        ? RefactoringCheck.pass("supported-block-present", "Found a parameterless void method whose final two statements are literal System.out.println calls.")
        : RefactoringCheck.fail("supported-block-present", "No parameterless void method ends with two literal System.out.println calls.");
  }

  @Override
  public List<RefactoringCheck> preconditions(RefactoringContext context) {
    Optional<Candidate> candidate = findCandidate(context.compilationUnit());
    if (candidate.isEmpty()) {
      return List.of(RefactoringCheck.fail("supported-block-present",
          "No parameterless void method ends with two literal System.out.println calls."));
    }
    Candidate found = candidate.orElseThrow();
    String helperName = availableHelperName(found.type());
    return List.of(
        RefactoringCheck.pass("supported-block-present",
            "The final two method statements are single-argument string-literal System.out.println calls."),
        RefactoringCheck.pass("helper-name-available",
            "A non-conflicting helper name was selected: " + helperName + "."),
        RefactoringCheck.pass("static-context-preserved",
            found.method().isStatic()
                ? "The extracted helper will remain static because the source method is static."
                : "The extracted helper will remain an instance method because the source method is not static."));
  }

  @Override
  public RefactoringExecution execute(RefactoringContext context) {
    CompilationUnit transformed = context.compilationUnit().clone();
    Candidate candidate = findCandidate(transformed).orElseThrow(
        () -> new IllegalStateException("The supported statement block disappeared before execution."));
    TypeDeclaration<?> type = candidate.type();
    MethodDeclaration method = candidate.method();
    BlockStmt body = method.getBody().orElseThrow();
    String helper = availableHelperName(type);

    Statement first = body.getStatement(body.getStatements().size() - 2);
    Statement second = body.getStatement(body.getStatements().size() - 1);
    BlockStmt helperBody = new BlockStmt();
    helperBody.addStatement(first.clone());
    helperBody.addStatement(second.clone());

    body.getStatements().remove(body.getStatements().size() - 1);
    body.getStatements().remove(body.getStatements().size() - 1);
    body.addStatement(new ExpressionStmt(new MethodCallExpr(helper)));

    MethodDeclaration extracted = new MethodDeclaration();
    extracted.setName(helper);
    extracted.setType("void");
    extracted.setPrivate(true);
    extracted.setStatic(method.isStatic());
    extracted.setBody(helperBody);
    type.addMember(extracted);

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("helperName", helper);
    details.put("ownerType", type.getNameAsString());
    details.put("sourceMethod", method.getNameAsString());
    details.put("changes", List.of("Moves the final two literal print statements into a private helper.",
        "Replaces the two statements with one helper call.",
        "Preserves static context when the original method is static."));
    return new RefactoringExecution(transformed.toString(),
        "Extracted the final two literal print statements from " + type.getNameAsString() + "." + method.getNameAsString() + ".",
        details);
  }

  @Override
  public List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution) {
    ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()).parse(execution.sourceAfter());
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
      return List.of(RefactoringCheck.fail("transformed-source-parses", "The transformed source could not be parsed."));
    }
    CompilationUnit after = parsed.getResult().orElseThrow();
    String helperName = String.valueOf(execution.details().get("helperName"));
    String ownerType = String.valueOf(execution.details().get("ownerType"));
    String sourceMethod = String.valueOf(execution.details().get("sourceMethod"));
    Optional<TypeDeclaration<?>> owner = after.getTypes().stream()
        .filter(type -> type.getNameAsString().equals(ownerType)).findFirst();
    if (owner.isEmpty()) return List.of(RefactoringCheck.fail("owner-type-preserved", "The source type was not found after transformation."));

    TypeDeclaration<?> type = owner.orElseThrow();
    Optional<MethodDeclaration> helper = type.getMethodsByName(helperName).stream().findFirst();
    Optional<MethodDeclaration> originalMethod = type.getMethodsByName(sourceMethod).stream().findFirst();
    if (helper.isEmpty() || originalMethod.isEmpty()) {
      return List.of(RefactoringCheck.fail("helper-and-caller-present", "The extracted helper or original method is missing."));
    }

    MethodDeclaration helperMethod = helper.orElseThrow();
    MethodDeclaration caller = originalMethod.orElseThrow();
    boolean helperHasExpectedBody = helperMethod.isPrivate()
        && helperMethod.isStatic() == caller.isStatic()
        && helperMethod.getBody().map(body -> body.getStatements().size() == 2
            && isLiteralPrint(body.getStatement(0)) && isLiteralPrint(body.getStatement(1))).orElse(false);
    boolean callerCallsHelper = caller.getBody().map(body -> body.getStatements().stream()
        .filter(Statement::isExpressionStmt)
        .map(Statement::asExpressionStmt)
        .anyMatch(statement -> statement.getExpression().isMethodCallExpr()
            && statement.getExpression().asMethodCallExpr().getNameAsString().equals(helperName))).orElse(false);

    return List.of(
        helperHasExpectedBody
            ? RefactoringCheck.pass("helper-contains-original-statements", "The private helper contains both original literal print statements and preserves static context.")
            : RefactoringCheck.fail("helper-contains-original-statements", "The helper does not satisfy its expected body or access/static properties."),
        callerCallsHelper
            ? RefactoringCheck.pass("original-method-calls-helper", "The original method now invokes the extracted helper.")
            : RefactoringCheck.fail("original-method-calls-helper", "The original method does not call the extracted helper."));
  }

  private Optional<Candidate> findCandidate(CompilationUnit unit) {
    for (TypeDeclaration<?> type : unit.getTypes()) {
      for (MethodDeclaration method : type.getMethods()) {
        if (method.getNameAsString().startsWith("extractedPrintSteps")) continue;
        if (!method.getType().isVoidType() || !method.getParameters().isEmpty() || method.getBody().isEmpty()) continue;
        BlockStmt body = method.getBody().orElseThrow();
        if (body.getStatements().size() < 2) continue;
        Statement first = body.getStatement(body.getStatements().size() - 2);
        Statement second = body.getStatement(body.getStatements().size() - 1);
        if (isLiteralPrint(first) && isLiteralPrint(second)) return Optional.of(new Candidate(type, method));
      }
    }
    return Optional.empty();
  }

  private String availableHelperName(TypeDeclaration<?> type) {
    String helper = "extractedPrintSteps";
    int suffix = 2;
    while (!type.getMethodsByName(helper).isEmpty()) helper = "extractedPrintSteps" + suffix++;
    return helper;
  }

  private boolean isLiteralPrint(Statement statement) {
    if (!statement.isExpressionStmt()) return false;
    Expression expression = statement.asExpressionStmt().getExpression();
    if (!expression.isMethodCallExpr()) return false;
    MethodCallExpr call = expression.asMethodCallExpr();
    if (!call.getNameAsString().equals("println") || call.getScope().isEmpty()
        || !call.getScope().get().toString().equals("System.out") || call.getArguments().size() != 1) return false;
    return call.getArgument(0).isStringLiteralExpr();
  }

  private record Candidate(TypeDeclaration<?> type, MethodDeclaration method) { }
}
