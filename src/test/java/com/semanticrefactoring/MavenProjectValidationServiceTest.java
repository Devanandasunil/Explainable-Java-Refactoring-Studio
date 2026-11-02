package com.semanticrefactoring;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MavenProjectValidationServiceTest {
  private final MavenProjectValidationService service = new MavenProjectValidationService();

  @Test
  void rejectsArchiveWithoutMavenProject() throws IOException {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.validate(zip("notes.txt", "not a Maven project")));
    assertTrue(error.getMessage().contains("No pom.xml"));
  }

  @Test
  void rejectsZipEntryThatEscapesTemporaryWorkspace() throws IOException {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> service.validate(zip("../outside.txt", "must not be written")));
    assertTrue(error.getMessage().contains("unsafe path"));
  }

  private byte[] zip(String name, String contents) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }
}
