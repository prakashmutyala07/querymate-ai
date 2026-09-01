const STORAGE_KEY = "querymate-ai-conversation-id";

const state = {
  conversationId: window.localStorage.getItem(STORAGE_KEY) || "",
  busy: false
};

const thread = document.querySelector("#thread");
const emptyState = document.querySelector("#emptyState");
const chatForm = document.querySelector("#chatForm");
const input = document.querySelector("#messageInput");
const sendButton = document.querySelector("#sendButton");
const connectionStatus = document.querySelector("#connectionStatus");
const modelName = document.querySelector("#modelName");
const toolCount = document.querySelector("#toolCount");

loadTools();

input.addEventListener("input", autoGrow);
input.addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    chatForm.requestSubmit();
  }
});

document.querySelectorAll(".suggestion").forEach((button) => {
  button.addEventListener("click", () => {
    input.value = button.textContent;
    autoGrow();
    chatForm.requestSubmit();
  });
});

document.querySelector("#newConversationButton").addEventListener("click", () => {
  state.conversationId = "";
  window.localStorage.removeItem(STORAGE_KEY);
  thread.replaceChildren(emptyState);
  thread.classList.remove("has-messages");
  emptyState.hidden = false;
  modelName.textContent = "Waiting for model";
});

document.querySelector("#clearMemoryButton").addEventListener("click", async () => {
  if (!state.conversationId || state.busy) return;
  await fetch(`/api/conversations/${encodeURIComponent(state.conversationId)}/memory`, { method: "DELETE" });
  connectionStatus.textContent = "Conversation memory cleared";
});

chatForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = input.value.trim();
  if (!text || state.busy) return;

  emptyState.hidden = true;
  thread.classList.add("has-messages");
  addUserTurn(text);
  input.value = "";
  autoGrow();
  setBusy(true);

  const turn = addAssistantTurn();
  try {
    await streamChat(text, turn);
  } catch (error) {
    turn.finish();
    turn.renderError("Chat request failed. Check that DAB and the model provider are reachable.");
  } finally {
    setBusy(false);
  }
});

async function loadTools() {
  try {
    const response = await fetch("/api/mcp/tools");
    if (!response.ok) throw new Error(String(response.status));
    const tools = await response.json();
    connectionStatus.textContent = "Connected";
    if (toolCount) toolCount.textContent = String(tools.length);
  } catch {
    connectionStatus.textContent = "DAB unavailable";
    if (toolCount) toolCount.textContent = "0";
  }
}

/** Reads the SSE stream from the POST response and drives one assistant turn. */
async function streamChat(message, turn) {
  const response = await fetch("/api/chat/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message, conversationId: state.conversationId || null })
  });
  if (!response.ok || !response.body) throw new Error(String(response.status));

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    const frames = buffer.split("\n\n");
    buffer = frames.pop() ?? "";
    for (const frame of frames) {
      let event = "message";
      const dataLines = [];
      for (const line of frame.split("\n")) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      }
      if (!dataLines.length) continue;
      let payload;
      try { payload = JSON.parse(dataLines.join("")); } catch { continue; }

      if (event === "progress") turn.step(payload.message);
      else if (event === "complete") { turn.finish(); turn.renderAnswer(payload); }
      else if (event === "error") { turn.finish(); turn.renderError(payload.message || "Request failed."); }
    }
  }
  turn.finish();
}

function addUserTurn(text) {
  const wrap = document.createElement("div");
  wrap.className = "turn";
  const bubble = document.createElement("div");
  bubble.className = "bubble-user";
  bubble.textContent = text;
  wrap.append(bubble);
  thread.append(wrap);
  scrollToEnd();
}

/**
 * One assistant turn. Progress events stay in the assistant message as a
 * vertical thinking timeline, followed by the final answer/table content.
 */
function addAssistantTurn() {
  const wrap = document.createElement("div");
  wrap.className = "turn";

  const card = document.createElement("div");
  card.className = "assistant-message";

  const topline = document.createElement("div");
  topline.className = "assistant-topline";
  const name = document.createElement("span");
  name.className = "assistant-name";
  name.textContent = "QueryMate AI";
  const meta = document.createElement("span");
  meta.textContent = "Thinking";
  const dots = document.createElement("span");
  dots.className = "loading-dots";
  dots.setAttribute("aria-hidden", "true");
  dots.append(document.createElement("span"), document.createElement("span"), document.createElement("span"));
  topline.append(name, meta, dots);

  const list = document.createElement("ol");
  list.className = "steps";

  const stepToggle = document.createElement("button");
  stepToggle.type = "button";
  stepToggle.className = "step-toggle";
  stepToggle.textContent = "Show execution steps";
  stepToggle.hidden = true;
  stepToggle.addEventListener("click", () => {
    const expanded = card.classList.toggle("show-steps");
    stepToggle.textContent = expanded ? "Hide execution steps" : "Show execution steps";
  });

  card.append(topline, stepToggle, list);
  wrap.append(card);
  thread.append(wrap);
  scrollToEnd();

  let current = null;
  let complete = false;

  return {
    step(text) {
      if (!text) return;
      if (current) current.classList.remove("active");
      const item = document.createElement("li");
      item.className = "step active";
      const dot = document.createElement("span");
      dot.className = "dot";
      const label = document.createElement("span");
      label.textContent = text;
      item.append(dot, label);
      list.append(item);
      current = item;
      scrollToEnd();
    },

    finish() {
      if (complete) return;
      complete = true;
      if (current) current.classList.remove("active");
      meta.textContent = "Answer ready";
      dots.hidden = true;
      if (list.children.length) {
        card.classList.add("is-complete");
        stepToggle.hidden = false;
      }
    },

    renderAnswer(payload) {
      if (payload.conversationId) {
        state.conversationId = payload.conversationId;
        window.localStorage.setItem(STORAGE_KEY, payload.conversationId);
      }
      if (payload.model) {
        modelName.textContent = payload.fallbackUsed ? `${payload.model} (fallback)` : payload.model;
      }

      if (payload.message) {
        const p = document.createElement("div");
        p.className = payload.status === "ERROR" ? "answer error" : "answer";
        p.textContent = payload.message;
        card.append(p);
      }

      if (payload.columns?.length && payload.rows?.length) {
        card.append(renderTable(payload.columns, payload.rows));
      }

      const notes = [];
      if (payload.partialResults) notes.push("Results may be incomplete.");
      if (payload.dataNotes) notes.push(payload.dataNotes);
      if (payload.followUpQuestion) notes.push(payload.followUpQuestion);
      if (notes.length) {
        const note = document.createElement("div");
        note.className = "notes";
        if (!payload.usedDatabaseTools) {
          const badge = document.createElement("span");
          badge.className = "badge";
          badge.textContent = "no tool data";
          note.append(badge);
        }
        note.append(document.createTextNode(notes.join(" ")));
        card.append(note);
      }
      scrollToEnd();
    },

    renderError(text) {
      meta.textContent = "Request failed";
      dots.hidden = true;
      const p = document.createElement("div");
      p.className = "answer error";
      p.textContent = text;
      card.append(p);
      scrollToEnd();
    }
  };
}

function renderTable(columns, rows) {
  const wrap = document.createElement("div");
  wrap.className = "table-wrap";
  const table = document.createElement("table");

  const thead = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const column of columns) {
    const th = document.createElement("th");
    th.textContent = column;
    headRow.append(th);
  }
  thead.append(headRow);

  const tbody = document.createElement("tbody");
  for (const row of rows) {
    const tr = document.createElement("tr");
    for (let i = 0; i < columns.length; i += 1) {
      const td = document.createElement("td");
      td.textContent = row[i] ?? "";
      tr.append(td);
    }
    tbody.append(tr);
  }

  table.append(thead, tbody);
  wrap.append(table);
  return wrap;
}

function setBusy(busy) {
  state.busy = busy;
  sendButton.disabled = busy;
  input.disabled = busy;
  if (!busy) input.focus();
}

function autoGrow() {
  const maxHeight = 176;
  input.style.height = "auto";
  const nextHeight = Math.min(input.scrollHeight, maxHeight);
  input.style.height = `${nextHeight}px`;
  input.classList.toggle("is-scrollable", input.scrollHeight > maxHeight);
  input.style.overflowY = input.scrollHeight > maxHeight ? "auto" : "hidden";
}

function scrollToEnd() {
  thread.scrollTo({ top: thread.scrollHeight, behavior: "smooth" });
}
