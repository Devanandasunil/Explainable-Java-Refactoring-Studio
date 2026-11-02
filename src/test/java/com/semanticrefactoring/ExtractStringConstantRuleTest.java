package com.semanticrefactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class ExtractStringConstantRuleTest {
  private static final String SAMPLE = """
      public class ConstantDemo {
        public static void main(String[] args) {
          show();
        }
        private static String label() {
          return "processing";
        }
        private static void show() {
          System.out.println("processing");
        }
      }
      """;

  @Test
  void introducesConstantAndPreservesObservedOutput() throws Exception {
    var proposal = new RefactoringEngine(List.of(new ExtractStringConstantRule())).propose(SAMPLE);
    assertTrue(Boolean.TRUE.equals(proposal.get("success")));
    String changed = (String) proposal.get("refactored");
    assertTrue(changed.contains("private static final String EXTRACTED_STRING = \"processing\""));
    assertTrue(((List<?>) proposal.get("postconditions")).stream().allMatch(item -> item.toString().contains("passed=true")));
    assertEquals(compileAndRun(SAMPLE), compileAndRun(changed));
  }

  @Test
  void avoidsExistingIdentifiers() {
    String source = """
        class NameCollision {
          private static final String EXTRACTED_STRING = "existing";
          String first() { return "same"; }
          String second() { return "same"; }
        }
        """;
    var proposal = new RefactoringEngine(List.of(new ExtractStringConstantRule())).propose(source);
    assertTrue(Boolean.TRUE.equals(proposal.get("success")));
    assertTrue(((String) proposal.get("refactored")).contains("EXTRACTED_STRING_2"));
  }

  @Test
  void declinesUniqueLiteralsAndInterfaces() {
    String unique = "class Unique { String value() { return \"only once\"; } }";
    String iface = "interface Unsupported { default String value() { return \"same\" + \"same\"; } }";
    ExtractStringConstantRule rule = new ExtractStringConstantRule();
    assertFalse(rule.checkApplicability(new RefactoringContext(unique,
        new com.github.javaparser.JavaParser().parse(unique).getResult().orElseThrow())).passed());
    assertFalse(rule.checkApplicability(new RefactoringContext(iface,
        new com.github.javaparser.JavaParser().parse(iface).getResult().orElseThrow())).passed());
  }

  private String compileAndRun(String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("Tests require a JDK, not a JRE.");
    Path dir = Files.createTempDirectory("constant-regression-");
    PrintStream oldOut = System.out;
    try {
      Path file = dir.resolve("ConstantDemo.java");
      Files.writeString(file, source, StandardCharsets.UTF_8);
      ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
      int exit = compiler.run(null, diagnostics, diagnostics, "-proc:none", "--release", "21", "-d", dir.toString(), file.toString());
      assertEquals(0, exit, diagnostics.toString(StandardCharsets.UTF_8));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, null);
           PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(capture);
        Class<?> demo = Class.forName("ConstantDemo", true, loader);
        demo.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
      } finally {
        System.setOut(oldOut);
      }
      return output.toString(StandardCharsets.UTF_8);
    } finally {
      System.setOut(oldOut);
      try (var paths = Files.walk(dir)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> {
          try { Files.deleteIfExists(path); } catch (Exception ignored) { }
        });
      }
    }
  }
}
