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
    toJava("onError", "ブロックへの変換に失敗しました" + (msg.error ? "：" + msg.error : ""));
    return;
  }
  out.innerHTML = msg.svg;
  const svg = out.querySelector("svg");
  if (!svg) { toJava("onError", "SVGが見つかりません"); return; }
  prepareFields(svg);
  status("変換完了：空欄候補 " + fieldStats.all + " 個。プレビューの引数をクリックすると個別に空欄を切り替えられます。");
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

function dumpSvg(){
  const svg = document.querySelector("#out svg");
  if (!svg){
    toJava("onError", "先にブロック変換をしてください");
    return;
  }
  toJava("onSvg", new XMLSerializer().serializeToString(svg));
}

function exportImages(scale) {
  const svg = document.querySelector("#out svg");
  if (!svg) { toJava("onError", "先にブロック変換をしてください"); return; }
  const rect = svg.getBoundingClientRect();
  const w = Math.ceil(rect.width), h = Math.ceil(rect.height);

  const rs = blanks();
  const saved = rs.map(r => r.getAttribute("data-on") === "1");
  const questionXml = serialize(svg, w, h, scale);
  rs.forEach(r => setShown(r, false));
  const answerXml = serialize(svg, w, h, scale);
  rs.forEach((r, i) => setShown(r, saved[i]));

  let q;
  toPng(questionXml, w, h, scale, "問題")
    .then(res => { q = res; return toPng(answerXml, w, h, scale, "解答") })
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

function toPng(xml, w, h, scale, title) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const head = 40 * scale;
      const canvas = document.createElement("canvas");
      canvas.width = w * scale;
      canvas.height = h * scale + head;
      const ctx = canvas.getContext("2d");
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.fillStyle = "#000000";
      ctx.font = "bold " + (20 * scale) + "px sans-serif";
      ctx.fillText(title, 0, 26 * scale);
      ctx.drawImage(img, 0, head, w * scale, h * scale);
      const data = canvas.toDataURL("image/png").split(",")[1];
      canvas.width = 0;
      canvas.height = 0;
      resolve(data);
    };
    img.onerror = () => reject("SVGの画像化に失敗しました");
    img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(xml);
  });
}