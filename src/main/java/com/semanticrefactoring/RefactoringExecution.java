package com.semanticrefactoring;

import java.util.LinkedHashMap;
import java.util.Map;

/** Candidate output returned by a rule before validation-gated acceptance. */
public record RefactoringExecution(String sourceAfter, String summary, Map<String, Object> details) {
  public RefactoringExecution {
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("summary", summary);
    value.putAll(details);
    return value;
  }
}
