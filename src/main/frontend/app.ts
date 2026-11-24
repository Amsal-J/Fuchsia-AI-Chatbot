// FuchsiaAI file upload page (TypeScript source)
//
// This file is compiled to app.js by `mvn compile` (npm run build). Edit this file, never
// the generated app.js. The browser only runs the compiled JavaScript.
//
// Flow: click "Deposit File" -> OS file explorer opens (starting in Downloads where the
// browser allows it) -> each chosen file is listed in green -> each file is sent to the
// server one at a time -> the line stays green on success or turns red on failure.

// ---------- Integration point ----------
// Where the page sends files. Each file goes in its own request (matches the unary gRPC upload).
// Request:  POST UPLOAD_URL
//           Content-Type: application/octet-stream
//           X-File-Name: <file name, URI-encoded>
//           body = raw file bytes
// Response: any 2xx = uploaded. Anything else = failed; the response body text (if any)
//           is shown to the user as the reason, e.g. 409 "A file named 'x' already exists".
// Served and handled by fuchsia.WebServer, which forwards each file to the gRPC server.
const UPLOAD_URL = "/api/upload";

// Matches FileIngestionClient.MAX_FILE_BYTES (gRPC's 4 MB message limit minus 1 KB).
// Checked here so oversized files are rejected before they are sent.
const MAX_FILE_BYTES = 4 * 1024 * 1024 - 1024;

// ---------- Types ----------

type MessageKind = "ok" | "error";

type UploadResult =
  | { ok: true }
  | { ok: false; reason: string };

// showOpenFilePicker isn't in TypeScript's built-in browser types (only Chrome and Edge
// have it), so describe the part this page uses. Optional because other browsers lack it.
interface OpenFilePickerOptions {
  startIn?: "desktop" | "documents" | "downloads" | "music" | "pictures" | "videos";
  multiple?: boolean;
}

interface Window {
  showOpenFilePicker?: (options?: OpenFilePickerOptions) => Promise<FileSystemFileHandle[]>;
}

// ---------- Page elements ----------

// Looks up an element and fails loudly if index.html and this file ever drift apart.
function requireElement<T extends HTMLElement>(id: string, type: new () => T): T {
  const element = document.getElementById(id);
  if (!(element instanceof type)) {
    throw new Error(`Missing #${id} (expected ${type.name}) in index.html`);
  }
  return element;
}

const depositButton = requireElement("deposit-button", HTMLButtonElement);
const fileInput = requireElement("file-input", HTMLInputElement);
const statusList = requireElement("status", HTMLUListElement);

// ---------- Choosing files ----------

// showOpenFilePicker is the only browser API that can ask the file explorer to start in
// Downloads. Chrome and Edge have it (on https or localhost). Firefox and Safari don't,
// so they use the hidden <input type="file">, which opens where the OS last left off.
depositButton.addEventListener("click", async () => {
  const showOpenFilePicker = window.showOpenFilePicker;
  if (showOpenFilePicker === undefined) {
    fileInput.click();
    return;
  }

  try {
    const handles = await showOpenFilePicker({
      startIn: "downloads",
      multiple: true
      // File type filters go here once valid types are decided (the "types" option).
    });
    const files = await Promise.all(handles.map((handle) => handle.getFile()));
    await depositFiles(files);
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") {
      return; // user closed the file explorer without choosing anything
    }
    const message = err instanceof Error ? err.message : String(err);
    showMessage("Could not open the file explorer: " + message, "error");
  }
});

fileInput.addEventListener("change", async () => {
  const files = Array.from(fileInput.files ?? []);
  fileInput.value = ""; // reset so choosing the same file again still fires "change"
  await depositFiles(files);
});

// ---------- Uploading ----------

// Lists every chosen file, then uploads them one after another.
async function depositFiles(files: File[]): Promise<void> {
  if (files.length === 0) {
    return;
  }

  statusList.replaceChildren(); // clear messages from the previous deposit
  depositButton.disabled = true;

  const lines = files.map((file) =>
    showMessage("Selected: " + file.name + " (" + formatSize(file.size) + ") - uploading...", "ok")
  );

  try {
    for (const [i, file] of files.entries()) {
      const line = lines[i];

      if (file.size > MAX_FILE_BYTES) {
        setMessage(line, file.name + " did not upload: file is too large (max 4 MB).", "error");
        continue;
      }

      const result = await uploadFile(file);
      if (result.ok) {
        setMessage(line, file.name + " uploaded successfully.", "ok");
      } else {
        setMessage(line, file.name + " did not upload: " + result.reason, "error");
      }
    }
  } finally {
    depositButton.disabled = false;
  }
}

// Sends one file to the server. This is the only function that talks to the backend.
async function uploadFile(file: File): Promise<UploadResult> {
  let response: Response;
  try {
    response = await fetch(UPLOAD_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-File-Name": encodeURIComponent(file.name)
      },
      body: file
    });
  } catch {
    return { ok: false, reason: "could not reach the server." };
  }

  if (response.ok) {
    return { ok: true };
  }

  let reason = "";
  try {
    reason = (await response.text()).trim();
  } catch {
    // ignore, fall back to the status code below
  }
  if (reason === "") {
    reason = "server responded with status " + response.status + ".";
  }
  return { ok: false, reason };
}

// ---------- Helpers ----------

function showMessage(text: string, kind: MessageKind): HTMLLIElement {
  const line = document.createElement("li");
  setMessage(line, text, kind);
  statusList.appendChild(line);
  return line;
}

function setMessage(line: HTMLLIElement, text: string, kind: MessageKind): void {
  line.textContent = text; // textContent, never innerHTML: file names are untrusted
  line.className = kind === "ok" ? "status-ok" : "status-error";
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / (1024 * 1024)).toFixed(1) + " MB";
}