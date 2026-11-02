package com.semanticrefactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RefactoringEngineTest {
  @Test
  void exposesRegisteredRulesAndReturnsFirstApplicableProposal() {
    RefactoringRule unsupported = rule("first", "First rule", false);
    RefactoringRule supported = rule("second", "Second rule", true);
    RefactoringRule later = rule("third", "Third rule", true);
    RefactoringEngine engine = new RefactoringEngine(List.of(unsupported, supported, later));

    assertEquals(3, engine.availableRules().size());
    Map<String, Object> proposal = engine.propose("class Example {}");
    assertEquals(Boolean.TRUE, proposal.get("success"));
    assertEquals("second", proposal.get("ruleId"));
  }

  @Test
  void returnsAllApplicableProposalsForAlternativeSelection() {
    RefactoringEngine engine = new RefactoringEngine(List.of(
        rule("one", "One", true), rule("two", "Two", false), rule("three", "Three", true)));

    List<Map<String, Object>> proposals = engine.proposeAll("class Example {}");
    assertEquals(List.of("one", "three"), proposals.stream().map(p -> p.get("ruleId")).toList());
  }

  @Test
  void proposalIncludesReviewDiffAndExplicitlyUnmeasuredEvidence() {
    String source = "class Example { void a() { System.out.println(\"same\"); } void b() { System.out.println(\"same\"); } }";
    Map<String, Object> proposal = new RefactoringEngine(List.of(new ExtractStringConstantRule())).propose(source);

    assertEquals(Boolean.TRUE, proposal.get("success"));
    assertTrue(String.valueOf(proposal.get("diff")).contains("+"));
    Map<?, ?> evidence = (Map<?, ?>) proposal.get("safetyEvidence");
    assertEquals(100, evidence.get("maximum"));
    Map<?, ?> components = (Map<?, ?>) evidence.get("components");
    assertEquals("not-run", ((Map<?, ?>) components.get("compile")).get("status"));
    assertEquals("not-run", ((Map<?, ?>) components.get("regressionTests")).get("status"));
    assertEquals("not-measured", ((Map<?, ?>) components.get("testCoverage")).get("status"));
  }

  @Test
  void explainsWhenNoRegisteredRuleSupportsSource() {
    Map<String, Object> result = new RefactoringEngine(List.of(rule("none", "No match", false)))
        .propose("class Example {}");
    assertFalse((Boolean) result.get("success"));
    assertTrue(String.valueOf(result.get("message")).contains("No supported block"));
    assertTrue(result.containsKey("availableRules"));
    assertTrue(((List<?>) result.get("decisionTrace")).stream().anyMatch(item ->
        item.toString().contains("applicability")));
  }

  @Test
  void failedPreconditionPreventsTransformationExecution() {
    AtomicBoolean executed = new AtomicBoolean(false);
    RefactoringEngine engine = new RefactoringEngine(List.of(new RefactoringRule() {
      @Override public String id() { return "precondition-failure"; }
      @Override public String name() { return "Precondition failure"; }
      @Override public RefactoringCheck checkApplicability(RefactoringContext context) {
        return RefactoringCheck.pass("fixture", "Applicable.");
      }
      @Override public List<RefactoringCheck> preconditions(RefactoringContext context) {
        return List.of(RefactoringCheck.fail("fixture", "Expected rejection"));
      }
      @Override public RefactoringExecution execute(RefactoringContext context) {
        executed.set(true);
        return new RefactoringExecution(context.source() + "// changed", "changed", Map.of());
      }
      @Override public List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution) {
        return List.of(RefactoringCheck.pass("fixture", "unused"));
      }
    }));

    Map<String, Object> result = engine.propose("class Example {}");
    assertFalse((Boolean) result.get("success"));
    assertFalse(executed.get());
  }

  @Test
  void failedPostconditionRejectsCandidateAndKeepsSourceUnchanged() {
    String source = "class Example {}";
    RefactoringEngine engine = new RefactoringEngine(List.of(new RefactoringRule() {
      @Override public String id() { return "postcondition-failure"; }
      @Override public String name() { return "Postcondition failure"; }
      @Override public RefactoringCheck checkApplicability(RefactoringContext context) {
        return RefactoringCheck.pass("fixture", "Applicable.");
      }
      @Override public List<RefactoringCheck> preconditions(RefactoringContext context) {
        return List.of(RefactoringCheck.pass("fixture", "Precondition passed"));
      }
      @Override public RefactoringExecution execute(RefactoringContext context) {
        return new RefactoringExecution(context.source() + "\n// candidate", "Candidate created", Map.of());
      }
      @Override public List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution) {
        return List.of(RefactoringCheck.fail("fixture", "Expected rejection"));
      }
    }));

    Map<String, Object> result = engine.propose(source);
    assertFalse((Boolean) result.get("success"));
    assertFalse(result.containsKey("refactored"));
    assertTrue(((List<?>) result.get("decisionTrace")).stream().anyMatch(item ->
        item.toString().contains("postcondition")));
  }

  private RefactoringRule rule(String id, String name, boolean applies) {
    return new RefactoringRule() {
      @Override public String id() { return id; }
      @Override public String name() { return name; }
      @Override public RefactoringCheck checkApplicability(RefactoringContext context) {
        return applies ? RefactoringCheck.pass("fixture", "Applicable.")
            : RefactoringCheck.fail("fixture", "No supported block found for " + name + ".");
      }
      @Override public List<RefactoringCheck> preconditions(RefactoringContext context) {
        return List.of(RefactoringCheck.pass("fixture", "Precondition passed"));
      }
      @Override public RefactoringExecution execute(RefactoringContext context) {
        return new RefactoringExecution(context.source() + "\n// " + id, "Fixture transformation", Map.of());
      }
      @Override public List<RefactoringCheck> postconditions(RefactoringContext before, RefactoringExecution execution) {
        return List.of(RefactoringCheck.pass("fixture", "Postcondition passed"));
      }
    };
  }
}
