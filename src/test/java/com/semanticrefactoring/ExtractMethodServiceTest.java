package com.semanticrefactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class ExtractMethodServiceTest {
  private static final String SAMPLE = """
      public class Demo {
        public static void main(String[] args) {
          run();
        }
        private static void run() {
          System.out.println("first");
          System.out.println("second");
        }
      }
      """;

  @Test
  void preservesOutputForSupportedStaticExtraction() throws Exception {
    MapResult proposal = new MapResult(new ExtractMethodService().propose(SAMPLE));
    assertTrue(proposal.success());
    assertTrue(proposal.refactored().contains("private static void extractedPrintSteps()"));

    assertEquals(compileAndRun(SAMPLE), compileAndRun(proposal.refactored()));
  }

  @Test
  void rejectsUnsupportedStatementsWithoutChangingSource() {
    String source = "class Demo { void run() { System.out.println(1); System.out.println(2); } }";
    var proposal = new ExtractMethodService().propose(source);
    assertEquals(Boolean.FALSE, proposal.get("success"));
    assertEquals("No parameterless void method ends with two literal System.out.println calls.", proposal.get("message"));
  }

  private String compileAndRun(String source) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("Tests require a JDK, not a JRE.");
    Path dir = Files.createTempDirectory("refactor-regression-");
    PrintStream oldOut = System.out;
    try {
      Path file = dir.resolve("Demo.java");
      Files.writeString(file, source, StandardCharsets.UTF_8);
      ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
      int exit = compiler.run(null, diagnostics, diagnostics, "-proc:none", "--release", "21", "-d", dir.toString(), file.toString());
      assertEquals(0, exit, diagnostics.toString(StandardCharsets.UTF_8));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, null);
           PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
        System.setOut(capture);
        Class<?> demo = Class.forName("Demo", true, loader);
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

  private record MapResult(java.util.Map<String, Object> value) {
    boolean success() { return Boolean.TRUE.equals(value.get("success")); }
    String refactored() { return (String) value.get("refactored"); }
  }
}
