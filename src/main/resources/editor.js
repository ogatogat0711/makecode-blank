const MONACO_VERSION = "0.52.2";
const MONACO_BASE = "https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/" + MONACO_VERSION + "/min/vs";
const EDITOR_THEME = "vs-dark";
const EDITOR_FONT = "Consolas, 'Courier New', monospace";
const LOAD_TIMEOUT = 20000;
const WORKER_TIMEOUT = 8000;
const MARKER_OWNER = "makecode";

// [補完候補, 挿入されるコード, 説明]
const API_ITEMS = [
  ["player.onChat", "player.onChat(\"${1:run}\", function () {\n\t$0\n})", "チャットコマンドで実行する"],
  ["player.say", "player.say(\"${1:メッセージ}\")", "チャットにメッセージを表示する"],
  ["player.execute", "player.execute(\"${1:/say hello}\")", "スラッシュコマンドを実行する"],
  ["player.position", "player.position()", "プレイヤーの位置"],
  ["player.teleport", "player.teleport(${1:pos(0, 0, 0)})", "プレイヤーをテレポートさせる"],
  ["player.onItemInteracted", "player.onItemInteracted(${1:WOODEN_SWORD}, function () {\n\t$0\n})", "アイテムを使ったときに実行する"],

  ["agent.move", "agent.move(${1|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|}, ${2:1})", "エージェントを移動させる"],
  ["agent.turn", "agent.turn(${1|LEFT_TURN,RIGHT_TURN|})", "エージェントの向きを変える"],
  ["agent.place", "agent.place(${1|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|})", "ブロックを置く"],
  ["agent.destroy", "agent.destroy(${1|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|})", "ブロックを壊す"],
  ["agent.till", "agent.till(${1|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|})", "土を耕す"],
  ["agent.collect", "agent.collect(${1:AIR})", "アイテムを拾う"],
  ["agent.collectAll", "agent.collectAll()", "まわりのアイテムをすべて拾う"],
  ["agent.inspect", "agent.inspect(AgentInspection.${1|Block,Redstone|}, ${2|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|})", "となりのブロックを調べる"],
  ["agent.detect", "agent.detect(AgentDetection.${1|Block,Mob|}, ${2|FORWARD,BACK,LEFT,RIGHT,UP,DOWN|})", "ブロックやモブがあるか調べる"],
  ["agent.setItem", "agent.setItem(${1:GOLD_BLOCK}, ${2:64}, ${3:1})", "持ち物スロットにアイテムを入れる"],
  ["agent.getItemCount", "agent.getItemCount(${1:1})", "スロットのアイテム数"],
  ["agent.teleportToPlayer", "agent.teleportToPlayer()", "プレイヤーのところへ呼ぶ"],

  ["blocks.place", "blocks.place(${1:GOLD_BLOCK}, ${2:pos(0, 0, 0)})", "ブロックを置く"],
  ["blocks.fill", "blocks.fill(\n\t${1:GOLD_BLOCK},\n\t${2:pos(0, 0, 0)},\n\t${3:pos(4, 4, 4)},\n\tFillOperation.${4|Replace,Destroy,Hollow,Keep,Outline|}\n)", "範囲をブロックで埋める"],
  ["blocks.testForBlock", "blocks.testForBlock(${1:GOLD_BLOCK}, ${2:pos(0, 0, 0)})", "その位置が指定のブロックか調べる"],
  ["blocks.onBlockBroken", "blocks.onBlockBroken(${1:GOLD_BLOCK}, function () {\n\t$0\n})", "ブロックが壊されたときに実行する"],
  ["blocks.onBlockPlaced", "blocks.onBlockPlaced(${1:GOLD_BLOCK}, function () {\n\t$0\n})", "ブロックが置かれたときに実行する"],

  ["mobs.spawn", "mobs.spawn(${1:CHICKEN}, ${2:pos(0, 0, 0)})", "モブを出す"],
  ["mobs.kill", "mobs.kill(${1:CHICKEN})", "モブを消す"],
  ["mobs.give", "mobs.give(${1:mobs.target(LOCAL_PLAYER)}, ${2:GOLD_BLOCK}, ${3:1})", "アイテムを渡す"],

  ["loops.forever", "loops.forever(function () {\n\t$0\n})", "ずっとくりかえす"],
  ["loops.pause", "loops.pause(${1:1000})", "指定したミリ秒だけ待つ"],

  ["pos", "pos(${1:0}, ${2:0}, ${3:0})", "プレイヤーからの相対位置"],
  ["world", "world(${1:0}, ${2:0}, ${3:0})", "ワールドの絶対位置"],

  ["for", "for (let ${1:index} = 0; ${1:index} < ${2:5}; ${1:index}++) {\n\t$0\n}", "くりかえし"],
  ["if", "if (${1:条件}) {\n\t$0\n}", "もし〜なら"],
  ["function", "function ${1:name}() {\n\t$0\n}", "関数を作る"],
  ["let", "let ${1:name} = ${2:0}", "変数を作る"],
];

const API_WORDS = [
  "FORWARD", "BACK", "LEFT", "RIGHT", "UP", "DOWN", "LEFT_TURN", "RIGHT_TURN",
  "AIR", "STONE", "DIRT", "GRASS", "COBBLESTONE", "SAND", "GLASS", "TNT",
  "GOLD_BLOCK", "IRON_BLOCK", "DIAMOND_BLOCK", "REDSTONE_BLOCK", "WATER", "LAVA",
  "AgentInspection.Block", "AgentDetection.Block", "LOCAL_PLAYER",
];

const PLACE_RE = /(agent|blocks)\s*\.\s*place\s*\(/;

let editor = null;
let shapeTimer = null;
let pendingValue = "";
let ready = false;
let failed = false;
const queued = [];

function toJava(method, ...args) {
  if (!window.bridge) {
    queued.push([method, args]);
    return;
  }
  try { window.bridge[method](...args); } catch (e) { /* ignore */ }
}

function notifyEditorIfReady() {
  if (!window.bridge) return;
  const pending = queued.splice(0, queued.length);
  pending.forEach(c => toJava(c[0], ...c[1]));
}

function fail(msg) {
  if (ready || failed) return;
  failed = true;
  toJava("onEditorFailed", String(msg));
}

function boot() {
  const s = document.createElement("script");
  s.src = MONACO_BASE + "/loader.min.js";
  s.onload = onLoaderReady;
  s.onerror = () => fail("エディタ本体を取得できませんでした（ネット接続を確認してください）");
  document.head.appendChild(s);
  setTimeout(() => fail("エディタの読み込みがタイムアウトしました"), LOAD_TIMEOUT);
}

function onLoaderReady() {
  try {
    require.config({ paths: { vs: MONACO_BASE } });
    require(["vs/editor/editor.main"], createEditor, err => fail(String(err)));
  } catch (e) {
    fail(String(e));
  }
}

function createEditor() {
  try {
    monaco.languages.typescript.javascriptDefaults.setDiagnosticsOptions({
      noSemanticValidation: true,
      noSyntaxValidation: true,
    });
    registerCompletion();
    editor = monaco.editor.create(document.getElementById("editor"), {
      value: pendingValue,
      language: "javascript",
      theme: EDITOR_THEME,
      fontFamily: EDITOR_FONT,
      fontSize: 13,
      tabSize: 4,
      minimap: { enabled: false },
      automaticLayout: true,
      scrollBeyondLastLine: false,
      renderWhitespace: "selection",
      mouseWheelZoom: true,
    });
    editor.onDidChangeModelContent(() => {
      clearErrorMarkers();
      scheduleShapeReport();
    });
    ready = true;
    toJava("onEditorReady", "monaco " + MONACO_VERSION + " / " + navigator.userAgent);
    enableSyntaxCheck();
  } catch (e) {
    fail(String(e));
  }
}

function enableSyntaxCheck() {
  let settled = false;
  const report = (on, detail) => {
    if (settled) return;
    settled = true;
    if (on) {
      monaco.languages.typescript.javascriptDefaults.setDiagnosticsOptions({
        noSemanticValidation: true,
        noSyntaxValidation: false,
      });
    }
    toJava("onEditorInfo", "構文チェック: " + (on ? "有効" : "無効（" + detail + "）")
      + " / 文字数=" + getCode().length);
  };
  setTimeout(() => report(false, "ワーカーが応答しません"), WORKER_TIMEOUT);
  try {
    monaco.languages.typescript.getJavaScriptWorker().then(() => report(true), e => report(false, e));
  } catch (e) {
    report(false, e);
  }
}

function registerCompletion() {
  monaco.languages.registerCompletionItemProvider("javascript", {
    triggerCharacters: ["."],
    provideCompletionItems: (model, position) => {
      const line = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
      const m = /([A-Za-z_$][\w$]*\.)?[\w$]*$/.exec(line);
      const prefix = m ? m[0] : "";
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: position.column - prefix.length,
        endColumn: position.column,
      };
      const snippet = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;
      const suggestions = API_ITEMS.map(it => ({
        label: it[0],
        kind: monaco.languages.CompletionItemKind.Function,
        insertText: it[1],
        insertTextRules: snippet,
        detail: it[2],
        range: range,
      }));
      API_WORDS.forEach(w => suggestions.push({
        label: w,
        kind: monaco.languages.CompletionItemKind.Constant,
        insertText: w,
        range: range,
      }));
      return { suggestions: suggestions };
    },
  });
}

function getCode() {
  return editor ? editor.getValue() : pendingValue;
}

function setCode(code) {
  pendingValue = String(code);
  if (editor) editor.setValue(pendingValue);
  reportShape();
}

function reportShape() {
  toJava("onCodeShape", PLACE_RE.test(getCode()));
}

function scheduleShapeReport() {
  if (shapeTimer) clearTimeout(shapeTimer);
  shapeTimer = setTimeout(reportShape, 300);
}

function setErrorMarker(line, column, message) {
  if (!editor) return;
  const model = editor.getModel();
  const ln = Math.max(1, Math.min(model.getLineCount(), Math.floor(line) || 1));
  const col = Math.max(1, Math.floor(column) || 1);
  monaco.editor.setModelMarkers(model, MARKER_OWNER, [{
    severity: monaco.MarkerSeverity.Error,
    message: String(message),
    startLineNumber: ln,
    endLineNumber: ln,
    startColumn: col,
    endColumn: model.getLineMaxColumn(ln),
  }]);
  editor.revealLineInCenter(ln);
}

function clearErrorMarkers() {
  if (editor) monaco.editor.setModelMarkers(editor.getModel(), MARKER_OWNER, []);
}

boot();
