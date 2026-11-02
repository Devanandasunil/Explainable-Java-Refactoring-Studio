package com.semanticrefactoring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Timestamped event in the explainable rule-decision and validation trace. */
public record RefactoringTraceEntry(Instant timestamp, String ruleId, String stage, String status, String detail) {
  public Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("timestamp", timestamp.toString());
    value.put("ruleId", ruleId);
    value.put("stage", stage);
    value.put("status", status);
    value.put("detail", detail);
    return value;
  }
}
