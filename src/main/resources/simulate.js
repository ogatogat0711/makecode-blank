const OP_LIMIT = 20000;
const EVENT_LIMIT = 20000;

const FORWARD = 0, BACK = 1, LEFT = 2, RIGHT = 3, UP = 4, DOWN = 5;
const LEFT_TURN = 0, RIGHT_TURN = 1;

const STOP = { stop: true };

function facingVec(f) {
  switch (((f % 4) + 4) % 4) {
    case 0: return [0, 0, 1];
    case 1: return [1, 0, 0];
    case 2: return [0, 0, -1];
    default: return [-1, 0, 0];
  }
}

function dirVec(dir, f) {
  switch (dir) {
    case UP: return [0, 1, 0];
    case DOWN: return [0, -1, 0];
    case BACK: return facingVec(f + 2);
    case LEFT: return facingVec(f + 3);
    case RIGHT: return facingVec(f + 1);
    default: return facingVec(f);
  }
}

function toInt(v) {
  const n = Math.floor(Number(v));
  return isFinite(n) ? n : 0;
}

function makeState() {
  return {
    x: 0, y: 0, z: 0, f: 0,
    world: Object.create(null),
    events: [],
    placed: 0, destroyed: 0, blocked: 0, ops: 0,
    truncated: false,
  };
}

function key(x, y, z) { return x + "," + y + "," + z; }

function record(st, t, block) {
  if (st.events.length >= EVENT_LIMIT) {
    st.truncated = true;
    throw STOP;
  }
  const e = { t: t, ax: st.x, ay: st.y, az: st.z, f: st.f };
  if (block) {
    e.x = block[0];
    e.y = block[1];
    e.z = block[2];
  }
  st.events.push(e);
}

function tick(st) {
  if (++st.ops > OP_LIMIT) {
    st.truncated = true;
    throw STOP;
  }
}

function buildApi(st) {
  const agent = {
    move: (dir, n) => {
      tick(st);
      const steps = Math.abs(toInt(n));
      const d = dirVec(dir, st.f);
      for (let i = 0; i < steps; i++) {
        const nx = st.x + d[0], ny = st.y + d[1], nz = st.z + d[2];
        if (st.world[key(nx, ny, nz)]) {
          st.blocked++;
          record(st, "blocked");
          return;
        }
        st.x = nx; st.y = ny; st.z = nz;
        record(st, "move");
      }
    },
    turn: t => {
      tick(st);
      st.f = (st.f + (t === LEFT_TURN ? 3 : 1)) % 4;
      record(st, "turn");
    },
    place: dir => {
      tick(st);
      const d = dirVec(dir, st.f);
      const b = [st.x + d[0], st.y + d[1], st.z + d[2]];
      if (st.world[key(b[0], b[1], b[2])]) return;
      st.world[key(b[0], b[1], b[2])] = true;
      st.placed++;
      record(st, "place", b);
    },
    destroy: dir => {
      tick(st);
      const d = dirVec(dir, st.f);
      const b = [st.x + d[0], st.y + d[1], st.z + d[2]];
      if (!st.world[key(b[0], b[1], b[2])]) return;
      delete st.world[key(b[0], b[1], b[2])];
      st.destroyed++;
      record(st, "destroy", b);
    },
    teleportToPlayer: () => {
      tick(st);
      st.x = 0; st.y = 0; st.z = 0; st.f = 0;
      record(st, "teleport");
    },
  };

  const blocks = {
    place: (block, p) => {
      tick(st);
      if (!p || typeof p !== "object") return;
      const b = [toInt(p.x), toInt(p.y), toInt(p.z)];
      if (st.world[key(b[0], b[1], b[2])]) return;
      st.world[key(b[0], b[1], b[2])] = true;
      st.placed++;
      record(st, "world", b);
    },
  };

  const handlers = [];
  const player = {
    onChat: (cmd, fn) => { if (typeof fn === "function") handlers.push(fn); },
  };
  const loops = {
    forever: fn => { if (typeof fn === "function") handlers.push(fn); },
    pause: () => {},
  };

  const point = (x, y, z) => ({ x: toInt(x), y: toInt(y), z: toInt(z) });

  return {
    api: {
      agent: wrap(agent),
      blocks: wrap(blocks),
      player: wrap(player),
      loops: wrap(loops),
      pos: point,
      world: point,
      FORWARD, BACK, LEFT, RIGHT, UP, DOWN, LEFT_TURN, RIGHT_TURN,
    },
    handlers: handlers,
  };
}

function wrap(obj) {
  return new Proxy(obj, {
    get: (t, k) => (k in t ? t[k] : () => 0),
  });
}

function makeScope(api) {
  const unknown = function () { return 0; };
  return new Proxy(api, {
    has: () => true,
    get: (t, k) => {
      if (k === Symbol.unscopables) return undefined;
      if (k in t) return t[k];
      if (k in globalThis) return globalThis[k];
      return unknown;
    },
  });
}

function simulate(code) {
  const st = makeState();
  let ok = true;
  let error = "";
  try {
    const built = buildApi(st);
    const scope = makeScope(built.api);
    const run = new Function("__scope", "with (__scope) {\n" + String(code) + "\n}");
    run(scope);
    for (let i = 0; i < built.handlers.length; i++) built.handlers[i]();
  } catch (e) {
    if (e !== STOP) {
      ok = false;
      error = String(e && e.message ? e.message : e);
    }
  }
  return JSON.stringify({
    ok: ok,
    error: error,
    truncated: st.truncated,
    events: st.events,
    summary: {
      placed: st.placed,
      destroyed: st.destroyed,
      blocked: st.blocked,
      ops: st.ops,
    },
  });
}
