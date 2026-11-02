package com.semanticrefactoring;

import java.util.LinkedHashMap;
import java.util.Map;

/** A named precondition or postcondition with evidence for human review. */
public record RefactoringCheck(String id, boolean passed, String message, int line) {
  public static RefactoringCheck pass(String id, String message) {
    return new RefactoringCheck(id, true, message, -1);
  }

  public static RefactoringCheck fail(String id, String message) {
    return new RefactoringCheck(id, false, message, -1);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id);
    value.put("passed", passed);
    value.put("message", message);
    value.put("line", line);
    return value;
  }
}
