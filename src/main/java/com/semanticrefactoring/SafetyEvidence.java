package com.semanticrefactoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transparent proposal evidence summary. It is a review aid, never a correctness guarantee. */
public final class SafetyEvidence {
  private SafetyEvidence() { }

  public static Map<String, Object> assess(String before, String after,
      List<RefactoringCheck> preconditions, List<RefactoringCheck> postconditions) {
    int staticPoints = 0;
    int staticMaximum = 3;
    if (preconditions.stream().allMatch(RefactoringCheck::passed)) staticPoints++;
    if (postconditions.stream().allMatch(RefactoringCheck::passed)) staticPoints++;
    if (new AnalysisService().analyze(after).get("success").equals(Boolean.TRUE)) staticPoints++;
    int scopePoints = before.equals(after) ? 0 : 1;
    int score = staticPoints * 20 + scopePoints * 20;

    Map<String, Object> components = new LinkedHashMap<>();
    components.put("staticEvidence", Map.of("status", "measured", "points", staticPoints,
        "maximum", staticMaximum, "weightPerCheck", 20, "basis", "Rule preconditions, rule postconditions, and transformed-source parse."));
    components.put("changeScope", Map.of("status", "measured", "points", scopePoints,
        "maximum", 1, "weightPerCheck", 20, "basis", "Single-source proposal; this score does not infer project-wide reference impact."));
    components.put("compile", Map.of("status", "not-run", "points", 0, "maximum", 1, "weightPerCheck", 10));
    components.put("regressionTests", Map.of("status", "not-run", "points", 0, "maximum", 1, "weightPerCheck", 10));
    components.put("testCoverage", Map.of("status", "not-measured", "points", 0, "maximum", 0));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("score", score);
    result.put("maximum", 100);
    result.put("label", "Refactoring Safety Evidence Score");
    result.put("components", components);
    result.put("definition", "Weighted evidence coverage out of 100: three static checks at 20 points each, change-scope evidence at 20, compile at 10, and regression-test results at 10. Coverage is reported separately and is not scored.");
    result.put("interpretation", "A transparent summary of recorded evidence only. It is not a probability, quality rating, or proof of semantic equivalence.");
    return result;
  }

  /** Small line-oriented unified diff suitable for a single-file review panel. */
  public static String unifiedDiff(String before, String after) {
    String[] left = before.split("\\R", -1);
    String[] right = after.split("\\R", -1);
    StringBuilder diff = new StringBuilder("--- before.java\n+++ after.java\n");
    int prefix = 0;
    while (prefix < left.length && prefix < right.length && left[prefix].equals(right[prefix])) prefix++;
    int suffix = 0;
    while (suffix < left.length - prefix && suffix < right.length - prefix
        && left[left.length - 1 - suffix].equals(right[right.length - 1 - suffix])) suffix++;
    int oldCount = left.length - prefix - suffix + suffix;
    int newCount = right.length - prefix - suffix + suffix;
    int start = prefix + 1;
    diff.append("@@ -").append(start).append(',').append(oldCount)
        .append(" +").append(start).append(',').append(newCount).append(" @@\n");
    for (int i = prefix; i < left.length - suffix; i++) diff.append('-').append(left[i]).append('\n');
    for (int i = prefix; i < right.length - suffix; i++) diff.append('+').append(right[i]).append('\n');
    for (int i = left.length - suffix; i < left.length; i++) diff.append(' ').append(left[i]).append('\n');
    return diff.toString();
  }
}
