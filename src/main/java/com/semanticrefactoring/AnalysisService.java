package com.semanticrefactoring;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Syntax-only code analysis with explicit, reviewable heuristics. */
public class AnalysisService {
  private static final int LONG_METHOD_STATEMENTS = 10;
  private static final int HIGH_COMPLEXITY = 10;
  private static final int MANY_PARAMETERS = 5;

  public Map<String, Object> analyze(String source) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Map<String, Object>> types = new ArrayList<>();
    List<Map<String, Object>> methods = new ArrayList<>();
    List<Map<String, Object>> findings = new ArrayList<>();
    ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()).parse(source);
    out.put("success", parsed.isSuccessful() && parsed.getResult().isPresent());
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
      out.put("summary", "Could not parse this Java source.");
      out.put("problems", parsed.getProblems().stream().map(Object::toString).toList());
      out.put("types", types);
      out.put("methods", methods);
      out.put("findings", findings);
      out.put("qualityMetrics", emptyMetrics());
      out.put("suggestions", List.of("Fix parse errors, then analyze again."));
      return out;
    }

    CompilationUnit unit = parsed.getResult().orElseThrow();
    out.put("packageName", unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("(default package)"));
    int totalLines = source.isEmpty() ? 0 : source.split("\\R", -1).length - (source.endsWith("\n") || source.endsWith("\r") ? 1 : 0);
    int complexityTotal = 0;
    int highestComplexity = 0;

    for (TypeDeclaration<?> type : unit.getTypes()) {
      List<String> fields = new ArrayList<>();
      for (FieldDeclaration field : type.getFields()) {
        field.getVariables().forEach(variable -> fields.add(variable.getNameAsString() + " : " + variable.getTypeAsString()));
      }
      Map<String, Object> typeInfo = new LinkedHashMap<>();
      typeInfo.put("name", type.getNameAsString());
      typeInfo.put("kind", type.getClass().getSimpleName().replace("Declaration", ""));
      typeInfo.put("fields", fields);
      types.add(typeInfo);

      for (MethodDeclaration method : type.getMethods()) {
        int statementCount = method.getBody().map(body -> body.getStatements().size()).orElse(0);
        int methodLines = method.getRange().map(range -> range.end.line - range.begin.line + 1).orElse(0);
        int complexity = cyclomaticComplexity(method);
        int parameterCount = method.getParameters().size();
        complexityTotal += complexity;
        highestComplexity = Math.max(highestComplexity, complexity);

        Map<String, Object> methodInfo = new LinkedHashMap<>();
        methodInfo.put("className", type.getNameAsString());
        methodInfo.put("name", method.getNameAsString());
        methodInfo.put("returnType", method.getTypeAsString());
        methodInfo.put("parameters", parameterCount);
        methodInfo.put("statementCount", statementCount);
        methodInfo.put("lines", methodLines);
        methodInfo.put("cyclomaticComplexity", complexity);
        methodInfo.put("line", method.getBegin().map(position -> position.line).orElse(-1));
        methods.add(methodInfo);

        String location = type.getNameAsString() + "." + method.getNameAsString();
        int line = method.getBegin().map(position -> position.line).orElse(-1);
        if (statementCount > LONG_METHOD_STATEMENTS) {
          findings.add(finding("Potential long method", "Medium", location,
              "This method has " + statementCount + " top-level statements; the configured threshold is " + LONG_METHOD_STATEMENTS + ".",
              "Review whether related steps can be extracted into small, well-named methods.", line,
              "long-method", statementCount, LONG_METHOD_STATEMENTS));
        }
        if (complexity > HIGH_COMPLEXITY) {
          findings.add(finding("High decision complexity", "Medium", location,
              "Estimated cyclomatic complexity is " + complexity + "; the configured threshold is " + HIGH_COMPLEXITY + ".",
              "Review branches and consider simplifying decisions or extracting cohesive logic.", line,
              "cyclomatic-complexity", complexity, HIGH_COMPLEXITY));
        }
        if (parameterCount > MANY_PARAMETERS) {
          findings.add(finding("Many method parameters", "Low", location,
              "This method has " + parameterCount + " parameters; the configured threshold is " + MANY_PARAMETERS + ".",
              "Review whether the inputs form a cohesive value object or whether the method has too many responsibilities.", line,
              "parameter-count", parameterCount, MANY_PARAMETERS));
        }
      }
    }

    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("sourceLines", totalLines);
    metrics.put("typeCount", types.size());
    metrics.put("methodCount", methods.size());
    metrics.put("averageCyclomaticComplexity", methods.isEmpty() ? 0.0 : Math.round((complexityTotal * 100.0 / methods.size())) / 100.0);
    metrics.put("highestCyclomaticComplexity", highestComplexity);
    metrics.put("findingsCount", findings.size());
    metrics.put("methodMetrics", methods.stream().map(method -> {
      Map<String, Object> metric = new LinkedHashMap<>();
      metric.put("method", method.get("className") + "." + method.get("name"));
      metric.put("lines", method.get("lines"));
      metric.put("topLevelStatements", method.get("statementCount"));
      metric.put("cyclomaticComplexity", method.get("cyclomaticComplexity"));
      metric.put("parameters", method.get("parameters"));
      return metric;
    }).toList());
    out.put("qualityMetrics", metrics);

    List<String> suggestions = new ArrayList<>();
    if (!findings.isEmpty()) suggestions.add("Review each finding and its stated rule; findings are prompts for inspection, not proof of a defect.");
    if (suggestions.isEmpty()) suggestions.add("No configured heuristic triggered. This does not prove the code is free of design or quality issues.");
    suggestions.add("Compile checks catch some errors. Run relevant regression tests to check behavior after changing code.");
    out.put("types", types);
    out.put("methods", methods);
    out.put("findings", findings);
    out.put("suggestions", suggestions);
    out.put("summary", "Parsed " + types.size() + " type(s), " + methods.size() + " method(s), and found " + findings.size() + " heuristic finding(s).");
    return out;
  }

  private int cyclomaticComplexity(MethodDeclaration method) {
    int score = 1;
    score += method.findAll(IfStmt.class).size();
    score += method.findAll(ForStmt.class).size();
    score += method.findAll(ForEachStmt.class).size();
    score += method.findAll(WhileStmt.class).size();
    score += method.findAll(DoStmt.class).size();
    score += method.findAll(CatchClause.class).size();
    score += method.findAll(ConditionalExpr.class).size();
    score += (int) method.findAll(BinaryExpr.class).stream()
        .filter(expression -> expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR).count();
    return score;
  }

  private Map<String, Object> finding(String title, String severity, String location, String reason,
      String recommendation, int line, String ruleId, int observed, int threshold) {
    Map<String, Object> finding = new LinkedHashMap<>();
    finding.put("severity", severity);
    finding.put("title", title);
    finding.put("location", location);
    finding.put("reason", reason);
    finding.put("recommendation", recommendation);
    finding.put("line", line);
    finding.put("ruleId", ruleId);
    finding.put("observed", observed);
    finding.put("threshold", threshold);
    return finding;
  }

  private Map<String, Object> emptyMetrics() {
    Map<String, Object> metrics = new LinkedHashMap<>();
    metrics.put("sourceLines", 0);
    metrics.put("typeCount", 0);
    metrics.put("methodCount", 0);
    metrics.put("averageCyclomaticComplexity", 0.0);
    metrics.put("highestCyclomaticComplexity", 0);
    metrics.put("findingsCount", 0);
    metrics.put("methodMetrics", List.of());
    return metrics;
  }

  /** Prefer the parsed top-level type name so comments and literals cannot mislead the compiler. */
  public static String findPrimaryClassName(String source) {
    ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()).parse(source);
    if (parsed.getResult().isPresent()) {
      CompilationUnit unit = parsed.getResult().orElseThrow();
      for (TypeDeclaration<?> type : unit.getTypes()) {
        if (type.isPublic()) return type.getNameAsString();
      }
      if (!unit.getTypes().isEmpty()) return unit.getTypes().get(0).getNameAsString();
    }
    Matcher matcher = Pattern.compile("\\bpublic\\s+(?:final\\s+|abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)").matcher(source);
    if (matcher.find()) return matcher.group(1);
    matcher = Pattern.compile("\\bclass\\s+([A-Za-z_$][\\w$]*)").matcher(source);
    return matcher.find() ? matcher.group(1) : null;
  }
}
