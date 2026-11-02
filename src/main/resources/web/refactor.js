(() => {
  const editor = document.getElementById("source");
  const output = document.getElementById("output");
  const button = document.getElementById("refactor");
  if (!editor || !output || !button) return;

  let proposed = null;
  let proposedFrom = null;
  let currentProposal = null;

  function recordDecision(stage, status, detail) {
    const entry = {
      timestamp: new Date().toISOString(),
      ruleId: currentProposal?.ruleId || currentProposal?.provider || "proposal",
      stage,
      status,
      detail
    };
    const entries = [...(currentProposal?.decisionTrace || []), entry];
    if (currentProposal) currentProposal.decisionTrace = entries;
    if (window.studioRecordDecisionTrace) window.studioRecordDecisionTrace(entries, currentProposal?.message);
    return entry;
  }

  const panel = document.createElement("section");
  panel.id = "refactorPreview";
  panel.className = "refactor-preview";
  panel.hidden = true;
  panel.innerHTML = `
    <h3 id="refactorTitle">Refactoring preview</h3>
    <p id="refactorMessage"></p>
    <label for="refactorOriginal">Original code</label>
    <textarea id="refactorOriginal" readonly spellcheck="false"></textarea>
    <label for="refactorChanged">Proposed code</label>
    <textarea id="refactorChanged" readonly spellcheck="false"></textarea>
    <p id="refactorNote"></p>
    <details id="decisionTracePanel" hidden><summary>Decision trace</summary><ol id="decisionTraceList" class="trace-list"></ol></details>
    <button id="applyRefactor" type="button">Compile and apply to editor</button>
    <button id="cancelRefactor" type="button">Cancel</button>`;
  document.querySelector(".editor").appendChild(panel);

  const byId = id => document.getElementById(id);

  function showProposal(result) {
    proposed = result.refactored;
    proposedFrom = editor.value;
    currentProposal = result;
    if (window.studioRecordRefactor) window.studioRecordRefactor(result, null, false);
    if (window.studioRecordDecisionTrace) window.studioRecordDecisionTrace(result.decisionTrace || [], result.message);
    byId("refactorTitle").textContent = result.provider ? `Local model proposal · ${result.model}` : `Rule-based proposal · ${result.ruleName || result.refactoring?.name || "Refactoring rule"}`;
    byId("refactorOriginal").value = result.original;
    byId("refactorChanged").value = result.refactored;
    byId("refactorMessage").textContent = result.explanation ? `${result.message} ${result.explanation}` : result.message;
    byId("refactorNote").textContent = result.note;
    const tracePanel = byId("decisionTracePanel");
    const traceList = byId("decisionTraceList");
    traceList.replaceChildren();
    tracePanel.hidden = !result.decisionTrace?.length;
    (result.decisionTrace || []).forEach(entry => {
      const item = document.createElement("li");
      item.textContent = `${entry.stage} · ${entry.status}: ${entry.detail}`;
      traceList.append(item);
    });
    panel.hidden = false;
    output.textContent = "Preview ready. Your editor has not been changed.";
    panel.scrollIntoView({behavior: "smooth", block: "start"});
  }
  window.studioShowRefactorProposal = showProposal;

  button.addEventListener("click", async () => {
    button.disabled = true;
    output.textContent = "Preparing refactoring preview...";
    panel.hidden = true;
    proposed = null;

    try {
      const response = await fetch("/api/refactor", {
        method: "POST",
        headers: {"Content-Type": "text/plain; charset=utf-8"},
        body: editor.value
      });
      const result = await response.json();

      if (!result.success) {
        if (window.studioRecordDecisionTrace) window.studioRecordDecisionTrace(result.decisionTrace || [], result.message);
        const decisions = (result.decisionTrace || []).map(entry => `${entry.stage} · ${entry.status}: ${entry.detail}`).join("\n");
        output.textContent = [result.message || "No safe refactoring proposal available.", decisions].filter(Boolean).join("\n\n");
        return;
      }

      showProposal(result);
    } catch (error) {
      output.textContent = "Could not prepare preview: " + error.message;
    } finally {
      button.disabled = false;
    }
  });

  byId("applyRefactor").addEventListener("click", async () => {
    if (proposed === null) return;
    if (editor.value !== proposedFrom) {
      panel.hidden = true;
      proposed = null;
      output.textContent = "The editor changed after this preview. Create a new preview before applying.";
      return;
    }
    const apply = byId("applyRefactor");
    apply.disabled = true;
    apply.textContent = "Checking compile…";
    recordDecision("single-file-compile", "started", "Compiling the proposed source with the configured JDK before editing the browser buffer.");
    try {
      const response = await fetch("/api/compile", {
        method: "POST",
        headers: {"Content-Type": "text/plain; charset=utf-8"},
        body: proposed
      });
      const check = await response.json();
      if (window.studioRecordRefactor) window.studioRecordRefactor(
        currentProposal, check, false
      );
      if (!check.success) {
        recordDecision("single-file-compile", "failed", "The compiler rejected the proposal; the editor source was left unchanged.");
        recordDecision("editor-acceptance", "rolled_back", "Proposal was not applied because compilation failed.");
        output.textContent = "The proposal did not compile, so it was not applied.\n" + check.output;
        return;
      }
      recordDecision("single-file-compile", "passed", "The proposed source compiled successfully. This checks compilation only, not behavioral equivalence.");
      editor.value = proposed;
      editor.dispatchEvent(new Event("input", {bubbles: true}));
      recordDecision("editor-acceptance", "applied", "User applied the compile-checked proposal to the browser editor. Regression validation remains separate.");
      if (window.studioRecordRefactor) window.studioRecordRefactor(currentProposal, check, true);
      panel.hidden = true;
      output.textContent = "Compile check passed and the proposal was applied to the editor. Save when ready and run regression tests. Compile success does not prove behavior is unchanged.";
      proposed = null;
    } catch (error) {
      recordDecision("editor-acceptance", "rolled_back", "Compile validation could not complete; the editor source was left unchanged.");
      output.textContent = "Compile check could not run; the proposal was not applied. " + error.message;
    } finally {
      apply.disabled = false;
      apply.textContent = "Compile and apply to editor";
    }
  });

  byId("cancelRefactor").addEventListener("click", () => {
    recordDecision("editor-acceptance", "cancelled", "User cancelled the proposal; the editor source was left unchanged.");
    panel.hidden = true;
    proposed = null;
    output.textContent = "Preview cancelled. The editor was not changed.";
  });
})();
