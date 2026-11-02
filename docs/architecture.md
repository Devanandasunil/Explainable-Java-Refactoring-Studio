# Architecture and implementation status

The browser editor talks to a Java built-in `HttpServer` bound to `127.0.0.1:8765`. Static files are served from an allowlist. API requests accept at most 1 MB of Java source. The server never reads or writes arbitrary user-selected paths; browser file-picker permissions handle file access.

## Analysis

`AnalysisService` parses a single compilation unit with JavaParser. It reports top-level types, fields, and methods; source line count; method line, parameter, and top-level statement counts; and a syntax-based cyclomatic complexity estimate. Findings use three explicitly configured heuristics: more than 10 top-level statements, complexity greater than 10, and more than 5 parameters. Results include a rule ID, observed value, threshold, location, rationale, and recommendation.

The complexity estimate starts at 1 and adds `if`, loop, `catch`, conditional-expression, `&&`, and `||` nodes found in the method's AST. It is a lightweight indicator, not a standardized overall quality score.

## Refactoring

`RefactoringEngine` coordinates registered implementations of the `RefactoringRule` lifecycle. It can list rules, return the first applicable proposal for the current UI, or return every applicable proposal. Each rule provides applicability checks, preconditions, execution, and structural postconditions. `ExtractMethodService` supports only the final two literal `System.out.println(...)` statements in a parameterless `void` method. `ExtractStringConstantRule` replaces a repeated method-body string literal with a collision-free class constant. Each proposal operates on a cloned syntax tree and records lifecycle decisions in a timestamped trace. Proposals include a line-oriented unified diff and a Refactoring Safety Evidence Score. The score allocates 60 points to three static checks, 20 to single-file change-scope evidence, 10 to compile, and 10 to regression tests; only static and scope evidence are present at proposal time, so unrun validation is visibly marked and receives no points. Test coverage is reported as not measured. This is an evidence-coverage summary, not a probability or proof of behavior preservation. The UI rejects stale previews and offers a single-file JDK 21 compile check.

The optional `OllamaRefactoringService` lists installed models through the loopback Ollama API and requests a complete source proposal plus an explanation. Ollama output must be valid JSON and parse as a Java compilation unit. This checks response shape and syntax only; the user still reviews the result, compiles it, and runs relevant regression tests.

The Markdown report records rule findings, metric definitions and values, refactoring explanation, before/after measurements, compile-check scope/result, and lifecycle trace. It labels stale analysis when the editor source changes after analysis.

## Project validation

`MavenProjectValidationService` accepts an explicitly selected ZIP, extracts it to a temporary workspace with path traversal and size/count limits, locates a `pom.xml`, and runs `mvn test` with a five-minute timeout. Output is capped and temporary files are removed after the run. Uploaded Maven build plugins and tests execute as part of this user-triggered operation. The result reports Maven's exit status, test counts found in output, elapsed time, and output. A passing run is observed behavior on tested cases, not a formal proof of semantic equivalence. Gradle projects are not supported yet.

## Validation boundaries

- The compiler uses a temporary folder, disables annotation processing, and compiles one source file with `--release 21`.
- No program is executed by the server. User code is not run as a behavioral test.
- Project-level Maven compilation and regression-test execution are implemented. Gradle support, automatic before/after behavioral comparison, and formal semantic-equivalence checks are not implemented.
- Heuristic thresholds are configurable implementation choices and need empirical evaluation before making claims about defect detection or quality improvement.
- The JUnit suite covers analysis, rule registration/selection, lifecycle gates, and representative transformation cases. It does not establish behavior preservation for arbitrary programs. Project validation service, AI, server endpoints, and browser flows still need dedicated automated test coverage.
- Maven `verify` now generates PMD and JaCoCo reports. PMD is configured to report findings, not fail the build on all findings; JaCoCo coverage reflects only the current test set.

## Target architecture status

Java 21, JavaParser, JUnit 5, PMD, and JaCoCo are now used. Spring Boot and React/TypeScript are not yet integrated. Keep the existing single-file workflow usable while migrating the transport layer and UI in stages; the refactoring engine should remain a framework module independent of either presentation technology.

## Optional AI deployment

AI requests go only to `http://127.0.0.1:11434` using Ollama's local `/api/tags` and `/api/generate` endpoints. No external model provider is contacted and no model is bundled. If Ollama is not running or no model is installed, the rest of the application remains usable and the AI controls explain how to enable the optional feature.
