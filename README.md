# Explainable Java Refactoring Studio

A local-first research prototype for exploring explainable Java refactoring. It combines a JavaParser refactoring-rule engine, constrained rule-based Extract Method, optional one-shot local model proposals through Ollama, single-file compiler checks, basic before/after measurements, JUnit regression checks, PMD analysis, JaCoCo coverage reporting, and a downloadable Markdown report.

## What the prototype does

- Opens, edits, and saves one `.java` source file in the browser.
- Parses Java syntax and reports top-level types, fields, and methods.
- Shows transparent heuristic findings for long methods, high estimated cyclomatic complexity, and methods with many parameters. Each finding includes its rule, observed value, threshold, location, and recommendation.
- Reports source lines, method count, average and highest estimated cyclomatic complexity.
- Previews rule-based Extract Method and Extract String Constant proposals through the reusable `RefactoringRule` lifecycle and registry. Each candidate is checked for applicability and preconditions, transformed on a cloned syntax tree, then checked for syntax and structural postconditions.
- Records each proposal's applicability, precondition, execution, and postcondition decisions in a timestamped trace. A proposal passing structural checks is ready for validation; that is not proof of semantic equivalence.
- Optionally asks a model installed in **local Ollama** for a refactoring proposal based on a goal you provide. The source is sent only to the Ollama service on this computer; the proposal is parsed and syntax-checked before it is shown.
- Shows original and proposed source, checks compilation before applying either proposal, detects stale previews, and lets you download an explanation and measurements as a Markdown report.
- Accepts an explicitly selected Maven project ZIP and runs its `mvn test` lifecycle in a temporary folder. Archive size, extracted size, file count, output, and execution time are bounded.

## Use the rule-based workflow

1. Start the server with `scripts/start.ps1` or the Maven command below, then open [http://127.0.0.1:8765](http://127.0.0.1:8765).
2. Load the sample or open one Java source file.
3. Select **Analyze code** to see the configured findings and measurements.
4. Select **Rule-based preview** for the narrowly supported Extract Method example. Review both complete source versions.
5. Select **Compile and apply to editor** only after reviewing the proposal. A passing compile check is not proof of behavioral equivalence.
6. Optionally select a Maven project ZIP and run project compilation and JUnit tests. Passing tests provide observed evidence for tested cases only.
7. Download the Markdown report to keep a record of findings, proposal trace, metrics, compile result, and project-test result.

## Optional local AI workflow

Install and start [Ollama](https://ollama.com/) on this computer, then install a code-capable model using Ollama's instructions. The studio checks the local model list at `127.0.0.1:11434`; select **Refresh models** if Ollama starts after the page is opened.

Enter a specific goal such as “extract the repeated validation into a private helper,” choose the installed model, and ask for a preview. The model returns a complete proposed source file and an explanation. The app rejects empty or syntactically invalid proposals, but model explanations can be wrong. Inspect the complete result, compile it, and run regression tests before relying on it. See [Ollama's API documentation](https://github.com/ollama/ollama/blob/main/docs/api.md).

## Run on Windows

The scripts use the portable JDK and Maven locations described in `START-HERE.txt` when present, with system tools as a fallback. From this project folder run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start.ps1"
```

Keep the terminal open while using the app; press Ctrl+C to stop the server. To run the JUnit regression suite and generate PMD and JaCoCo reports:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\verify.ps1"
```

JaCoCo writes its HTML report to `target/site/jacoco/index.html`; PMD writes its report to `target/site/pmd.html`. These reports describe the current prototype and do not establish broad semantic preservation.

The first build may need network access to download Maven dependencies. The server binds to `127.0.0.1` and source requests are limited to 1 MB. Project ZIP uploads are limited to 50 MB and extracted projects to 300 MB; project validation has a five-minute time limit.

## Metric and validation definitions

Cyclomatic complexity is estimated per method as `1 + if/loop/catch/conditional-expression nodes + && / || operators` found in that method's syntax tree. Source-line count is the number of physical lines in the submitted file. These are simple static measurements, not a composite quality score; the analyzer can miss issues and produce false positives.

The editor's compiler check compiles one source file with JDK 21. The separate Maven ZIP validator resolves and tests an uploaded project, but does not automatically apply a proposal to that project or compare before/after runtime behavior. Neither check proves semantic equivalence. The browser workbench does not write arbitrary local paths through the server; browser file-picker permissions control file opening and saving.

## Current scope and next research steps

- The optional AI feature requires a separately installed local Ollama model. There is no hosted AI provider or model bundled with this project.
- AI and rule-based proposals are suggestions for review. There is no formal semantic-equivalence proof or automated behavioral test generation.
- The editor still analyzes and edits one source file at a time. Project validation runs Maven compilation/tests against an uploaded project, but does not automatically apply a proposal to that project or compare old/new project behavior.
- The JUnit suite covers analysis, registry selection, lifecycle gates, and representative rule cases. Test counts and results from the verification run do not establish equivalence for arbitrary Java programs. HTTP endpoint and browser workflow tests remain future work.
- Spring Boot and the React/TypeScript frontend are target architecture decisions, not integrated in this checkout yet. The built-in Java HTTP server and browser workbench remain in place while the refactoring engine is separated and tested.
- Quality measurement is currently limited to source lines, method/parameter counts, and syntax-based complexity; duplication, maintainability indices, and empirical evaluation are not implemented.
