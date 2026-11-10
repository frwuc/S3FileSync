/* S3FileSync Tauri 前端逻辑
 * - 与后端通过 invoke 命令 + `state` 事件通信
 * - 状态栏常驻「下次自动同步」倒计时（不再被状态文本覆盖）
 */
"use strict";

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

/* ---------- 常量 ---------- */

const NAV_ITEMS = [
  { key: "settings", label: "设置", icon: '<path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.08a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.08a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.08a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"/>' },
  { key: "browser", label: "文件浏览", icon: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/>' },
  { key: "logs", label: "运行日志", icon: '<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>' },
  { key: "about", label: "关于", icon: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>' },
];

const QINIU_REGIONS = [
  ["z0", "华东"], ["cn-east-2", "华东-浙江2"], ["z1", "华北"],
  ["z2", "华南"], ["na0", "北美"], ["as0", "东南亚"],
];

const SYNC_MODES = [
  ["download", "仅下载（云端 → 本地）"],
  ["upload", "仅上传（本地 → 云端）"],
  ["both", "双向同步"],
];

/* ---------- 全局状态 ---------- */

const S = {
  snap: null,        // 后端快照（已保存配置 + 运行状态）
  tab: "settings",
  form: null,        // 设置页编辑中的表单
  dirty: false,
  showSk: false,
  browser: {
    prefix: "", loadedPrefix: "", items: [], nextMarker: null,
    markerStack: [], pageStart: null, selected: new Set(),
    sortKey: "name", sortDesc: false, busy: false, status: "尚未列举",
  },
  picker: null,      // { current, dirs, stack, busy, status }
  logs: [],
  logsFollow: true,
  modal: null,       // { title, msg, onOk } 或 null
};

/* ---------- 工具 ---------- */

const $ = (sel) => document.querySelector(sel);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function normPrefix(p) {
  return String(p ?? "").trim().replace(/\\/g, "/").replace(/^\/+/, "");
}

function humanSize(n) {
  n = Number(n) || 0;
  if (n < 1024) return n + " B";
  let v = n;
  for (const u of ["KB", "MB", "GB", "TB"]) {
    v /= 1024;
    if (Math.abs(v) < 1024) return v.toFixed(1) + " " + u;
  }
  return (v / 1024).toFixed(1) + " PB";
}

function fmtPutTime(t) {
  if (!t || t <= 0) return "—";
  const d = new Date(Math.floor(t / 10000));
  if (isNaN(d)) return "—";
  const p = (x) => String(x).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function fmtClock(ms) {
  const d = new Date(ms);
  const p = (x) => String(x).padStart(2, "0");
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

function fmtLeft(ms) {
  let s = Math.max(0, Math.round((ms - Date.now()) / 1000));
  if (s >= 3600) return `${Math.floor(s / 3600)} 小时 ${Math.floor((s % 3600) / 60)} 分`;
  if (s >= 60) return `${Math.floor(s / 60)} 分 ${s % 60} 秒`;
  return `${s} 秒`;
}

/* ---------- 对话框（tauri-plugin-dialog） ---------- */

function openDialog(options) {
  return invoke("plugin:dialog|open", { options });
}
function saveDialog(options) {
  return invoke("plugin:dialog|save", { options });
}

/* ---------- Toast / 弹窗 ---------- */

function toast(msg, type) {
  const el = document.createElement("div");
  el.className = "toast" + (type === "err" ? " err" : "");
  el.textContent = msg;
  $("#toast-root").appendChild(el);
  setTimeout(() => el.remove(), 3800);
}

function showModal(title, msg) {
  S.modal = { title, msg };
  renderModal();
}

function showConfirm(title, msg, onOk) {
  S.modal = { title, msg, onOk, confirm: true };
  renderModal();
}

function closeModal() {
  S.modal = null;
  renderModal();
}

function renderModal() {
  const root = $("#modal-root");
  if (S.picker) return renderPicker(root);
  if (!S.modal) { root.innerHTML = ""; return; }
  const m = S.modal;
  root.innerHTML = `
    <div class="overlay" id="ovl">
      <div class="dialog">
        <div class="dialog-title">${esc(m.title)}</div>
        <div class="dialog-body">${esc(m.msg)}</div>
        <div class="dialog-footer">
          ${m.confirm
            ? `<button class="btn btn-danger btn-sm" id="md-ok">${esc(m.okLabel || "确定")}</button>
               <button class="btn btn-sm" id="md-cancel">取消</button>`
            : `<button class="btn btn-sm" id="md-copy">复制</button>
               <button class="btn btn-primary btn-sm" id="md-ok">知道了</button>`}
        </div>
      </div>
    </div>`;
  $("#ovl").addEventListener("mousedown", (e) => { if (e.target.id === "ovl") closeModal(); });
  if (m.confirm) {
    $("#md-ok").addEventListener("click", () => { const f = m.onOk; closeModal(); f && f(); });
    $("#md-cancel").addEventListener("click", closeModal);
  } else {
    $("#md-copy").addEventListener("click", () => {
      navigator.clipboard.writeText(m.msg).then(() => toast("已复制到剪贴板"));
    });
    $("#md-ok").addEventListener("click", closeModal);
  }
}

/* ---------- 云端目录选择器 ---------- */

function pickerCfg() {
  const f = S.form;
  return {
    provider: f.provider === "s3" ? "s3" : "qiniu",
    ak: f.ak.trim(), sk: f.sk.trim(), bucket: f.bucket.trim(),
    region: f.region.trim(), download_domain: f.domain.trim(),
    s3_endpoint: f.endpoint.trim(), s3_region: f.s3_region.trim(),
    s3_path_style: !!f.path_style,
    download_dir: f.dir.trim(), interval_minutes: Math.max(1, +f.interval || 30),
    prefix: normPrefix(f.prefix), sync_mode: f.mode, upload_overwrite: !!f.overwrite,
    proxy_mode: f.proxy,
  };
}

function openPicker() {
  const f = S.form;
  if (!f.ak.trim() || !f.sk.trim() || !f.bucket.trim() || (f.provider === "s3" && !f.endpoint.trim())) {
    showModal("选择云端目录", "请先填写访问密钥（AK/SK）与桶名；S3 存储还需填写服务端端点。");
    return;
  }
  S.picker = { current: normPrefix(f.prefix), dirs: [], stack: [], busy: true, status: "正在列举…" };
  renderModal();
  invoke("list_dirs", { cfg: pickerCfg(), prefix: S.picker.current })
    .then((dirs) => pickerDirsDone(S.picker.current, dirs))
    .catch((e) => pickerFail(String(e)));
}

function pickerDirsDone(prefix, dirs) {
  if (!S.picker) return;
  if (S.picker.current !== prefix) return; // 已切到别的目录
  S.picker.busy = false;
  S.picker.dirs = dirs || [];
  S.picker.status = S.picker.dirs.length ? `${S.picker.dirs.length} 个子目录` : "该目录下没有子目录（可直接使用当前目录）";
  renderModal();
}

function pickerFail(msg) {
  if (!S.picker) return;
  S.picker.busy = false;
  S.picker.status = "列举失败: " + msg;
  renderModal();
}

function pickerLoad(prefix, pushStack) {
  const p = S.picker;
  if (!p || p.busy) return;
  if (pushStack) p.stack.push(p.current);
  p.current = prefix;
  p.busy = true;
  p.status = "正在列举…";
  p.dirs = [];
  renderModal();
  invoke("list_dirs", { cfg: pickerCfg(), prefix })
    .then((dirs) => pickerDirsDone(prefix, dirs))
    .catch((e) => pickerFail(String(e)));
}

function pickerEnter(dir) { pickerLoad(normPrefix(dir.replace(/\/+$/, "")), true); }

function pickerUp() {
  const p = S.picker;
  if (!p) return;
  let target;
  if (p.stack.length) target = p.stack.pop();
  else {
    const parts = p.current.split("/").filter(Boolean);
    parts.pop();
    target = parts.join("/");
  }
  p.current = target; // 不再入栈（本来就是上级）
  p.busy = true; p.status = "正在列举…"; p.dirs = [];
  renderModal();
  invoke("list_dirs", { cfg: pickerCfg(), prefix: target })
    .then((dirs) => pickerDirsDone(target, dirs))
    .catch((e) => pickerFail(String(e)));
}

function pickerRefresh() { pickerLoad(S.picker.current, false); }

function pickerChoose(prefix) {
  const v = normPrefix(String(prefix).replace(/\/+$/, ""));
  S.form.prefix = v;
  S.dirty = true;
  S.picker = null;
  renderModal();
  renderContent(); // 回填输入框
}

function closePicker() { S.picker = null; renderModal(); }

function renderPicker(root) {
  const p = S.picker;
  const relName = (dir) => {
    const cur = p.current;
    const strip = cur ? cur.replace(/\/+$/, "") + "/" : "";
    let name = dir;
    if (strip && dir.startsWith(strip)) name = dir.slice(strip.length);
    return name.replace(/\/+$/, "");
  };
  root.innerHTML = `
    <div class="overlay" id="ovl">
      <div class="dialog">
        <div class="dialog-title">选择云端目录</div>
        <div class="picker-head">
          <div class="picker-path">${p.current ? "当前：" + esc(p.current) + "/" : "当前：桶根目录"}</div>
          <div style="display:flex;gap:8px">
            <button class="btn btn-sm" id="pk-refresh">刷新</button>
            <button class="btn btn-sm" id="pk-up" ${p.current ? "" : "disabled"}>⬆ 上级</button>
          </div>
        </div>
        <div class="picker-list">
          ${p.busy
            ? `<div class="empty-state"><span class="spinner"></span>正在列举子目录…</div>`
            : p.dirs.length === 0
              ? `<div class="empty-state">该目录下没有子目录，可直接使用当前目录</div>`
              : p.dirs.map((d) => `
                  <div class="dir-row" data-dir="${esc(d)}">
                    <span class="name">📁 ${esc(relName(d))}</span>
                    <button class="btn btn-sm pk-enter">进入</button>
                    <button class="btn btn-primary btn-sm pk-choose">选择</button>
                  </div>`).join("")}
        </div>
        <div class="picker-foot">
          <div class="picker-status">${esc(p.status)}</div>
          <div style="display:flex;gap:8px">
            <button class="btn btn-sm" id="pk-cancel">取消</button>
            <button class="btn btn-primary btn-sm" id="pk-use">使用当前目录</button>
          </div>
        </div>
      </div>
    </div>`;
  $("#ovl").addEventListener("mousedown", (e) => { if (e.target.id === "ovl") closePicker(); });
  $("#pk-refresh").addEventListener("click", pickerRefresh);
  $("#pk-up").addEventListener("click", pickerUp);
  $("#pk-cancel").addEventListener("click", closePicker);
  $("#pk-use").addEventListener("click", () => pickerChoose(p.current));
  root.querySelectorAll(".pk-enter").forEach((b) =>
    b.addEventListener("click", (e) => pickerEnter(e.target.closest(".dir-row").dataset.dir)));
  root.querySelectorAll(".pk-choose").forEach((b) =>
    b.addEventListener("click", (e) => pickerChoose(e.target.closest(".dir-row").dataset.dir)));
}

/* ---------- 侧边栏 / 状态栏 ---------- */

function renderNav() {
  $("#nav").innerHTML = NAV_ITEMS.map((n) => `
    <button class="nav-item ${S.tab === n.key ? "active" : ""}" data-tab="${n.key}">
      <svg viewBox="0 0 24 24" class="ic">${n.icon}</svg>${n.label}
    </button>`).join("");
  $("#nav").querySelectorAll(".nav-item").forEach((b) =>
    b.addEventListener("click", () => { S.tab = b.dataset.tab; renderNav(); renderContent(); if (S.tab === "logs") refreshLogs(); }));
}

function onSnapshot() {
  const s = S.snap;
  if (!s) return;
  // 侧边栏
  $("#version").textContent = "v" + s.version;
  $("#brand-sub").textContent = s.provider_label;
  const cs = $("#cfg-state");
  cs.innerHTML = `<span class="dot ${s.is_ready ? "dot-green" : "dot-red"}"></span><span>${s.is_ready ? "已配置" : "待配置"}</span>`;
  // 状态栏
  const chip = $("#chip-run"), dot = $("#chip-run-dot"), txt = $("#chip-run-text");
  chip.className = "chip";
  if (!s.is_ready) { chip.classList.add("chip-red"); dot.className = "dot dot-red"; txt.textContent = "配置待完善"; }
  else if (s.paused) { chip.classList.add("chip-red"); dot.className = "dot dot-red"; txt.textContent = "自动同步已暂停"; }
  else { chip.classList.add("chip-green"); dot.className = "dot dot-green"; txt.textContent = "自动同步运行中"; }
  $("#chip-mode").textContent = "方向：" + (s.sync_mode_label || "—");
  const st = $("#sb-status");
  st.textContent = s.status || "准备就绪";
  st.className = "sb-status" + (/失败|错误/.test(s.status || "") ? " err" : "");
  $("#sb-spinner").hidden = !s.sync_busy;
  $("#sync-dropdown").style.display = s.sync_busy ? "none" : "";
  $("#btn-sync").disabled = s.sync_busy;
  $("#btn-pause").textContent = s.paused ? "恢复自动" : "暂停自动";
  updateNextSync();
}

function updateNextSync() {
  const el = $("#sb-next"), txt = $("#sb-next-text");
  if (!S.snap) return;
  if (S.snap.paused) {
    el.classList.add("paused");
    txt.textContent = "自动同步已暂停";
    return;
  }
  el.classList.remove("paused");
  const at = S.snap.next_sync_at;
  if (!at) { txt.textContent = "下次同步：—"; return; }
  txt.textContent = `下次同步：${fmtClock(at)}（剩 ${fmtLeft(at)}）`;
}

function tick() {
  updateNextSync();
  // 到点后后端会推送新 state；此处仅刷新倒计时显示
}

/* ---------- 页面渲染 ---------- */

function renderContent() {
  const c = $("#content");
  switch (S.tab) {
    case "settings": return renderSettings(c);
    case "browser": return renderBrowser(c);
    case "logs": return renderLogs(c);
    case "about": return renderAbout(c);
  }
}

/* ----- 设置页 ----- */

function formFromCfg(cfg) {
  return {
    provider: cfg.provider || "qiniu",
    ak: cfg.ak || "", sk: cfg.sk || "",
    bucket: cfg.bucket || "", region: cfg.region || "z0", domain: cfg.download_domain || "",
    endpoint: cfg.s3_endpoint || "", s3_region: cfg.s3_region || "us-east-1", path_style: !!cfg.s3_path_style,
    dir: cfg.download_dir || "", prefix: cfg.prefix || "", mode: cfg.sync_mode || "download",
    interval: cfg.interval_minutes || 30, overwrite: !!cfg.upload_overwrite, proxy: cfg.proxy_mode || "auto",
  };
}

function formToCfg() {
  const f = S.form;
  return {
    provider: f.provider === "s3" ? "s3" : "qiniu",
    ak: f.ak.trim(), sk: f.sk.trim(), bucket: f.bucket.trim(), region: f.region.trim(),
    download_domain: f.domain.trim(), s3_endpoint: f.endpoint.trim(), s3_region: f.s3_region.trim(),
    s3_path_style: !!f.path_style, download_dir: f.dir.trim(),
    interval_minutes: Math.max(1, Math.round(+f.interval || 30)),
    prefix: normPrefix(f.prefix), sync_mode: f.mode, upload_overwrite: !!f.overwrite, proxy_mode: f.proxy,
  };
}

function bindForm() {
  const f = S.form;
  const bind = (id, key, cast) => {
    const el = $("#" + id);
    if (!el) return;
    el.addEventListener("input", () => {
      f[key] = cast ? cast(el.value) : el.value;
      S.dirty = true;
      $("#dirty-badge").style.display = "";
    });
    el.addEventListener("change", () => {
      f[key] = cast ? cast(el.value) : el.value;
      S.dirty = true;
      $("#dirty-badge").style.display = "";
    });
  };
  bind("f-ak", "ak"); bind("f-sk", "sk"); bind("f-bucket", "bucket");
  bind("f-region", "region"); bind("f-domain", "domain");
  bind("f-endpoint", "endpoint"); bind("f-s3-region", "s3_region");
  bind("f-dir", "dir"); bind("f-prefix", "prefix");
  bind("f-interval", "interval", (v) => Math.max(1, Math.round(+v || 30)));
  $("#f-overwrite").addEventListener("change", (e) => { f.overwrite = e.target.checked; S.dirty = true; $("#dirty-badge").style.display = ""; });
  $("#f-sk-eye").addEventListener("click", () => {
    S.showSk = !S.showSk;
    $("#f-sk").type = S.showSk ? "text" : "password";
    $("#f-sk-eye").textContent = S.showSk ? "隐藏" : "显示";
  });
}

function renderSettings(c) {
  if (!S.form) S.form = formFromCfg(S.snap.cfg);
  const f = S.form;
  const isS3 = f.provider === "s3";
  const regionOptions = QINIU_REGIONS.map(([code, name]) =>
    `<option value="${code}" ${f.region === code ? "selected" : ""}>${code}（${name}）</option>`).join("");
  c.innerHTML = `
    <div class="page-title">设置</div>

    <div class="card">
      <div class="card-title">存储服务商</div>
      <div class="seg" id="seg-provider">
        <button data-v="qiniu" class="${!isS3 ? "active" : ""}">七牛云 Kodo</button>
        <button data-v="s3" class="${isS3 ? "active" : ""}">S3 兼容存储</button>
      </div>
      <div class="card-hint">${isS3
        ? "S3 协议兼容存储：AWS S3、MinIO、阿里云 OSS（兼容模式）等。需填写服务端点与区域。"
        : "七牛云对象存储 Kodo。密钥在七牛控制台「密钥管理」获取。"}</div>
    </div>

    <div class="card">
      <div class="card-title">${isS3 ? "S3 存储账号" : "七牛云账号（Kodo）"}</div>
      <div class="form-grid">
        <label>${isS3 ? "Access Key ID" : "Access Key (AK)"}</label>
        <input class="input" id="f-ak" value="${esc(f.ak)}" placeholder="${isS3 ? "AKIA…" : "七牛控制台「密钥管理」"}" />

        <label>${isS3 ? "Secret Access Key" : "Secret Key (SK)"}</label>
        <div class="field-inline">
          <input class="input" id="f-sk" type="${S.showSk ? "text" : "password"}" value="${esc(f.sk)}" placeholder="仅保存在本机" />
          <button class="btn btn-sm" id="f-sk-eye">${S.showSk ? "隐藏" : "显示"}</button>
        </div>

        <label>Bucket 桶名</label>
        <input class="input" id="f-bucket" value="${esc(f.bucket)}" />

        ${isS3 ? `
        <label>服务端点</label>
        <input class="input" id="f-endpoint" value="${esc(f.endpoint)}" placeholder="https://s3.us-east-1.amazonaws.com 或 http://127.0.0.1:9000" />

        <label>区域 (Region)</label>
        <input class="input" id="f-s3-region" value="${esc(f.s3_region)}" placeholder="如 us-east-1 / cn-north-1 / minio" />

        <label>寻址方式</label>
        <div class="field-inline">
          <select class="input" id="f-path-style">
            <option value="auto" ${!f.path_style ? "selected" : ""}>自动（AWS 虚拟主机式）</option>
            <option value="path" ${f.path_style ? "selected" : ""}>路径式（/bucket/key）</option>
          </select>
          <span class="muted">MinIO 等自定义端点自动用路径式</span>
        </div>` : `
        <label>存储区域</label>
        <div class="field-inline">
          <select class="input" id="f-region-sel">${regionOptions}</select>
          <input class="input" id="f-region" style="width:110px" value="${esc(f.region)}" placeholder="可手动输入" />
        </div>

        <label>下载域名 (可选)</label>
        <input class="input" id="f-domain" value="${esc(f.domain)}" placeholder="留空自动获取" />`}
      </div>
    </div>

    <div class="card">
      <div class="card-title">同步设置</div>
      <div class="form-grid">
        <label>同步方向</label>
        <div class="seg" id="seg-mode">
          ${SYNC_MODES.map(([code, label]) =>
            `<button data-v="${code}" class="${f.mode === code ? "active" : ""}">${label}</button>`).join("")}
        </div>

        <label>自动同步间隔</label>
        <div class="field-inline">
          <input class="input" type="number" id="f-interval" min="1" max="1440" value="${esc(f.interval)}" />
          <span class="muted">分钟</span>
        </div>

        <label>本地目录</label>
        <div class="field-inline">
          <input class="input" id="f-dir" value="${esc(f.dir)}" placeholder="D:\\data\\qiniu" />
          <button class="btn btn-sm" id="f-dir-browse">浏览…</button>
          <button class="btn btn-sm" id="f-dir-open">打开目录</button>
        </div>

        <label>云端目录（Key 前缀）</label>
        <div class="field-inline">
          <input class="input" id="f-prefix" value="${esc(f.prefix)}" placeholder="留空 = 整个桶" />
          <button class="btn btn-sm" id="f-prefix-browse">浏览…</button>
          <span class="muted">可直接浏览云端目录选择</span>
        </div>
      </div>
      <div style="margin-top:12px">
        <label class="check-line"><input type="checkbox" id="f-overwrite" ${f.overwrite ? "checked" : ""} />
          上传时覆盖云端同名文件（默认不覆盖，内容不同会跳过）</label>
      </div>
    </div>

    <div class="card">
      <div class="card-title">网络与启动</div>
      <div class="form-grid">
        <label>网络代理</label>
        <select class="input" id="f-proxy">
          <option value="auto" ${f.proxy === "auto" ? "selected" : ""}>自动（推荐）</option>
          <option value="direct" ${f.proxy === "direct" ? "selected" : ""}>直连（忽略系统代理）</option>
          <option value="system" ${f.proxy === "system" ? "selected" : ""}>系统代理</option>
        </select>

        <label>开机自启</label>
        <label class="check-line"><input type="checkbox" id="f-autostart" ${S.snap.autostart ? "checked" : ""} />
          登录 Windows 后自动在托盘运行</label>
      </div>
      <div class="card-hint">代理软件没开时选「直连」；数据目录：见「关于」页</div>
    </div>

    <div class="actions-row">
      <button class="btn btn-primary" id="btn-save">保存设置</button>
      <button class="btn" id="btn-test-conn">测试连接</button>
      <button class="btn" id="btn-test-upload">测试上传</button>
      <span class="dirty-badge" id="dirty-badge" style="display:${S.dirty ? "" : "none"}">有未保存的修改</span>
      <span class="muted">修改后需保存；「测试上传」会创建并自动删除一个临时对象</span>
    </div>`;

  // provider 切换
  $("#seg-provider").querySelectorAll("button").forEach((b) =>
    b.addEventListener("click", () => { S.form.provider = b.dataset.v; S.dirty = true; renderContent(); }));
  // 同步方向
  $("#seg-mode").querySelectorAll("button").forEach((b) =>
    b.addEventListener("click", () => {
      S.form.mode = b.dataset.v; S.dirty = true;
      $("#seg-mode").querySelectorAll("button").forEach((x) => x.classList.toggle("active", x === b));
    }));
  // 七牛区域下拉
  const rs = $("#f-region-sel");
  if (rs) rs.addEventListener("change", (e) => {
    S.form.region = e.target.value; S.dirty = true;
    const ri = $("#f-region"); if (ri) ri.value = e.target.value;
  });
  // S3 寻址方式
  const ps = $("#f-path-style");
  if (ps) ps.addEventListener("change", (e) => { S.form.path_style = e.target.value === "path"; S.dirty = true; });
  // 代理
  $("#f-proxy").addEventListener("change", (e) => { S.form.proxy = e.target.value; S.dirty = true; });
  // 自启
  $("#f-autostart").addEventListener("change", (e) => {
    invoke("set_autostart", { enabled: e.target.checked })
      .then(() => { S.snap.autostart = e.target.checked; toast(e.target.checked ? "已开启开机自启" : "已关闭开机自启"); })
      .catch((err) => { e.target.checked = !e.target.checked; showModal("开机自启", "设置失败: " + err); });
  });
  // 本地目录
  $("#f-dir-browse").addEventListener("click", () => {
    openDialog({ directory: true, title: "选择本地目录" }).then((p) => {
      if (p) { S.form.dir = p; S.dirty = true; $("#f-dir").value = p; $("#dirty-badge").style.display = ""; }
    }).catch(() => {});
  });
  $("#f-dir-open").addEventListener("click", () => {
    const d = S.form.dir.trim();
    if (!d) { showModal("本地目录", "请先填写本地目录。"); return; }
    invoke("open_local_dir", { path: d }).catch((e) => showModal("本地目录", String(e)));
  });
  // 云端目录浏览
  $("#f-prefix-browse").addEventListener("click", openPicker);
  // 保存 / 测试
  $("#btn-save").addEventListener("click", saveSettings);
  $("#btn-test-conn").addEventListener("click", () => runTest("test_connection", "正在测试连接…"));
  $("#btn-test-upload").addEventListener("click", () => runTest("test_upload", "正在测试上传…"));

  bindForm();
}

async function saveSettings() {
  try {
    const snap = await invoke("save_config", { cfg: formToCfg() });
    S.snap = snap;
    S.form = formFromCfg(snap.cfg);
    S.dirty = false;
    onSnapshot();
    renderContent();
    toast("设置已保存");
  } catch (e) {
    showModal("保存设置", String(e));
  }
}

async function runTest(cmd, busyMsg) {
  toast(busyMsg);
  const btn = cmd === "test_connection" ? $("#btn-test-conn") : $("#btn-test-upload");
  if (btn) { btn.disabled = true; }
  try {
    const r = await invoke(cmd, { cfg: formToCfg() });
    showModal(r.title, (r.ok ? "✓ 成功\n\n" : "✗ 失败\n\n") + r.msg);
  } catch (e) {
    showModal("测试", String(e));
  } finally {
    if (btn) btn.disabled = false;
  }
}

/* ----- 浏览页 ----- */

function browserCfg() { return S.snap.cfg; }

function renderBrowser(c) {
  const b = S.browser;
  const n = b.selected.size;
  const arrow = (k) => b.sortKey === k ? `<span class="arrow">${b.sortDesc ? "▾" : "▴"}</span>` : "";
  c.innerHTML = `
    <div class="page-title">文件浏览</div>
    <div class="toolbar">
      <input class="input" id="b-prefix" value="${esc(b.prefix)}" placeholder="云端目录（留空 = 整个桶）" />
      <button class="btn btn-primary btn-sm" id="b-refresh">刷新</button>
      <button class="btn btn-sm" id="b-up">⬆ 上级</button>
      <span style="flex:1"></span>
      <button class="btn btn-sm" id="b-upload-files">⬆ 上传文件</button>
      <button class="btn btn-sm" id="b-upload-dir">⬆ 上传文件夹</button>
      <button class="btn btn-sm" id="b-download" ${n ? "" : "disabled"}>⬇ 下载选中${n ? `（${n}）` : ""}</button>
      <button class="btn btn-danger btn-sm" id="b-delete" ${n ? "" : "disabled"}>删除选中${n ? `（${n}）` : ""}</button>
      <button class="btn btn-sm" id="b-open-local">打开本地目录</button>
    </div>
    <div class="table-wrap">
      <table class="tbl">
        <thead><tr>
          <th class="no-sort" style="width:34px"><input type="checkbox" id="b-all" ${b.items.length && b.selected.size === b.items.length ? "checked" : ""} /></th>
          <th class="sortable ${b.sortKey === "name" ? "sorted" : ""}" data-k="name">名称 ${arrow("name")}</th>
          <th class="sortable ${b.sortKey === "size" ? "sorted" : ""}" data-k="size">大小 ${arrow("size")}</th>
          <th class="sortable ${b.sortKey === "time" ? "sorted" : ""}" data-k="time">修改时间 ${arrow("time")}</th>
        </tr></thead>
        <tbody id="b-tbody"></tbody>
      </table>
    </div>
    <div class="browser-status">
      ${b.busy ? '<span class="spinner"></span>' : ""}
      <span id="b-status">${esc(b.status)}</span>
      <div class="pager">
        <button class="btn btn-sm" id="b-prev" ${b.markerStack.length ? "" : "disabled"}>上一页</button>
        <button class="btn btn-sm" id="b-next" ${b.nextMarker ? "" : "disabled"}>下一页</button>
      </div>
    </div>`;

  renderBrowserRows();
  $("#b-prefix").addEventListener("change", (e) => { b.prefix = normPrefix(e.target.value); });
  $("#b-prefix").addEventListener("keydown", (e) => { if (e.key === "Enter") browserRefresh(); });
  $("#b-refresh").addEventListener("click", browserRefresh);
  $("#b-up").addEventListener("click", browserUp);
  $("#b-next").addEventListener("click", browserNextPage);
  $("#b-prev").addEventListener("click", browserPrevPage);
  $("#b-all").addEventListener("change", (e) => {
    if (e.target.checked) b.items.forEach((it) => b.selected.add(it.key));
    else b.selected.clear();
    renderContent();
  });
  $("#b-upload-files").addEventListener("click", uploadFiles);
  $("#b-upload-dir").addEventListener("click", uploadDir);
  $("#b-download").addEventListener("click", downloadSelected);
  $("#b-delete").addEventListener("click", deleteSelected);
  $("#b-open-local").addEventListener("click", () => {
    const d = browserCfg().download_dir;
    if (!d) { showModal("本地目录", "请先在设置页填写本地目录。"); return; }
    invoke("open_local_dir", { path: d }).catch((e) => showModal("本地目录", String(e)));
  });
  c.querySelectorAll("th.sortable").forEach((th) =>
    th.addEventListener("click", () => {
      const k = th.dataset.k;
      if (b.sortKey === k) b.sortDesc = !b.sortDesc;
      else { b.sortKey = k; b.sortDesc = false; }
      renderContent();
    }));
}

function sortedItems() {
  const b = S.browser;
  const items = [...b.items];
  const dir = b.sortDesc ? -1 : 1;
  if (b.sortKey === "size") items.sort((a, x) => dir * (a.fsize - x.fsize));
  else if (b.sortKey === "time") items.sort((a, x) => dir * (a.put_time - x.put_time));
  else items.sort((a, x) => dir * a.key.localeCompare(x.key, "zh-CN"));
  return items;
}

function renderBrowserRows() {
  const b = S.browser;
  const tb = $("#b-tbody");
  if (!tb) return;
  if (b.busy) {
    tb.innerHTML = `<tr><td colspan="4"><div class="empty-state"><span class="spinner"></span>正在列举…</div></td></tr>`;
    return;
  }
  if (!b.items.length) {
    tb.innerHTML = `<tr><td colspan="4"><div class="empty-state">
      <svg viewBox="0 0 24 24" class="ic"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/></svg>
      没有对象，输入前缀后点「刷新」列举</div></td></tr>`;
    return;
  }
  tb.innerHTML = sortedItems().map((it) => `
    <tr data-key="${esc(it.key)}">
      <td><input type="checkbox" class="b-check" ${b.selected.has(it.key) ? "checked" : ""} /></td>
      <td class="cell-name" title="${esc(it.key)}">📄 ${esc(it.key)}</td>
      <td class="cell-num">${humanSize(it.fsize)}</td>
      <td class="cell-time">${fmtPutTime(it.put_time)}</td>
    </tr>`).join("");
  tb.querySelectorAll(".b-check").forEach((cb) =>
    cb.addEventListener("change", (e) => {
      const key = e.target.closest("tr").dataset.key;
      if (e.target.checked) b.selected.add(key); else b.selected.delete(key);
      // 局部更新按钮计数，避免整页重渲染
      const n = b.selected.size;
      const dl = $("#b-download"), del = $("#b-delete");
      if (dl) { dl.disabled = !n; dl.textContent = `⬇ 下载选中${n ? `（${n}）` : ""}`; }
      if (del) { del.disabled = !n; del.textContent = `删除选中${n ? `（${n}）` : ""}`; }
    }));
}

function browserSetBusy(status) {
  const b = S.browser;
  b.busy = true; b.status = status;
  const el = $("#b-status"); if (el) el.textContent = status;
  renderBrowserRows();
}

async function browserList(prefix, marker, pushStack) {
  const b = S.browser;
  if (b.busy) return;
  b.busy = true; b.status = marker ? "正在加载下一页…" : "正在列举…";
  if (pushStack) b.markerStack.push(b.pageStart);
  renderBrowserRows();
  const st = $("#b-status"); if (st) st.textContent = b.status;
  try {
    const r = await invoke("list_page", { cfg: browserCfg(), prefix, marker, limit: 100 });
    b.busy = false;
    b.items = r.items;
    b.nextMarker = r.next_marker;
    b.pageStart = marker ?? null;
    b.loadedPrefix = prefix;
    b.status = r.next_marker ? `${b.items.length} 个对象，还有更多` : `${b.items.length} 个对象，已到末页`;
    renderContent();
  } catch (e) {
    b.busy = false;
    b.status = "列举失败: " + e;
    renderContent();
  }
}

function browserRefresh() {
  const b = S.browser;
  b.prefix = normPrefix(($("#b-prefix") || {}).value ?? b.prefix);
  b.selected.clear();
  b.markerStack = [];
  browserList(b.prefix, null, false);
}

function browserNextPage() {
  if (!S.browser.nextMarker) return;
  browserList(S.browser.loadedPrefix, S.browser.nextMarker, true);
}

function browserPrevPage() {
  const b = S.browser;
  if (!b.markerStack.length) return;
  const marker = b.markerStack.pop() || null;
  browserList(b.loadedPrefix, marker, false);
}

function browserUp() {
  const b = S.browser;
  const parts = normPrefix(b.prefix).split("/").filter(Boolean);
  parts.pop();
  b.prefix = parts.join("/");
  browserRefresh();
}

function uploadFiles() {
  openDialog({ multiple: true, title: "选择要上传的文件" }).then((paths) => {
    if (!paths || !paths.length) return;
    if (!Array.isArray(paths)) paths = [paths];
    browserSetBusy(`正在上传 ${paths.length} 个文件…`);
    invoke("upload_paths", { cfg: browserCfg(), paths, prefix: S.browser.prefix })
      .then((msg) => { showModal("上传", msg); browserRefresh(); })
      .catch((e) => { S.browser.busy = false; showModal("上传", String(e)); renderContent(); });
  }).catch(() => {});
}

function uploadDir() {
  openDialog({ directory: true, title: "选择要上传的文件夹" }).then((dir) => {
    if (!dir) return;
    browserSetBusy("正在上传文件夹…");
    invoke("upload_dir", { cfg: browserCfg(), dir, prefix: S.browser.prefix })
      .then((msg) => { showModal("上传", msg); browserRefresh(); })
      .catch((e) => { S.browser.busy = false; showModal("上传", String(e)); renderContent(); });
  }).catch(() => {});
}

function downloadSelected() {
  const b = S.browser;
  const entries = b.items.filter((it) => b.selected.has(it.key))
    .map((it) => ({ key: it.key, hash: it.hash, fsize: it.fsize }));
  if (!entries.length) return;
  browserSetBusy(`正在下载 ${entries.length} 个对象…`);
  invoke("download_keys", { cfg: browserCfg(), entries })
    .then((msg) => { showModal("下载", msg); })
    .catch((e) => showModal("下载", String(e)))
    .finally(() => { b.busy = false; renderContent(); });
}

function deleteSelected() {
  const b = S.browser;
  const keys = [...b.selected];
  if (!keys.length) return;
  showConfirm("删除云端对象", `确定删除选中的 ${keys.length} 个对象？删除后无法恢复。`, async () => {
    browserSetBusy(`正在删除 ${keys.length} 个对象…`);
    try {
      const msg = await invoke("delete_keys", { cfg: browserCfg(), keys });
      showModal("删除", msg);
      b.selected.clear();
      browserRefresh();
    } catch (e) {
      b.busy = false;
      showModal("删除", String(e));
      renderContent();
    }
  });
}

/* ----- 日志页 ----- */

function renderLogs(c) {
  c.innerHTML = `
    <div class="page-title">运行日志</div>
    <div class="card" style="padding:12px 16px">
      <div class="toolbar" style="margin:0">
        <label class="check-line"><input type="checkbox" id="l-follow" ${S.logsFollow ? "checked" : ""} /> 自动跟随（最新在最下）</label>
        <button class="btn btn-sm" id="l-refresh">刷新</button>
        <button class="btn btn-sm" id="l-export">导出…</button>
        <button class="btn btn-sm" id="l-reveal">打开日志文件</button>
        <span style="flex:1"></span>
        <button class="btn btn-sm" id="l-clear">清空显示</button>
      </div>
    </div>
    <div class="log-view" id="log-view"></div>`;
  $("#l-follow").addEventListener("change", (e) => { S.logsFollow = e.target.checked; renderLogLines(true); });
  $("#l-refresh").addEventListener("click", () => refreshLogs());
  $("#l-export").addEventListener("click", () => {
    saveDialog({ defaultPath: "S3FileSync-log.txt" }).then((p) => {
      if (!p) return;
      invoke("export_logs", { path: p })
        .then((n) => toast(`已导出 ${n} 行到 ${p}`))
        .catch((e) => showModal("导出日志", String(e)));
    }).catch(() => {});
  });
  $("#l-reveal").addEventListener("click", () => invoke("reveal_path", { path: S.snap.log_path }));
  $("#l-clear").addEventListener("click", () => { invoke("clear_logs"); refreshLogs(); });
  renderLogLines(true);
}

async function refreshLogs() {
  try {
    S.logs = await invoke("get_logs");
    if (S.tab === "logs") renderLogLines(true);
  } catch (e) { /* ignore */ }
}

function renderLogLines(scroll) {
  const v = $("#log-view");
  if (!v) return;
  const lines = S.logs;
  v.innerHTML = lines.length
    ? lines.map((l) => {
        let cls = "";
        if (l.includes("ERROR")) cls = "err";
        else if (l.includes("WARN")) cls = "warn";
        return `<div class="log-line ${cls}">${esc(l)}</div>`;
      }).join("")
    : `<div class="empty-state">暂无日志</div>`;
  if (scroll || S.logsFollow) v.scrollTop = v.scrollHeight;
}

/* ----- 关于页 ----- */

function renderAbout(c) {
  const s = S.snap;
  c.innerHTML = `
    <div class="page-title">关于</div>
    <div class="card">
      <div class="card-title">软件信息</div>
      <dl class="kv-grid">
        <dt>软件名称</dt><dd>S3FileSync（Tauri 桌面端）</dd>
        <dt>版本</dt><dd>v${esc(s.version)}</dd>
        <dt>实现</dt><dd>Rust + Tauri 2（WebView2），单文件 exe</dd>
        <dt>后端</dt><dd>七牛云 Kodo 与 S3 协议兼容存储（AWS S3 / MinIO 等）</dd>
        <dt>对应 Python 端</dt><dd>功能对等（同步引擎 / 浏览 / 托盘 / 自动同步），共用同一份配置与状态</dd>
      </dl>
    </div>
    <div class="card">
      <div class="card-title">数据与配置</div>
      <dl class="kv-grid">
        <dt>配置目录</dt><dd>${esc(s.app_dir)}</dd>
        <dt>同步状态</dt><dd>${esc(s.state_path)}</dd>
        <dt>日志文件</dt><dd>${esc(s.log_path)}</dd>
      </dl>
      <div class="actions-row" style="margin-top:12px">
        <button class="btn btn-sm" id="a-open-dir">打开配置目录</button>
      </div>
      <div class="card-hint">SK 明文保存在本机 config.json，勿用于公用电脑；与 Python 端 / 安卓端共用此目录。</div>
    </div>
    <div class="card">
      <div class="card-title">帮助</div>
      <div class="actions-row" style="flex-direction:column;align-items:flex-start;gap:6px">
        <a class="link" id="a-repo">Gitee 仓库（S3FileSync）</a>
        <a class="link" id="a-doc">使用说明（Windows）</a>
      </div>
      <div class="card-hint" style="margin-top:10px">
        CLI 调试：S3FileSync.exe --once [--mode upload|download|both]<br/>
        关闭窗口 = 隐藏到托盘；退出请用托盘菜单或 --silent 参数
      </div>
    </div>`;
  $("#a-open-dir").addEventListener("click", () => invoke("open_local_dir", { path: s.app_dir }));
  $("#a-repo").addEventListener("click", () => invoke("open_url", { url: "https://gitee.com/frwuc/S3FileSync" }));
  $("#a-doc").addEventListener("click", () => invoke("open_url", { url: "https://gitee.com/frwuc/S3FileSync/blob/main/windows/README.md" }));
}

/* ---------- 状态栏交互 ---------- */

function bindStatusbar() {
  $("#btn-sync").addEventListener("click", () => {
    invoke("start_sync", {}).catch((e) => toast(String(e), "err"));
  });
  $("#btn-pause").addEventListener("click", async () => {
    try { S.snap = await invoke("toggle_pause"); onSnapshot(); }
    catch (e) { toast(String(e), "err"); }
  });
  $("#btn-once").addEventListener("click", (e) => {
    e.stopPropagation();
    const m = $("#once-menu");
    m.hidden = !m.hidden;
  });
  $("#once-menu").querySelectorAll("button").forEach((b) =>
    b.addEventListener("click", () => {
      $("#once-menu").hidden = true;
      invoke("start_sync", { mode: b.dataset.mode || null }).catch((e) => toast(String(e), "err"));
    }));
  document.addEventListener("click", () => { const m = $("#once-menu"); if (m) m.hidden = true; });
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      if (S.picker) closePicker();
      else if (S.modal) closeModal();
    }
  });
}

/* ---------- 启动 ---------- */

async function init() {
  bindStatusbar();
  renderNav();
  await listen("state", (e) => {
    S.snap = e.payload;
    onSnapshot();
    if (S.tab === "logs") refreshLogs();
  });
  try {
    S.snap = await invoke("get_state");
  } catch (e) {
    toast(String(e), "err");
  }
  onSnapshot();
  if (!S.snap.is_ready && !S.snap.cfg.ak) {
    toast("首次使用：请先在「设置」页填写密钥与目录");
  }
  renderContent();
  setInterval(tick, 1000);
  setInterval(() => invoke("get_state").then((s) => { S.snap = s; onSnapshot(); }).catch(() => {}), 2000);
  setInterval(() => { if (S.tab === "logs") refreshLogs(); }, 2000);
}

init();
