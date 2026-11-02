package com.semanticrefactoring;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Explicitly invoked Maven project build/test runner with bounded archive extraction and process time. */
public final class MavenProjectValidationService {
  public static final int MAX_ARCHIVE_BYTES = 50 * 1024 * 1024;
  private static final long MAX_EXPANDED_BYTES = 300L * 1024 * 1024;
  private static final int MAX_ENTRIES = 20_000;
  private static final int MAX_OUTPUT_BYTES = 256 * 1024;
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(5);
  private static final Pattern TEST_SUMMARY = Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");

  public Map<String, Object> validate(byte[] archive) throws IOException, InterruptedException {
    if (archive == null || archive.length == 0) throw new IllegalArgumentException("Choose a non-empty Maven project ZIP file.");
    if (archive.length > MAX_ARCHIVE_BYTES) throw new IllegalArgumentException("The project archive exceeds the 50 MB limit.");

    Path workspace = Files.createTempDirectory("refactoring-project-validation-");
    try {
      extract(archive, workspace);
      Path pom = findPom(workspace);
      if (pom == null) throw new IllegalArgumentException("No pom.xml was found in the uploaded project archive.");
      Path projectRoot = pom.getParent();
      Path wrapper = projectRoot.resolve("mvnw.cmd");
      String maven = wrapper.toFile().isFile() ? wrapper.toString() : System.getenv().getOrDefault("STUDIO_MAVEN_CMD", "mvn.cmd");
      String localRepository = System.getenv("STUDIO_MAVEN_REPO");
      String command = quote(maven) + " -B -ntp" + (localRepository == null || localRepository.isBlank()
          ? "" : " -Dmaven.repo.local=" + quote(localRepository)) + " test";

      Process process = new ProcessBuilder("cmd.exe", "/d", "/c", command)
          .directory(projectRoot.toFile())
          .redirectErrorStream(true)
          .start();
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      AtomicBoolean truncated = new AtomicBoolean(false);
      Thread reader = Thread.ofVirtual().start(() -> captureOutput(process.getInputStream(), captured, truncated));
      long startedAt = System.nanoTime();
      boolean finished;
      try {
        finished = process.waitFor(PROCESS_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (InterruptedException interrupted) {
        stop(process);
        Thread.currentThread().interrupt();
        throw interrupted;
      }
      if (!finished) {
        stop(process);
      }
      reader.join(5_000);
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
      String output = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
      int exitCode = finished ? process.exitValue() : -1;
      int[] testCounts = summarizeTests(output);

      Map<String, Object> result = new LinkedHashMap<>();
      result.put("success", finished && exitCode == 0);
      result.put("buildTool", "Maven");
      result.put("compileAndTests", "mvn test");
      result.put("exitCode", exitCode);
      result.put("timedOut", !finished);
      result.put("testsRun", testCounts[0]);
      result.put("testFailures", testCounts[1]);
      result.put("testErrors", testCounts[2]);
      result.put("testsDetected", testCounts[0] > 0);
      result.put("outputTruncated", truncated.get());
      result.put("elapsedMillis", elapsedMillis);
      result.put("summary", summary(finished, exitCode, testCounts));
      result.put("output", output);
      return result;
    } finally {
      deleteTree(workspace);
    }
  }

  private void stop(Process process) {
    process.descendants().forEach(child -> {
      if (child.isAlive()) child.destroyForcibly();
    });
    if (process.isAlive()) process.destroyForcibly();
  }

  private void extract(byte[] archive, Path root) throws IOException {
    long expanded = 0;
    int entries = 0;
    try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("The archive contains too many files.");
        String name = entry.getName();
        if (unsafeEntryName(name)) throw new IllegalArgumentException("The archive contains an unsafe path: " + name);
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("The archive contains a path outside its project folder.");
        if (entry.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (var output = Files.newOutputStream(target)) {
          int count;
          while ((count = zip.read(buffer)) != -1) {
            expanded += count;
            if (expanded > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("The uncompressed project exceeds the 300 MB limit.");
            output.write(buffer, 0, count);
          }
        }
      }
    }
  }

  private boolean unsafeEntryName(String name) {
    if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.startsWith("/") || name.startsWith("\\")) return true;
    String portable = name.replace('\\', '/');
    if (portable.matches("^[A-Za-z]:.*")) return true;
    for (String part : portable.split("/")) if (part.equals("..")) return true;
    return false;
  }

  private Path findPom(Path root) throws IOException {
    try (var paths = Files.walk(root, 12)) {
      return paths.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().equalsIgnoreCase("pom.xml"))
          .min(Comparator.comparingInt(path -> root.relativize(path).getNameCount()))
          .orElse(null);
    }
  }

  private void captureOutput(InputStream input, ByteArrayOutputStream output, AtomicBoolean truncated) {
    try (input) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        synchronized (output) {
          int remaining = MAX_OUTPUT_BYTES - output.size();
          if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
          if (count > remaining) truncated.set(true);
        }
      }
    } catch (IOException ignored) {
      truncated.set(true);
    }
  }

  private int[] summarizeTests(String output) {
    int[] counts = new int[3];
    Matcher matcher = TEST_SUMMARY.matcher(output);
    while (matcher.find()) {
      counts[0] += Integer.parseInt(matcher.group(1));
      counts[1] += Integer.parseInt(matcher.group(2));
      counts[2] += Integer.parseInt(matcher.group(3));
    }
    return counts;
  }

  private String summary(boolean finished, int exitCode, int[] counts) {
    if (!finished) return "Project validation exceeded the five-minute limit and was stopped.";
    if (exitCode != 0) return "Maven project validation failed. Review the build output and failing tests.";
    if (counts[0] == 0) return "Maven project build passed, but no JUnit test executions were reported.";
    if (counts[1] + counts[2] > 0) return "Maven completed with failing or errored JUnit tests.";
    return "Maven project build and " + counts[0] + " reported JUnit test(s) passed.";
  }

  private String quote(String value) {
    return "\"" + value.replace("\"", "") + "\"";
  }

  private void deleteTree(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
      });
    } catch (IOException ignored) { }
  }
}
