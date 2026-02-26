/* ═══════════════════════════════════════════════════════
   DocExtract — App Logic
   ═══════════════════════════════════════════════════════ */

'use strict';

// ── Config ──────────────────────────────────────────────
let API_BASE = localStorage.getItem('docextract_api_base') || 'http://localhost:8080';
document.getElementById('api-url-input').value = API_BASE;

function saveApiUrl() {
  API_BASE = document.getElementById('api-url-input').value.replace(/\/$/, '');
  localStorage.setItem('docextract_api_base', API_BASE);
  showToast('API URL saved!', 'success');
}

// ── State ────────────────────────────────────────────────
let selectedFile     = null;
let currentAckId     = null;
let duplicateAckId   = null;
let pollingInterval  = null;
let currentSheets    = {};
let activeSheet      = null;

// ── File Selection ───────────────────────────────────────

function onDragOver(e) {
  e.preventDefault();
  document.getElementById('drop-zone').classList.add('drag-over');
}
function onDragLeave(e) {
  document.getElementById('drop-zone').classList.remove('drag-over');
}
function onDrop(e) {
  e.preventDefault();
  document.getElementById('drop-zone').classList.remove('drag-over');
  const files = e.dataTransfer.files;
  if (files.length) setFile(files[0]);
}
function onFileSelected(e) {
  if (e.target.files.length) setFile(e.target.files[0]);
}

function setFile(file) {
  const ALLOWED = ['application/pdf', 'image/jpeg', 'image/png', 'image/tiff', 'image/webp', 'image/jpg'];
  const ALLOWED_EXT = /\.(pdf|jpg|jpeg|png|tiff|tif|webp)$/i;
  if (!ALLOWED.includes(file.type) && !ALLOWED_EXT.test(file.name)) {
    showToast('Unsupported file type. Please upload PDF, JPG, PNG, or TIFF.', 'error');
    return;
  }
  selectedFile = file;
  hideDuplicate();
  hideAckCard();

  // Show preview
  const ext = file.name.split('.').pop().toUpperCase();
  document.getElementById('file-icon').textContent = ext;
  document.getElementById('file-name').textContent = file.name;
  document.getElementById('file-size').textContent = formatSize(file.size);
  show('file-preview');
  enable('upload-btn');
}

function clearFile() {
  selectedFile = null;
  document.getElementById('file-input').value = '';
  hide('file-preview');
  disable('upload-btn');
  hideDuplicate();
  hideAckCard();
}

// ── Upload ───────────────────────────────────────────────

async function uploadDocument() {
  if (!selectedFile) return;

  disable('upload-btn');
  hideDuplicate();
  hideAckCard();
  hideResultSection();

  const formData = new FormData();
  formData.append('file', selectedFile);

  // Simulate upload progress with XHR
  show('upload-progress');
  setProgress(0, 'Uploading…');

  try {
    const response = await xhrUpload(`${API_BASE}/api/documents/upload`, formData, (pct) => {
      setProgress(pct, pct < 100 ? 'Uploading…' : 'Analysing…');
    });

    hide('upload-progress');

    if (response.duplicate) {
      // Show duplicate banner
      duplicateAckId = response.ackId;
      document.getElementById('duplicate-msg').textContent = response.message || 'Previously analysed document found.';
      show('duplicate-banner');
      enable('upload-btn');

      // Pre-fill retrieve input
      document.getElementById('ack-id-input').value = response.ackId;
      enable('fetch-btn');
      return;
    }

    // Success
    currentAckId = response.ackId;
    showAckCard(response.ackId, response.message);
    enable('upload-btn');

  } catch (err) {
    hide('upload-progress');
    enable('upload-btn');
    showToast('Upload failed: ' + err.message, 'error');
  }
}

function xhrUpload(url, formData, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', url);

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) {
        const pct = Math.round((e.loaded / e.total) * 90); // cap at 90% until response
        onProgress(pct);
      }
    };

    xhr.onload = () => {
      onProgress(100);
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText));
        } catch {
          reject(new Error('Invalid server response'));
        }
      } else {
        let msg = `HTTP ${xhr.status}`;
        try { msg = JSON.parse(xhr.responseText).message || msg; } catch {}
        reject(new Error(msg));
      }
    };

    xhr.onerror = () => reject(new Error('Network error. Check the API URL and try again.'));
    xhr.send(formData);
  });
}

// ── Duplicate Flow ───────────────────────────────────────

function viewExistingResult() {
  if (!duplicateAckId) return;
  document.getElementById('ack-id-input').value = duplicateAckId;
  enable('fetch-btn');
  hideDuplicate();
  document.getElementById('retrieve-section').scrollIntoView({ behavior: 'smooth' });
  fetchResult(duplicateAckId);
}

async function triggerReprocess() {
  if (!duplicateAckId) return;
  try {
    disable(document.getElementById('reprocess-btn'));
    const res = await apiFetch(`/api/documents/reprocess/${duplicateAckId}`, { method: 'POST' });
    hideDuplicate();
    currentAckId = duplicateAckId;
    showAckCard(currentAckId, res.message || 'Reprocessing started.');
    showToast('Reprocessing triggered!', 'info');
    document.getElementById('ack-id-input').value = currentAckId;
    enable('fetch-btn');
  } catch (err) {
    showToast('Reprocess failed: ' + err.message, 'error');
    enable(document.getElementById('reprocess-btn'));
  }
}

// ── Status Polling ───────────────────────────────────────

function onAckInput() {
  const val = document.getElementById('ack-id-input').value.trim();
  val ? enable('fetch-btn') : disable('fetch-btn');
}

async function fetchStatus(ackOverride) {
  const ackId = ackOverride || document.getElementById('ack-id-input').value.trim();
  if (!ackId) return;

  stopPolling();
  hideResultSection();
  show('status-card');

  try {
    const data = await apiFetch(`/api/documents/status/${ackId}`);
    renderStatusCard(data);

    if (data.status === 'PENDING' || data.status === 'PROCESSING') {
      startPolling(ackId);
    } else if (data.status === 'COMPLETED') {
      show('view-result-btn');
      document.getElementById('view-result-btn').onclick = () => fetchResult(ackId);
    }
  } catch (err) {
    show('status-card');
    document.getElementById('status-card').innerHTML =
      `<p style="color:var(--danger)">Error: ${err.message}</p>`;
  }
}

function renderStatusCard(data) {
  const badge = document.getElementById('status-badge');
  badge.textContent = data.status;
  badge.className = 'status-badge ' + data.status.toLowerCase();

  document.getElementById('status-file').textContent    = data.fileName || '—';
  document.getElementById('status-updated').textContent = data.updatedAt
      ? new Date(data.updatedAt).toLocaleString() : '—';

  const errEl = document.getElementById('status-error');
  if (data.error) {
    errEl.textContent = data.error;
    show('status-error');
  } else {
    hide('status-error');
  }

  const viewBtn = document.getElementById('view-result-btn');
  if (data.status === 'COMPLETED') {
    show('view-result-btn');
  } else {
    hide('view-result-btn');
  }
}

function startPolling(ackId) {
  show('polling-indicator');
  pollingInterval = setInterval(async () => {
    try {
      const data = await apiFetch(`/api/documents/status/${ackId}`);
      renderStatusCard(data);

      if (data.status === 'COMPLETED' || data.status === 'FAILED') {
        stopPolling();
        if (data.status === 'COMPLETED') {
          showToast('Document analysis complete! Click "View Extracted Data".', 'success');
          show('view-result-btn');
          document.getElementById('view-result-btn').onclick = () => fetchResult(ackId);
        } else {
          showToast('Processing failed: ' + (data.error || 'Unknown error'), 'error');
        }
      }
    } catch (err) {
      // Silently retry on network errors
    }
  }, 5000);
}

function stopPolling() {
  if (pollingInterval) {
    clearInterval(pollingInterval);
    pollingInterval = null;
  }
  hide('polling-indicator');
}

function startPollingFromUpload() {
  if (!currentAckId) return;
  document.getElementById('ack-id-input').value = currentAckId;
  enable('fetch-btn');
  document.getElementById('retrieve-section').scrollIntoView({ behavior: 'smooth' });
  fetchStatus(currentAckId);
}

// ── Result Fetching ──────────────────────────────────────

async function fetchResult(ackOverride) {
  const ackId = ackOverride || document.getElementById('ack-id-input').value.trim();
  if (!ackId) return;

  hide('view-result-btn');
  showToast('Loading results…', 'info');

  try {
    const data = await apiFetch(`/api/documents/result/${ackId}`);
    renderResult(data);
  } catch (err) {
    showToast('Failed to load result: ' + err.message, 'error');
    show('view-result-btn');
  }
}

function renderResult(data) {
  currentSheets = data.sheets || {};
  const sheetNames = Object.keys(currentSheets);

  if (!sheetNames.length) {
    showToast('No data found in result.', 'error');
    return;
  }

  // Download link
  if (data.downloadUrl) {
    const dl = document.getElementById('download-link');
    dl.href = data.downloadUrl;
    dl.style.display = '';
  }

  // Build sheet tabs
  const tabsEl = document.getElementById('sheet-tabs');
  tabsEl.innerHTML = '';
  sheetNames.forEach((name, idx) => {
    const tab = document.createElement('button');
    tab.className = 'sheet-tab' + (idx === 0 ? ' active' : '');
    tab.textContent = name;
    tab.dataset.sheet = name;
    tab.onclick = () => switchSheet(name);
    tabsEl.appendChild(tab);
  });

  show('result-section');
  activeSheet = sheetNames[0];
  renderTable(currentSheets[activeSheet] || []);
}

function switchSheet(name) {
  activeSheet = name;
  document.querySelectorAll('.sheet-tab').forEach(t => {
    t.classList.toggle('active', t.dataset.sheet === name);
  });
  renderTable(currentSheets[name] || []);
}

function renderTable(rows) {
  const thead = document.getElementById('table-head');
  const tbody = document.getElementById('table-body');
  thead.innerHTML = '';
  tbody.innerHTML = '';

  if (!rows.length) {
    tbody.innerHTML = '<tr><td colspan="100" class="no-data">No data in this sheet.</td></tr>';
    document.getElementById('row-count').textContent = '';
    return;
  }

  const headers = Object.keys(rows[0]);

  const headerRow = document.createElement('tr');
  headers.forEach(h => {
    const th = document.createElement('th');
    th.textContent = h;
    headerRow.appendChild(th);
  });
  thead.appendChild(headerRow);

  rows.forEach(row => {
    const tr = document.createElement('tr');
    headers.forEach(h => {
      const td = document.createElement('td');
      td.textContent = row[h] || '';
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });

  document.getElementById('row-count').textContent = `${rows.length} row${rows.length !== 1 ? 's' : ''}`;
}

// ── Helpers ──────────────────────────────────────────────

async function apiFetch(path, options = {}) {
  const url = API_BASE + path;
  const response = await fetch(url, {
    headers: { 'Content-Type': 'application/json', ...options.headers },
    ...options
  });
  const text = await response.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { message: text }; }
  if (!response.ok) throw new Error(data.error || data.message || `HTTP ${response.status}`);
  return data;
}

function show(id)    { const el = typeof id === 'string' ? document.getElementById(id) : id; el?.classList.remove('hidden'); }
function hide(id)    { const el = typeof id === 'string' ? document.getElementById(id) : id; el?.classList.add('hidden'); }
function enable(id)  { const el = typeof id === 'string' ? document.getElementById(id) : id; if (el) el.disabled = false; }
function disable(id) { const el = typeof id === 'string' ? document.getElementById(id) : id; if (el) el.disabled = true; }

function showAckCard(ackId, msg) {
  document.getElementById('ack-id-display').textContent = ackId;
  document.getElementById('ack-message').textContent = msg || '';
  show('ack-card');
}
function hideAckCard()     { hide('ack-card'); }
function hideDuplicate()   { hide('duplicate-banner'); duplicateAckId = null; }
function hideResultSection() { hide('result-section'); }

function copyAckId() {
  const id = document.getElementById('ack-id-display').textContent;
  navigator.clipboard.writeText(id).then(() => showToast('Copied!', 'success'));
}

function setProgress(pct, label) {
  document.getElementById('progress-fill').style.width = pct + '%';
  document.getElementById('progress-text').textContent = label;
  document.getElementById('progress-pct').textContent = pct + '%';
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

let toastTimeout = null;
function showToast(msg, type = 'info') {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = `toast ${type}`;
  el.classList.remove('hidden');
  if (toastTimeout) clearTimeout(toastTimeout);
  toastTimeout = setTimeout(() => el.classList.add('hidden'), 4000);
}
