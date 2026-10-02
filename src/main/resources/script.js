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
const BLANK_STROKE = "#0303f0";

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
  r.style.setProperty("display", on ? "inline": "none", "important");
  (r.hiddenContent || []).forEach(el => {
    el.style.setProperty("visibility", on ? "hidden" : "visible", "important");
  });
}

let fieldStats = { all: 0, kinds: {} }

const BLANK_PAD = 1;

function boxIn(host, el, svg) {
  const bb = safeBBox(el);
  if (!bb) return null;
  let m = null;
  try {
    const hm = host.getCTM(), em = el.getCTM();
    if (hm && em) m = hm.inverse().multiply(em);
  } catch (e) { /* ignore */ }
  if (!m) return { x: bb.x, y: bb.y, w: bb.width, h: bb.height };
  const p = svg.createSVGPoint();
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  [[bb.x, bb.y], [bb.x + bb.width, bb.y], [bb.x, bb.y + bb.height], [bb.x + bb.width, bb.y + bb.height]]
    .forEach(c => {
      p.x = c[0];
      p.y = c[1];
      const q = p.matrixTransform(m);
      minX = Math.min(minX, q.x);
      minY = Math.min(minY, q.y);
      maxX = Math.max(maxX, q.x);
      maxY = Math.max(maxY, q.y);
    });
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
}

function unionBox(a, b) {
  if (!a) return b;
  if (!b) return a;
  const x = Math.min(a.x, b.x), y = Math.min(a.y, b.y);
  return { x: x, y: y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
}

function coverOfShape(shape) {
  const c = shape.cloneNode(false);
  c.removeAttribute("id");
  c.removeAttribute("class");
  c.removeAttribute("filter");
  c.removeAttribute("style");
  return c;
}

function coverOfBox(box) {
  const r = document.createElementNS(NS, "rect");
  r.setAttribute("x", box.x - BLANK_PAD);
  r.setAttribute("y", box.y - BLANK_PAD);
  r.setAttribute("width", box.w + BLANK_PAD * 2);
  r.setAttribute("height", box.h + BLANK_PAD * 2);
  const rad = (box.h + BLANK_PAD * 2) / 2;
  r.setAttribute("rx", rad);
  r.setAttribute("ry", rad);
  return r;
}

function fieldShape(f, pill) {
  if (pill) return pill;
  const rect = f.querySelector("rect.blocklyFieldRect");
  if (rect && rect.parentNode === f) {
    const b = safeBBox(rect);
    if (b && b.width > 0 && b.height > 0) return rect;
  }
  return null;
}

// 引数（編集可能フィールド）ごとに、白塗りの図形を重ねておく（初期状態は非表示）
function prepareFields(svg) {
  const kinds = { repeat: 0, number: 0, direction: 0, variable: 0, text: 0, other: 0 };

  let all = 0;
  svg.querySelectorAll("g.blocklyField").forEach(f => {
    if (hasClass(f, "blocklyLabelField")) return;
    if (!f.querySelector("text") && !f.querySelector("image")) return;

    const block = parentBlock(f);
    const kind = classify(f, block);

    const pill = isPillBlock(block) ? blockPath(block) : null;
    const host = pill ? block : f;
    const shape = fieldShape(f, pill);
    const base = boxIn(host, shape || (pill ? pill : f), svg);
    if (!base || base.w === 0 || base.h === 0) return;

    const content = Array.from(f.querySelectorAll("text, image"));
    let contentBox = null;
    content.forEach(el => {
      contentBox = unionBox(contentBox, boxIn(host, el, svg));
    });

    const r = shape ? coverOfShape(shape) : coverOfBox(unionBox(base, contentBox));
    r.hiddenContent = content;

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

function blanks() { return Array.from(document.querySelectorAll("#out [data-blank]")); }
function blankKinds(csv) {
  const set = String(csv).split(",").map(x => x.trim()).filter(x => x);
  blanks().forEach(r => setShown(r, set.includes(r.getAttribute("data-kind"))));
}
function blankAll()    { blanks().forEach(r => setShown(r, true)); }
function clearBlanks() { blanks().forEach(r => setShown(r, false)); }

const TILE_MAX = 3000;
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
  toTiles(questionXml, w, h, scale)
    .then(res => { q = res; return toTiles(answerXml, w, h, scale) })
    .then(a => toJava("onExport", q, a))
    .catch(e => toJava("onError", String(e)));
}

function stackBounds(scale) {
  if (!canvasEl || !stacks.length) return "";
  const c = getTranslate(canvasEl);
  const out = [];
  stacks.forEach(el => {
    const bb = safeBBox(el);
    if (!bb) return;
    const t = getTranslate(el);
    const top = (t.y + bb.y + c.y) * scale;
    const bottom = (t.y + bb.y + bb.height + c.y) * scale;
    out.push(Math.round(top) + "," + Math.round(bottom));
  });
  return out.join(";");
}

function blockTops(scale) {
  if (!canvasEl) return "";
  const out = [];
  const walk = (el, oy) => {
    for (const child of el.children) {
      if (child.tagName !== "g") continue;
      const t = getTranslate(child);
      const y = oy + t.y;
      const cls = child.getAttribute("class") || "";
      if (cls.split(" ").indexOf("blocklyBlock") >= 0) {
        const bb = safeBBox(child);
        if (bb) out.push(Math.round((y + bb.y) * scale));
      }
      walk(child, y);
    }
  };
  walk(canvasEl, getTranslate(canvasEl).y);
  return Array.from(new Set(out)).sort((a, b) => a - b).join(",");
}

function stackSides(scale) {
  if (!canvasEl || !stacks.length) return "";
  const c = getTranslate(canvasEl);
  const out = [];
  stacks.forEach(el => {
    const bb = safeBBox(el);
    if (!bb) return;
    const t = getTranslate(el);
    out.push(Math.round((t.x + bb.x + c.x) * scale) + ","
      + Math.round((t.x + bb.x + bb.width + c.x) * scale));
  });
  return out.join(";");
}

function textHeight(scale) {
  const svg = document.querySelector("#out svg");
  if (!svg) return 0;
  const hs = [];
  svg.querySelectorAll("text").forEach(el => {
    const b = boxIn(svg, el, svg);
    if (b && b.h > 0) hs.push(b.h * scale);
  });
  if (!hs.length) return 0;
  hs.sort((a, b) => a - b);
  return Math.round(hs[Math.floor(hs.length / 2)]);
}

function guardBoxes(scale) {
  const svg = document.querySelector("#out svg");
  if (!svg) return "";
  const out = [];
  svg.querySelectorAll("text, image, [data-blank]").forEach(el => {
    const b = boxIn(svg, el, svg);
    if (!b || b.w <= 0 || b.h <= 0) return;
    out.push(Math.floor(b.x * scale) + "," + Math.floor(b.y * scale) + ","
      + Math.ceil((b.x + b.w) * scale) + "," + Math.ceil((b.y + b.h) * scale));
  });
  return out.join(";");
}

function serialize(svg, w, h, scale) {
  const c = svg.cloneNode(true);
  if (!c.getAttribute("viewBox")) c.setAttribute("viewBox", "0 0 " + w + " " + h);
  c.setAttribute("width", w * scale);
  c.setAttribute("height", h * scale);
  c.setAttribute("xmlns", NS);
  return new XMLSerializer().serializeToString(c);
}

function toTiles(xml, w, h, scale) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const fw = Math.round(w * scale), fh = Math.round(h * scale);
      const tiles = [];
      try {
        for (let y = 0; y < fh; y += TILE_MAX) {
          for (let x = 0; x < fw; x += TILE_MAX) {
            const tw = Math.min(TILE_MAX, fw - x), th = Math.min(TILE_MAX, fh - y);
            const canvas = document.createElement("canvas");
            canvas.width = tw;
            canvas.height = th;
            const ctx = canvas.getContext("2d");
            ctx.fillStyle = "#ffffff";
            ctx.fillRect(0, 0, tw, th);
            ctx.drawImage(img, -x, -y, fw, fh);
            tiles.push({ x: x, y: y, data: canvas.toDataURL("image/png").split(",")[1] });
            canvas.width = 0;
            canvas.height = 0;
          }
        }
      } catch (e) {
        reject("画像化に失敗しました: " + e);
        return;
      }
      resolve(JSON.stringify({ w: fw, h: fh, tiles: tiles }));
    };
    img.onerror = () => reject("SVGの画像化に失敗しました");
    img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
  });
}