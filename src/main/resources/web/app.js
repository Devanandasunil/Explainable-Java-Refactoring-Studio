(() => {
  const editor = document.getElementById("source");
  const output = document.getElementById("output");
  const sample = `public class Student {
    public void process() {
        System.out.println("Preparing student data");
        System.out.println("Processing complete");
    }
}
`;
  let fileHandle = null;
  let fileName = "Student.java";
  let savedSource = sample;
  let hasSavedFile = false;
  let analysisState = null;
  let refactorState = null;
  let decisionTraceState = null;
  let compileState = null;
  let projectValidationState = null;

  editor.value = sample;
  updateLineNumbers();

  function updateLineNumbers() {
    document.getElementById("numbers").textContent = Array.from(
      {length: Math.max(1, editor.value.split("\n").length)}, (_, index) => index + 1
    ).join("\n");
  }

  function updateFileLabels() {
    const clean = editor.value === savedSource;
    const label = clean ? (hasSavedFile ? "saved" : "sample · not saved yet") : "unsaved changes";
    document.getElementById("filepath").textContent = `${fileHandle ? fileHandle.name : fileName} · ${label}`;
    document.getElementById("saveStatus").textContent = clean ? (hasSavedFile ? "● Saved" : "● Sample not saved") : "● Unsaved changes";
    document.getElementById("tabFilename").textContent = fileName;
    document.getElementById("sidebarFile").textContent = "J　" + fileName;
  }

  function markAnalysisStale() {
    const status = document.getElementById("analysisStatus");
    status.textContent = analysisState ? "Analysis is out of date · analyze again" : "Analysis not run";
    if (analysisState) status.classList.add("stale");
  }

  editor.addEventListener("input", () => {
    updateLineNumbers();
    updateFileLabels();
    markAnalysisStale();
    compileState = null;
  });

  document.getElementById("source").addEventListener("keyup", event => {
    const before = editor.value.slice(0, editor.selectionStart);
    const line = before.split("\n").length;
    const column = before.length - before.lastIndexOf("\n");
    document.getElementById("cursor").textContent = `Ln ${line}, Col ${column}`;
  });

  async function post(path, button, label) {
    if (button) {
      button.disabled = true;
      button.dataset.oldLabel = button.textContent;
      button.textContent = label;
    }
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: {"Content-Type": "text/plain; charset=utf-8"},
        body: editor.value
      });
      const result = await response.json();
      if (!response.ok) throw new Error(result.error || `Request failed (${response.status})`);
      return result;
    } catch (error) {
      output.textContent = "Could not contact the local analysis service. Check that the server is running.\n" + error.message;
      return null;
    } finally {
      if (button) {
        button.disabled = false;
        button.textContent = button.dataset.oldLabel;
      }
    }
  }

  function renderAnalysis(result) {
    document.getElementById("types").textContent = result.types.length;
    document.getElementById("methods").textContent = result.methods.length;
    document.getElementById("findingsCount").textContent = result.findings.length;
    document.getElementById("structCount").textContent = result.types.length;
    document.getElementById("findCount").textContent = result.findings.length;

    const metrics = result.qualityMetrics;
    const quality = document.getElementById("qualityMetrics");
    quality.className = "metric-list";
    quality.replaceChildren();
    const rows = [
      ["Source lines", metrics.sourceLines],
      ["Average decision complexity", metrics.averageCyclomaticComplexity],
      ["Highest decision complexity", metrics.highestCyclomaticComplexity]
    ];
    rows.forEach(([label, value]) => {
      const row = document.createElement("div");
      row.className = "metric-row";
      const name = document.createElement("span");
      name.textContent = label;
      const amount = document.createElement("b");
      amount.textContent = value;
      row.append(name, amount);
      quality.append(row);
    });
    const formula = document.createElement("small");
    formula.textContent = "Complexity estimate: 1 + if/loop/catch/conditional branches + && / || operators. Syntax-based estimate; not a quality score.";
    quality.append(formula);

    const structure = document.getElementById("structure");
    structure.className = result.types.length ? "" : "empty";
    structure.replaceChildren();
    if (!result.types.length) structure.textContent = "No top-level types found.";
    result.types.forEach(type => {
      const card = document.createElement("div");
      card.className = "item";
      const title = document.createElement("b");
      title.textContent = `${type.name} · ${type.kind}`;
      card.append(title, document.createTextNode("Fields: " + (type.fields.join(", ") || "none")));
      result.methods.filter(method => method.className === type.name).forEach(method => {
        const detail = document.createElement("div");
        detail.textContent = `ƒ ${method.name}() · ${method.parameters} parameter(s) · complexity ${method.cyclomaticComplexity}`;
        card.append(detail);
      });
      structure.append(card);
    });

    const findings = document.getElementById("findingList");
    findings.replaceChildren();
    findings.className = result.findings.length ? "" : "empty";
    if (!result.findings.length) findings.textContent = "No configured rule triggered. This does not prove the code has no issues.";
    result.findings.forEach(finding => {
      const card = document.createElement("div");
      card.className = "item finding";
      const title = document.createElement("b");
      title.textContent = finding.title + " ";
      const badge = document.createElement("span");
      badge.className = "severity";
      badge.textContent = finding.severity;
      title.append(badge);
      const evidence = document.createElement("p");
      evidence.textContent = `${finding.location}${finding.line > 0 ? ` · line ${finding.line}` : ""} · ${finding.reason}`;
      const recommendation = document.createElement("p");
      recommendation.textContent = "Recommendation: " + finding.recommendation;
      const rule = document.createElement("small");
      rule.textContent = `Rule: ${finding.ruleId} · observed ${finding.observed}, threshold ${finding.threshold}`;
      card.append(title, evidence, recommendation, rule);
      findings.append(card);
    });

    const suggestions = document.getElementById("suggestions");
    suggestions.replaceChildren();
    result.suggestions.forEach(text => {
      const item = document.createElement("div");
      item.className = "item";
      item.textContent = "↗ " + text;
      suggestions.append(item);
    });
  }

  document.getElementById("analyze").addEventListener("click", async event => {
    output.textContent = "Analyzing Java syntax and configured rules…";
    const result = await post("/api/analyze", event.currentTarget, "Analyzing…");
    if (!result) return;
    if (!result.success) {
      output.textContent = "Parse failed:\n" + (result.problems || []).join("\n");
      return;
    }
    analysisState = {result, source: editor.value, timestamp: new Date()};
    renderAnalysis(result);
    const status = document.getElementById("analysisStatus");
    status.textContent = "Analysis is current";
    status.classList.remove("stale");
    output.textContent = result.summary + "\n\nMeasurements and warnings are syntax-based review aids. They do not prove a defect or behavior preservation.";
    updateReportButton();
  });

  document.getElementById("compile").addEventListener("click", async event => {
    output.textContent = "Checking whether this single source file compiles with the configured JDK…";
    const result = await post("/api/compile", event.currentTarget, "Checking…");
    if (!result) return;
    compileState = {result, source: editor.value, timestamp: new Date()};
    output.textContent = (result.success ? "✓ COMPILE SUCCESS\n\n" : "✕ COMPILE FAILED\n\n") + result.output + "\n\nThis single-file compile check does not run the program or its regression tests.";
    updateReportButton();
  });

  function confirmDiscard() {
    return editor.value === savedSource || confirm("This file has unsaved changes. Continue and discard them?");
  }

  async function openFile() {
    if (!confirmDiscard()) return;
    if (window.showOpenFilePicker) {
      try {
        const [handle] = await window.showOpenFilePicker({multiple: false, types: [{description: "Java source", accept: {"text/x-java": [".java"]}}]});
        const file = await handle.getFile();
        editor.value = await file.text();
        fileHandle = handle;
        fileName = file.name;
        savedSource = editor.value;
        hasSavedFile = true;
        editor.dispatchEvent(new Event("input"));
        output.textContent = `Opened ${file.name}. Analyze the file before reviewing or exporting findings.`;
      } catch (error) { if (error.name !== "AbortError") output.textContent = "Could not open file: " + error.message; }
      return;
    }
    document.getElementById("fileInput").click();
  }

  document.getElementById("fileInput").addEventListener("change", async event => {
    const file = event.target.files[0];
    if (!file) return;
    editor.value = await file.text();
    fileHandle = null;
    fileName = file.name;
    savedSource = editor.value;
    hasSavedFile = true;
    editor.dispatchEvent(new Event("input"));
    output.textContent = `Opened ${file.name}. Save As downloads your changes in this browser.`;
    event.target.value = "";
  });

  async function writeFile(handle) {
    const writable = await handle.createWritable();
    await writable.write(editor.value);
    await writable.close();
    fileHandle = handle;
    fileName = handle.name;
    savedSource = editor.value;
    hasSavedFile = true;
    updateFileLabels();
    output.textContent = `Saved ${fileName}.`;
  }

  function downloadSource() {
    const blob = new Blob([editor.value], {type: "text/x-java;charset=utf-8"});
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName.endsWith(".java") ? fileName : "Edited.java";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    savedSource = editor.value;
    hasSavedFile = true;
    updateFileLabels();
    output.textContent = `Downloaded ${link.download}.`;
  }

  async function saveAs() {
    if (!window.showSaveFilePicker) return downloadSource();
    try {
      const handle = await window.showSaveFilePicker({suggestedName: fileName, types: [{description: "Java source", accept: {"text/x-java": [".java"]}}]});
      await writeFile(handle);
    } catch (error) { if (error.name !== "AbortError") output.textContent = "Could not save file: " + error.message; }
  }

  document.getElementById("openFile").addEventListener("click", openFile);
  document.getElementById("saveFile").addEventListener("click", () => fileHandle ? writeFile(fileHandle).catch(error => output.textContent = "Could not save file: " + error.message) : saveAs());
  document.getElementById("saveAs").addEventListener("click", saveAs);
  document.getElementById("load").addEventListener("click", () => {
    if (!confirmDiscard()) return;
    editor.value = sample;
    fileHandle = null;
    fileName = "Student.java";
    savedSource = sample;
    hasSavedFile = false;
    editor.dispatchEvent(new Event("input"));
    output.textContent = "Sample loaded. Analyze it, preview the supported refactoring, or open your own Java file.";
  });
  document.getElementById("clear").addEventListener("click", () => output.textContent = "Output cleared.");

  function updateReportButton() {
    document.getElementById("downloadReport").disabled = !analysisState && !refactorState && !compileState;
  }

  function metricLines(title, metrics) {
    if (!metrics) return "";
    return `\n### ${title}\n- Source lines: ${metrics.sourceLines}\n- Types: ${metrics.typeCount}\n- Methods: ${metrics.methodCount}\n- Average cyclomatic complexity: ${metrics.averageCyclomaticComplexity}\n- Highest cyclomatic complexity: ${metrics.highestCyclomaticComplexity}\n- Heuristic findings: ${metrics.findingsCount}\n`;
  }

  function markdownCodeBlock(language, source) {
    const longest = Math.max(2, ...Array.from(source.matchAll(/`+/g), match => match[0].length));
    const fence = "`".repeat(longest + 1);
    return `${fence}${language}\n${source}${source.endsWith("\n") ? "" : "\n"}${fence}`;
  }

  function decisionTraceLines(entries, message) {
    if (!entries?.length) return "";
    return "\n## Refactoring decision trace\n" + (message ? `\n${message}\n\n` : "") + entries.map(entry =>
      `- ${entry.timestamp} · ${entry.ruleId} · ${entry.stage} · ${entry.status}: ${entry.detail}`
    ).join("\n") + "\n";
  }

  function createReport() {
    const current = analysisState;
    const lines = [
      "# Explainable Java Refactoring Studio — Review Report",
      "",
      `- File: ${fileName}`,
      `- Generated: ${new Date().toLocaleString()}`,
      `- Analysis source matches current editor: ${current ? String(current.source === editor.value) : "No analysis available"}`,
      "",
      "## Scope",
      "This report is generated from a syntax-based, single-file workbench. Heuristic findings are prompts for human review, not proof of defects. Compile success does not prove semantic equivalence. Optional AI proposals use a model installed in local Ollama; there is no hosted AI provider or bundled model. Maven project validation runs separately on the uploaded archive and is not an automatic before/after comparison of this proposal.",
      current ? metricLines("Measured source quality", current.result.qualityMetrics) : "",
      current ? "\n## Source analyzed\n" + markdownCodeBlock("java", current.source) + "\n" : "",
      current ? "\n## Findings\n" + (current.result.findings.length ? current.result.findings.map(item => `- **${item.title} (${item.severity})** — ${item.location}${item.line > 0 ? `, line ${item.line}` : ""}. ${item.reason} Rule: ${item.ruleId}; observed ${item.observed}, threshold ${item.threshold}. ${item.recommendation}`).join("\n") : "No configured heuristic triggered; this does not establish that the code is issue-free.") : "",
      refactorState ? "\n## Refactoring proposal\n" + `- Transformation: ${refactorState.proposal.refactoring?.name || refactorState.proposal.ruleName || "Refactoring rule"}\n- Provider/model: ${refactorState.proposal.provider ? `${refactorState.proposal.provider} · ${refactorState.proposal.model}` : "Built-in rule"}\n- Requested goal: ${refactorState.proposal.goal || "Registered rule applicability"}\n- Explanation: ${refactorState.proposal.explanation || refactorState.proposal.refactoring?.reason || refactorState.proposal.message}\n- Compile check: ${refactorState.compile ? (refactorState.compile.success ? "passed" : "failed") : "not run"}\n- Applied to editor: ${refactorState.applied ? "yes" : "no"}\n- Limit: compilation is not a behavior-equivalence proof; run regression tests.\n` + metricLines("Before proposal", refactorState.proposal.beforeMetrics) + metricLines("After proposal", refactorState.proposal.afterMetrics) + `\n### Original source\n${markdownCodeBlock("java", refactorState.proposal.original)}\n\n### Proposed source\n${markdownCodeBlock("java", refactorState.proposal.refactored)}\n` : "",
      decisionTraceState ? decisionTraceLines(decisionTraceState.entries, decisionTraceState.message) : "",
      compileState ? `\n## Last compile check\n- Result: ${compileState.result.success ? "passed" : "failed"}\n- Scope: single source file only; program and regression tests were not executed.\n- Output: ${compileState.result.output.replace(/\r?\n/g, " ")}\n` : "",
      projectValidationState ? `\n## Maven project validation\n- Archive: ${projectValidationState.fileName}\n- Result: ${projectValidationState.result.success ? "passed" : "failed"}\n- Summary: ${projectValidationState.result.summary}\n- JUnit executions reported: ${projectValidationState.result.testsRun}\n- Failures: ${projectValidationState.result.testFailures}; errors: ${projectValidationState.result.testErrors}\n- Elapsed: ${(projectValidationState.result.elapsedMillis / 1000).toFixed(1)} seconds\n- Output truncated: ${projectValidationState.result.outputTruncated ? "yes" : "no"}\n\n### Build output\n${markdownCodeBlock("text", projectValidationState.result.output)}\n` : "",
      "\n## Metric definition\nCyclomatic complexity is estimated as 1 plus if, loop, catch, and conditional-expression nodes and boolean && / || operators found in each method AST. Source lines are physical lines in the submitted file. These simple measurements do not capture all maintainability or behavior concerns."
    ];
    return lines.filter(Boolean).join("\n");
  }

  document.getElementById("downloadReport").addEventListener("click", () => {
    const blob = new Blob([createReport()], {type: "text/markdown;charset=utf-8"});
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName.replace(/\.java$/i, "") + "-refactoring-report.md";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  });

  const archiveInput = document.getElementById("projectArchive");
  const validateProjectButton = document.getElementById("validateProject");
  const validationStatus = document.getElementById("projectValidationStatus");
  const validationOutput = document.getElementById("projectValidationOutput");
  archiveInput.addEventListener("change", () => {
    const file = archiveInput.files[0];
    validateProjectButton.disabled = !file || file.size === 0 || file.size > 50 * 1024 * 1024;
    if (!file) validationStatus.textContent = "Choose a project ZIP file.";
    else if (file.size > 50 * 1024 * 1024) validationStatus.textContent = "The ZIP exceeds the 50 MB upload limit.";
    else validationStatus.textContent = `${file.name} selected. Project commands run only when you start validation.`;
  });
  validateProjectButton.addEventListener("click", async () => {
    const file = archiveInput.files[0];
    if (!file) return;
    validateProjectButton.disabled = true;
    validateProjectButton.textContent = "Building and running tests…";
    validationStatus.textContent = "Maven is compiling the project and running its tests. This can take up to five minutes.";
    validationOutput.hidden = true;
    try {
      const response = await fetch("/api/project/validate", {
        method: "POST",
        headers: {"Content-Type": "application/zip"},
        body: file
      });
      const result = await response.json();
      if (!response.ok && result.success === undefined) throw new Error(result.message || `Request failed (${response.status})`);
      projectValidationState = {fileName: file.name, result, timestamp: new Date()};
      validationStatus.textContent = result.summary || result.message || "Project validation completed.";
      validationOutput.textContent = result.output || result.message || "No build output was returned.";
      validationOutput.hidden = false;
      updateReportButton();
    } catch (error) {
      validationStatus.textContent = "Project validation could not run: " + error.message;
    } finally {
      validateProjectButton.disabled = !archiveInput.files[0] || archiveInput.files[0].size > 50 * 1024 * 1024;
      validateProjectButton.textContent = "Compile project and run JUnit tests";
    }
  });

  window.studioRecordRefactor = (proposal, compileResult, applied) => {
    refactorState = {proposal, compile: compileResult, applied};
    updateReportButton();
  };
  window.studioRecordDecisionTrace = (entries, message) => {
    decisionTraceState = entries?.length ? {entries, message} : null;
    updateReportButton();
  };
  updateFileLabels();
})();
