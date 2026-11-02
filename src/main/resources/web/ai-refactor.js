(() => {
  const modelSelect = document.getElementById("aiModel");
  const goalInput = document.getElementById("refactorGoal");
  const runButton = document.getElementById("aiRefactor");
  const refreshButton = document.getElementById("aiRefresh");
  const status = document.getElementById("aiStatus");
  const editor = document.getElementById("source");
  const output = document.getElementById("output");
  if (!modelSelect || !runButton || !refreshButton) return;

  async function loadModels() {
    refreshButton.disabled = true;
    status.textContent = "Checking for models in local Ollama…";
    try {
      const response = await fetch("/api/ai/models");
      const result = await response.json();
      modelSelect.replaceChildren();
      if (!result.success || !result.models.length) {
        const option = document.createElement("option");
        option.textContent = "No local models available";
        option.value = "";
        modelSelect.append(option);
        modelSelect.disabled = true;
        runButton.disabled = true;
        status.textContent = result.message || "Install a local model in Ollama, then refresh.";
        return;
      }
      result.models.forEach(model => {
        const option = document.createElement("option");
        option.value = model;
        option.textContent = model;
        modelSelect.append(option);
      });
      modelSelect.disabled = false;
      runButton.disabled = false;
      status.textContent = "Optional AI runs through Ollama on this computer. Review and validate every proposal.";
    } catch (error) {
      modelSelect.replaceChildren();
      const option = document.createElement("option");
      option.textContent = "Local AI unavailable";
      option.value = "";
      modelSelect.append(option);
      modelSelect.disabled = true;
      runButton.disabled = true;
      status.textContent = "Could not reach local Ollama. Start it and refresh models.";
    } finally {
      refreshButton.disabled = false;
    }
  }

  refreshButton.addEventListener("click", loadModels);
  runButton.addEventListener("click", async () => {
    const goal = goalInput.value.trim();
    if (!goal) {
      status.textContent = "Describe the change you want first, such as ‘extract repeated validation into a helper’.";
      goalInput.focus();
      return;
    }
    runButton.disabled = true;
    refreshButton.disabled = true;
    runButton.textContent = "Generating preview…";
    status.textContent = "The local model is preparing a proposal. Large models may take a little while.";
    output.textContent = "Asking the selected local model for a refactoring proposal…";
    try {
      const response = await fetch("/api/ai/refactor", {
        method: "POST",
        headers: {
          "Content-Type": "text/plain; charset=utf-8",
          "X-Ollama-Model": modelSelect.value,
          "X-Refactor-Goal": encodeURIComponent(goal)
        },
        body: editor.value
      });
      const result = await response.json();
      if (!response.ok || !result.success) throw new Error(result.message || `Request failed (${response.status})`);
      if (!window.studioShowRefactorProposal) throw new Error("The preview panel could not be opened. Reload the page and try again.");
      window.studioShowRefactorProposal(result);
      status.textContent = `Proposal generated with ${result.model}. Review it before applying.`;
    } catch (error) {
      output.textContent = "The local AI proposal could not be prepared.\n" + error.message;
      status.textContent = error.message;
    } finally {
      runButton.disabled = !modelSelect.value;
      refreshButton.disabled = false;
      runButton.textContent = "Ask local AI for preview";
    }
  });

  loadModels();
})();
