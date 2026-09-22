// Minecraft Education 版 MakeCode のレンダラー（日本語ブロック）
const RENDER_URL = "https://minecraft.makecode.com/--docs?render=1&lang=ja";
const NS = "http://www.w3.org/2000/svg";

let ready = false;
let pending = null;
let seq = 0;
const iframe = document.getElementById("render");

function toJava(method, ...args) {
  if (window.bridge) {
    try { window.bridge[method](...args); } catch (e) { /* ignore */ }
  }
}
function status(s) { toJava("onStatus", String(s)); }

// Java側がbridgeを登録した後に呼ぶ
function notifyIfReady() { if (ready) toJava("onReady"); }

window.addEventListener("message", ev => {
  const msg = ev.data;
  if (!msg || msg.source !== "makecode") return;
  if (msg.type === "renderready") {
    ready = true;
    toJava("onReady");
    if (pending) {
       const p = pending;
       pending = null;
       send(p);
     }
  } else if (msg.type === "renderblocks") {
    onRendered(msg);
  }
});

iframe.src = RENDER_URL;
setTimeout(() => {
  if (!ready) status("MakeCodeのレンダラーが応答しません。ネット接続を確認してください。");
}, 30000);

function renderCode(code) {
  const req = { type: "renderblocks", id: "r" + (++seq), code: String(code), options: { snippetMode: false } };
  if (!ready) { pending = req; status("レンダラーの準備待ち…"); return; }
  send(req);
}

function send(req) {
  status("ブロックに変換中…");
  iframe.contentWindow.postMessage(req, "*");
}

function onRendered(msg) {
  const out = document.getElementById("out");
  if (!msg.svg) {
    out.innerHTML = "";
    toJava("onRendered", false);
    toJava("onError", "ブロックへの変換に失敗しました" + (msg.error ? "：" + msg.error : ""));
    toJava("onCodeError", String(msg.error || ""));
    return;
  }
  out.innerHTML = msg.svg;
  const svg = out.querySelector("svg");
  if (!svg) { toJava("onRendered", false); toJava("onError", "SVGが見つかりません"); return; }
  prepareFields(svg);
  prepareLayout(svg);
  toJava("onRendered", true);
  status("変換完了：空欄候補 " + fieldStats.all + " 個。引数のクリックで空欄を切り替え、ブロックの地の部分をドラッグすると位置を動かせます。");
}

const DIRECTION_WORDS = ["前", "後ろ", "左", "右", "上", "下"];
const BLANK_FILL = "#ffffff";
const BLANK_STROKE = "#ffffff";

function hasClass(el, c){
  return el && el.classList && el.classList.contains(c);
}

function parentBlock(el){
  let p = el.parentElement;
  while(p && p.tagName !== "svg"){
    if (hasClass(p, "blocklyBlock")) return p;
    p = p.parentElement;
  }
  return null;
}

function blockPath(block){
  return Array.from(block.children).find(c => c.tagName === "path" && hasClass(c, "blocklyPath"));
}

function safeBBox(el){
  try{
    return el.getBBox();
  }catch (e){
    return null;
  }
}

function fieldText(f){
  return Array.from(f.querySelectorAll("text")).map(t => t.textContent).join("")
    .replace(/[\s"'“”‘’▾▼]/g, "");
}

function classify(f, block){
  if (hasClass(f, "blocklyVariableField")) return "variable";
  if (hasClass(f, "blocklyNumberField")) {
    const owner = block && hasClass(block, "blocklyShadow") ? parentBlock(block) : block;
    return owner && hasClass(owner, "controls_repeat_ext") ? "repeat" : "number";
  }
  if (hasClass(f, "blocklyTextInputField")) return "text";
  if (hasClass(f, "blocklyDropdownField") && DIRECTION_WORDS.includes(fieldText(f))) return "direction";
  if (/^[-+]?\d+(\.\d+)?$/.test(fieldText(f))) return "number";
  return "other"
}

function isPillBlock(block){
  if (!block) return false;
  if (block.querySelector(".blocklyBlock")) return false;
  const fields = block.querySelectorAll(".blocklyField:not(.blocklyLabelField)");
  const labels = block.querySelectorAll(".blocklyLabelField");
  return fields.length === 1 && labels.length === 0;
}

function setShown(r, on){
  r.setAttribute("data-on", on ? "1" : "0");
  r.style.setProperty("display", on ? "inline": "none", "important")
}

let fieldStats = { all: 0, kinds: {} }

// 引数（編集可能フィールド）ごとに、白塗りの四角を重ねておく（初期状態は非表示）
function prepareFields(svg) {
  const kinds = { repeat: 0, number: 0, direction: 0, variable: 0, text: 0, other: 0 };

  let all = 0;
  svg.querySelectorAll("g.blocklyField").forEach(f => {
    if (hasClass(f, "blocklyLabelField")) return;
    if (!f.querySelector("text") && !f.querySelector("image")) return;

    const block = parentBlock(f);
    const kind = classify(f, block);

    let host, bb;
    const path = isPillBlock(block) ? blockPath(block) : null;
    if (path) {
      host = block;
      bb = safeBBox(path);
    }
    else{
      host = f;
      bb = safeBBox(f);
    }

    if (!bb || bb.width === 0 || bb.height === 0) return;

    const pad = 1;
    const r = document.createElementNS(NS, "rect");
    r.setAttribute("x", bb.x - pad);
    r.setAttribute("y", bb.y - pad);
    r.setAttribute("width", bb.width + pad * 2);
    r.setAttribute("height", bb.height + pad * 2);

    const rad = (bb.height + pad * 2) / 2;

    r.setAttribute("rx", rad);
    r.setAttribute("ry", rad);
    r.setAttribute("data-blank", "1");
    r.setAttribute("data-kind", kind);

    r.style.setProperty("fill", BLANK_FILL, "important");
    r.style.setProperty("stroke", BLANK_STROKE, "important");
    r.style.setProperty("stroke-width", "1.5px", "important");
    r.style.setProperty("pointer-events", "all", "important");
    setShown(r, false);
    host.appendChild(r);
    host.setAttribute("data-fieldhost", "1");

    const toggle = e => {
      e.stopPropagation();
      setShown(r, r.getAttribute("data-on") !== "1");
    };
    host.style.cursor = "pointer";
    host.addEventListener("click", toggle)

    all++;
    kinds[kind]++;
  });
  fieldStats = { all, kinds };
  return all;
}

function blanks() { return Array.from(document.querySelectorAll("#out rect[data-blank]")); }
function blankKinds(csv) {
  const set = String(csv).split(",").map(x => x.trim()).filter(x => x);
  blanks().forEach(r => setShown(r, set.includes(r.getAttribute("data-kind"))));
}
function blankAll()    { blanks().forEach(r => setShown(r, true)); }
function clearBlanks() { blanks().forEach(r => setShown(r, false)); }

const LAYOUT_GAP = 24;
const ROW_TOLERANCE = 20;

let canvasEl = null;
let stacks = [];
let home = null;
let drag = null;

function getTranslate(el){
  const m = /translate\(\s*([-\d.]+)[\s,]+([-\d.]+)/.exec(el.getAttribute("transform") || "");
  return m ? { x: parseFloat(m[1]), y: parseFloat(m[2]) } : { x: 0, y: 0 };
}

function setTranslate(el, x, y){
  const rest = (el.getAttribute("transform") || "").replace(/translate\([^)]*\)/, "").trim();
  el.setAttribute("transform", "translate(" + x + ", " + y + ")" + (rest ? " " + rest : ""));
}

function prepareLayout(svg){
  canvasEl = svg.querySelector("g.blocklyBlockCanvas");
  stacks = canvasEl ? Array.from(canvasEl.children).filter(el => hasClass(el, "blocklyBlock")) : [];
  home = {
    width: svg.getAttribute("width"),
    height: svg.getAttribute("height"),
    viewBox: svg.getAttribute("viewBox"),
    canvas: canvasEl ? canvasEl.getAttribute("transform") : null,
    items: stacks.map(el => ({ el: el, transform: el.getAttribute("transform") }))
  };
  stacks.forEach(el => el.style.cursor = "grab");
  svg.addEventListener("mousedown", onDragStart);
}

function topStack(el){
  let p = el;
  while (p && p !== canvasEl){
    if (p.parentElement === canvasEl && hasClass(p, "blocklyBlock")) return p;
    p = p.parentElement;
  }
  return null;
}

function inFieldHost(el){
  let p = el;
  while (p && p !== canvasEl){
    if (p.getAttribute && p.getAttribute("data-fieldhost") === "1") return true;
    p = p.parentElement;
  }
  return false;
}

function toCanvasPoint(e){
  const svg = document.querySelector("#out svg");
  if (!svg || !canvasEl || !canvasEl.getScreenCTM) return null;
  const m = canvasEl.getScreenCTM();
  if (!m) return null;
  const pt = svg.createSVGPoint();
  pt.x = e.clientX;
  pt.y = e.clientY;
  return pt.matrixTransform(m.inverse());
}

function onDragStart(e){
  if (e.button !== 0 || !canvasEl) return;
  const stack = topStack(e.target);
  if (!stack || inFieldHost(e.target)) return;
  const p = toCanvasPoint(e);
  if (!p) return;
  const t = getTranslate(stack);
  drag = { el: stack, dx: t.x - p.x, dy: t.y - p.y };
  canvasEl.appendChild(stack);
  stack.style.cursor = "grabbing";
  e.preventDefault();
  document.addEventListener("mousemove", onDragMove);
  document.addEventListener("mouseup", onDragEnd);
}

function onDragMove(e){
  if (!drag) return;
  const p = toCanvasPoint(e);
  if (!p) return;
  const c = getTranslate(canvasEl);
  const bb = safeBBox(drag.el);
  let x = p.x + drag.dx;
  let y = p.y + drag.dy;
  if (bb){
    x = Math.max(x, -c.x - bb.x);
    y = Math.max(y, -c.y - bb.y);
  }
  setTranslate(drag.el, x, y);
  growToFit();
}

function onDragEnd(){
  document.removeEventListener("mousemove", onDragMove);
  document.removeEventListener("mouseup", onDragEnd);
  if (!drag) return;
  drag.el.style.cursor = "grab";
  drag = null;
  normalizeLayout();
}

function contentBounds(){
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  stacks.forEach(el => {
    const bb = safeBBox(el);
    if (!bb) return;
    const t = getTranslate(el);
    minX = Math.min(minX, t.x + bb.x);
    minY = Math.min(minY, t.y + bb.y);
    maxX = Math.max(maxX, t.x + bb.x + bb.width);
    maxY = Math.max(maxY, t.y + bb.y + bb.height);
  });
  return minX === Infinity ? null : { minX, minY, maxX, maxY };
}

function applySize(svg, w, h){
  svg.setAttribute("width", w);
  svg.setAttribute("height", h);
  svg.setAttribute("viewBox", "0 0 " + w + " " + h);
}

function growToFit(){
  const svg = document.querySelector("#out svg");
  const b = contentBounds();
  if (!svg || !b) return;
  const c = getTranslate(canvasEl);
  const w = Math.max(parseFloat(svg.getAttribute("width")) || 0, Math.ceil(b.maxX + c.x));
  const h = Math.max(parseFloat(svg.getAttribute("height")) || 0, Math.ceil(b.maxY + c.y));
  applySize(svg, w, h);
}

function normalizeLayout(){
  const svg = document.querySelector("#out svg");
  const b = contentBounds();
  if (!svg || !b) return;
  setTranslate(canvasEl, -b.minX, -b.minY);
  applySize(svg, Math.ceil(b.maxX - b.minX), Math.ceil(b.maxY - b.minY));
}

function arrangeStacks(vertical, quiet){
  if (!canvasEl || !stacks.length){
    toJava("onError", "先にブロック変換をしてください");
    return;
  }
  const items = stacks.map(el => ({ el: el, t: getTranslate(el), bb: safeBBox(el) })).filter(o => o.bb);
  items.sort((a, b) => {
    const ay = a.t.y + a.bb.y, by = b.t.y + b.bb.y;
    if (Math.abs(ay - by) > ROW_TOLERANCE) return ay - by;
    return (a.t.x + a.bb.x) - (b.t.x + b.bb.x);
  });
  let cur = 0;
  items.forEach(o => {
    if (vertical){
      setTranslate(o.el, -o.bb.x, cur - o.bb.y);
      cur += o.bb.height + LAYOUT_GAP;
    } else {
      setTranslate(o.el, cur - o.bb.x, -o.bb.y);
      cur += o.bb.width + LAYOUT_GAP;
    }
  });
  normalizeLayout();
  if (!quiet) status((vertical ? "縦" : "横") + "に並べました（" + items.length + " 個のブロック列）");
}

function arrangeVertical(quiet)   { arrangeStacks(true, quiet); }
function arrangeHorizontal(quiet) { arrangeStacks(false, quiet); }

function resetLayout(){
  const svg = document.querySelector("#out svg");
  if (!svg || !home){
    toJava("onError", "先にブロック変換をしてください");
    return;
  }
  home.items.forEach(it => it.el.setAttribute("transform", it.transform));
  if (canvasEl && home.canvas) canvasEl.setAttribute("transform", home.canvas);
  svg.setAttribute("width", home.width);
  svg.setAttribute("height", home.height);
  if (home.viewBox) svg.setAttribute("viewBox", home.viewBox);
  status("ブロックの位置を元に戻しました");
}

function dumpSvg(){
  const svg = document.querySelector("#out svg");
  if (!svg){
    toJava("onError", "先にブロック変換をしてください");
    return;
  }
  toJava("onSvg", new XMLSerializer().serializeToString(svg));
}

function svgSize(svg){
  const w = parseFloat(svg.getAttribute("width"));
  const h = parseFloat(svg.getAttribute("height"));
  if (w > 0 && h > 0) return { w: Math.ceil(w), h: Math.ceil(h) };
  const r = svg.getBoundingClientRect();
  return { w: Math.ceil(r.width), h: Math.ceil(r.height) };
}

function exportImages(scale) {
  const svg = document.querySelector("#out svg");
  if (!svg) { toJava("onError", "先にブロック変換をしてください"); return; }
  const size = svgSize(svg);
  const w = size.w, h = size.h;

  const rs = blanks();
  const saved = rs.map(r => r.getAttribute("data-on") === "1");
  const questionXml = serialize(svg, w, h, scale);
  rs.forEach(r => setShown(r, false));
  const answerXml = serialize(svg, w, h, scale);
  rs.forEach((r, i) => setShown(r, saved[i]));

  let q;
  toPng(questionXml, w, h, scale)
    .then(res => { q = res; return toPng(answerXml, w, h, scale) })
    .then(a => toJava("onExport", q, a))
    .catch(e => toJava("onError", String(e)));
}

function serialize(svg, w, h, scale) {
  const c = svg.cloneNode(true);
  if (!c.getAttribute("viewBox")) c.setAttribute("viewBox", "0 0 " + w + " " + h);
  c.setAttribute("width", w * scale);
  c.setAttribute("height", h * scale);
  c.setAttribute("xmlns", NS);
  return new XMLSerializer().serializeToString(c);
}

function toPng(xml, w, h, scale) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement("canvas");
      canvas.width = w * scale;
      canvas.height = h * scale;
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, w * scale, h * scale);
      const data = canvas.toDataURL("image/png").split(",")[1];
      canvas.width = 0;
      canvas.height = 0;
      resolve(data);
    };
    img.onerror = () => reject("SVGの画像化に失敗しました");
    img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
  });
}