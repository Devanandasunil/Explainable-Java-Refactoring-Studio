package com.semanticrefactoring;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validation-gated coordinator for independently registered refactoring rules. */
public final class RefactoringEngine {
  private final List<RefactoringRule> rules;

  public RefactoringEngine() {
    this(List.of(new ExtractMethodService(), new ExtractStringConstantRule()));
  }

  public RefactoringEngine(List<RefactoringRule> rules) {
    this.rules = List.copyOf(rules);
  }

  public List<Map<String, Object>> availableRules() {
    List<Map<String, Object>> available = new ArrayList<>();
    for (RefactoringRule rule : rules) {
      Map<String, Object> descriptor = new LinkedHashMap<>();
      descriptor.put("id", rule.id());
      descriptor.put("name", rule.name());
      available.add(descriptor);
    }
    return List.copyOf(available);
  }

  /** Evaluates registered rules in order and returns the first proposal that passes all gates. */
  public Map<String, Object> propose(String source) {
    return evaluate(source, rules);
  }

  /** Returns every proposal that independently passes its rule checks. */
  public List<Map<String, Object>> proposeAll(String source) {
    List<Map<String, Object>> proposals = new ArrayList<>();
    for (RefactoringRule rule : rules) {
      Map<String, Object> proposal = evaluate(source, List.of(rule));
      if (Boolean.TRUE.equals(proposal.get("success"))) proposals.add(proposal);
    }
    return List.copyOf(proposals);
  }

  private Map<String, Object> evaluate(String source, List<RefactoringRule> candidates) {
    List<RefactoringTraceEntry> trace = new ArrayList<>();
    ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()).parse(source);
    if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
      add(trace, "framework", "parse", "rejected", "Input source has Java syntax errors; no rule was evaluated.");
      Map<String, Object> failure = failure("The source has syntax errors. Fix them before refactoring.", trace);
      failure.put("problems", parsed.getProblems().stream().map(Object::toString).toList());
      return failure;
    }

    RefactoringContext before = new RefactoringContext(source, parsed.getResult().orElseThrow());
    add(trace, "framework", "parse", "passed", "Input parsed as a Java compilation unit.");
    String firstRejection = null;

    for (RefactoringRule rule : candidates) {
      RefactoringCheck applicability;
      try {
        applicability = rule.checkApplicability(freshContext(before));
      } catch (RuntimeException error) {
        add(trace, rule.id(), "applicability", "error", "Applicability check failed: " + safeMessage(error));
        if (firstRejection == null) firstRejection = "The rule's applicability check failed.";
        continue;
      }
      add(trace, rule.id(), "applicability", applicability.passed() ? "passed" : "rejected",
          applicability.id() + ": " + applicability.message());
      if (!applicability.passed()) {
        if (firstRejection == null) firstRejection = applicability.message();
        continue;
      }

      List<RefactoringCheck> preconditions;
      try {
        preconditions = List.copyOf(rule.preconditions(freshContext(before)));
      } catch (RuntimeException error) {
        add(trace, rule.id(), "preconditions", "error", "Precondition evaluation failed: " + safeMessage(error));
        if (firstRejection == null) firstRejection = "Preconditions could not be evaluated.";
        continue;
      }
      boolean preconditionsPassed = recordChecks(trace, rule.id(), "precondition", preconditions);
      if (preconditions.isEmpty()) {
        add(trace, rule.id(), "precondition", "passed", "Rule declares no additional preconditions.");
      }
      if (!preconditionsPassed) {
        if (firstRejection == null) firstRejection = "One or more preconditions failed for " + rule.name() + ".";
        continue;
      }

      RefactoringExecution execution;
      try {
        execution = rule.execute(freshContext(before));
      } catch (RuntimeException error) {
        add(trace, rule.id(), "execution", "error", "Transformation execution failed: " + safeMessage(error));
        if (firstRejection == null) firstRejection = "Transformation execution failed.";
        continue;
      }
      if (execution == null || execution.sourceAfter() == null || execution.sourceAfter().equals(source)) {
        add(trace, rule.id(), "execution", "rejected", "Rule did not produce a changed source snapshot.");
        if (firstRejection == null) firstRejection = "The rule produced no source change.";
        continue;
      }
      add(trace, rule.id(), "execution", "completed", execution.summary());

      ParseResult<CompilationUnit> afterParse = new JavaParser(new ParserConfiguration()).parse(execution.sourceAfter());
      RefactoringCheck syntaxPostcondition = afterParse.isSuccessful() && afterParse.getResult().isPresent()
          ? RefactoringCheck.pass("transformed-source-parses", "Transformed source parses as a Java compilation unit.")
          : RefactoringCheck.fail("transformed-source-parses", "Transformed source has Java syntax errors.");
      if (!syntaxPostcondition.passed()) {
        add(trace, rule.id(), "postcondition", "rejected", syntaxPostcondition.message());
        if (firstRejection == null) firstRejection = "The transformed source failed the syntax postcondition.";
        continue;
      }

      List<RefactoringCheck> postconditions;
      try {
        List<RefactoringCheck> allPostconditions = new ArrayList<>();
        allPostconditions.add(syntaxPostcondition);
        allPostconditions.addAll(rule.postconditions(freshContext(before), execution));
        postconditions = List.copyOf(allPostconditions);
      } catch (RuntimeException error) {
        add(trace, rule.id(), "postcondition", "error", "Postcondition evaluation failed: " + safeMessage(error));
        if (firstRejection == null) firstRejection = "Postconditions could not be evaluated.";
        continue;
      }
      boolean postconditionsPassed = recordChecks(trace, rule.id(), "postcondition", postconditions);
      if (postconditions.isEmpty()) {
        add(trace, rule.id(), "postcondition", "passed", "Rule declares no structural postconditions.");
      }
      if (!postconditionsPassed) {
        if (firstRejection == null) firstRejection = "One or more postconditions failed for " + rule.name() + ".";
        continue;
      }

      add(trace, rule.id(), "acceptance", "accepted", "Proposal passed parsing, rule preconditions, and structural postconditions; awaiting compile and regression validation.");
      return success(source, execution, rule, preconditions, postconditions, trace);
    }

    String message = candidates.isEmpty()
        ? "No refactoring rules are registered."
        : firstRejection == null ? "No registered rule supports this source." : firstRejection;
    add(trace, "framework", "acceptance", "rejected", "No transformation was accepted; the original source remains unchanged.");
    Map<String, Object> failure = failure(message, trace);
    failure.put("availableRules", availableRules());
    return failure;
  }

  private Map<String, Object> success(String source, RefactoringExecution execution, RefactoringRule rule,
      List<RefactoringCheck> preconditions, List<RefactoringCheck> postconditions,
      List<RefactoringTraceEntry> trace) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", true);
    result.put("ruleId", rule.id());
    result.put("ruleName", rule.name());
    result.put("message", execution.summary());
    result.put("original", source);
    result.put("refactored", execution.sourceAfter());
    result.putAll(execution.details());
    result.put("preconditions", preconditions.stream().map(RefactoringCheck::toMap).toList());
    result.put("postconditions", postconditions.stream().map(RefactoringCheck::toMap).toList());
    result.put("decisionTrace", trace.stream().map(RefactoringTraceEntry::toMap).toList());
    result.put("diff", SafetyEvidence.unifiedDiff(source, execution.sourceAfter()));
    result.put("safetyEvidence", SafetyEvidence.assess(source, execution.sourceAfter(), preconditions, postconditions));
    result.put("beforeMetrics", new AnalysisService().analyze(source).get("qualityMetrics"));
    result.put("afterMetrics", new AnalysisService().analyze(execution.sourceAfter()).get("qualityMetrics"));
    result.put("refactoring", Map.of(
        "name", rule.name(),
        "ruleId", rule.id(),
        "reason", execution.summary(),
        "changes", execution.details().getOrDefault("changes", List.of()),
        "limitations", List.of("Structural checks and compilation do not prove semantic equivalence.", "Review the diff and run relevant regression tests.")));
    result.put("note", "Proposal passed syntax and rule-specific structural checks. Compilation and regression tests are still required; neither alone is a formal proof of semantic equivalence.");
    return result;
  }

  private Map<String, Object> failure(String message, List<RefactoringTraceEntry> trace) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("success", false);
    result.put("message", message);
    result.put("decisionTrace", trace.stream().map(RefactoringTraceEntry::toMap).toList());
    return result;
  }

  private boolean recordChecks(List<RefactoringTraceEntry> trace, String ruleId,
      String stage, List<RefactoringCheck> checks) {
    boolean allPassed = true;
    for (RefactoringCheck check : checks) {
      add(trace, ruleId, stage, check.passed() ? "passed" : "rejected", check.id() + ": " + check.message());
      allPassed &= check.passed();
    }
    return allPassed;
  }

  private void add(List<RefactoringTraceEntry> trace, String ruleId, String stage, String status, String detail) {
    trace.add(new RefactoringTraceEntry(Instant.now(), ruleId, stage, status, detail));
  }

  private String safeMessage(RuntimeException error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private RefactoringContext freshContext(RefactoringContext original) {
    return new RefactoringContext(original.source(), original.compilationUnit().clone());
  }
}
