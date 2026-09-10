#!/usr/bin/env python3
"""Extract the injected JS payloads from MainActivity.java and verify them.

1. Rebuild each ``String`` constant (DOUBLE_TAP_LIKE_JS, SHORTS_DOUBLE_TAP_LIKE_JS)
   exactly as the JVM would concatenate it (unescaping \\n and \").
2. ``node --check`` each payload (syntax).
3. Run each payload in node against a minimal DOM stub and assert the gesture
   paths behave: generic handler likes on player double-tap, ignores taps
   inside a Shorts reel; Shorts handler clicks the Shorts like button and pops
   a heart on double-tap.

Usage:  python3 tools/smoke_test_js.py
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
JAVA = os.path.join(ROOT, "app", "src", "main", "java", "com", "au", "ytpro", "MainActivity.java")

CONST_NAMES = ["DOUBLE_TAP_LIKE_JS", "SHORTS_DOUBLE_TAP_LIKE_JS"]


def extract_constant(src: str, name: str) -> str:
    start = src.index("String " + name + " =")
    # statement ends at the first line that terminates the declaration: '";'
    end = src.index('";', start) + 2
    block = src[start:end]
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
    out = []
    for lit in literals:
        unescaped = (
            lit.replace("\\\\", "\x00")
            .replace('\\"', '"')
            .replace("\\n", "\n")
            .replace("\x00", "\\")
        )
        out.append(unescaped)
    return "".join(out)


def main() -> int:
    src = open(JAVA, encoding="utf-8").read()
    payloads = {name: extract_constant(src, name) for name in CONST_NAMES}

    tmp = tempfile.mkdtemp(prefix="ytpro-js-")
    for name, js in payloads.items():
        path = os.path.join(tmp, name + ".js")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(js)
        proc = subprocess.run(["node", "--check", path], capture_output=True, text=True)
        if proc.returncode != 0:
            print("node --check FAILED for %s:\n%s" % (name, proc.stderr))
            return 1
        print("node --check ok: %s (%d chars)" % (name, len(js)))

    smoke = os.path.join(tmp, "smoke.js")
    with open(smoke, "w", encoding="utf-8") as fh:
        fh.write(SMOKE_TEMPLATE.replace('__PAYLOAD_GENERIC__', payloads[CONST_NAMES[0]])
            .replace('__PAYLOAD_SHORTS__', payloads[CONST_NAMES[1]]))
    proc = subprocess.run(["node", smoke], capture_output=True, text=True)
    print(proc.stdout, end="")
    if proc.stderr:
        print(proc.stderr, end="", file=sys.stderr)
    return proc.returncode


SMOKE_TEMPLATE = r"""
'use strict';
let failures = 0;
function check(cond, label) {
  if (cond) { console.log('  [smoke ok] ' + label); }
  else { failures++; console.log('  [smoke FAIL] ' + label); }
}

// ---- minimal DOM stub -------------------------------------------------
function makeEl(tag, cls, id) {
  const el = {
    nodeType: 1, tagName: (tag || 'div').toUpperCase(),
    id: id || '', cls: cls || '', parentNode: null,
    children: [], style: {}, innerHTML: '', textContent: '',
    removed: false, clicked: 0,
    classList: { add() {}, remove() {} },
    getAttribute(n) { return n === 'class' ? this.cls : (n === 'id' ? this.id : null); },
    appendChild(c) { c.parentNode = this; this.children.push(c); return c; },
    removeChild(c) { this.children = this.children.filter(x => x !== c); c.parentNode = null; return c; },
    click() { this.clicked++; },
    remove() { this.removed = true; },
    querySelector(sel) { return routeQuery(sel, this); },
    closest() { return null; },
    get offsetWidth() { return 0; },
  };
  return el;
}

const shortLike = makeEl('button', '', 'short-like');
const watchLike = makeEl('button', '', 'watch-like');
const reel = makeEl('ytm-reel-video-renderer', 'reel-container');
reel.querySelector = (sel) => (sel.indexOf('ytm-like-button-renderer') !== -1 ? shortLike : null);

function routeQuery(sel, scope) {
  if (scope === reel) return scope.querySelector(sel);
  if (sel.indexOf('ytm-like-button-renderer') !== -1 || sel.indexOf('Like this short') !== -1) return shortLike;
  if (sel.indexOf('ytm-reel-video-renderer') !== -1 || sel.indexOf('reel-player') !== -1 || sel.indexOf('.shortsContainer') !== -1) return reel;
  if (sel.indexOf('like') !== -1 || sel.indexOf('Like') !== -1) return watchLike;
  return null;
}

const listeners = { touchend: [], click: [] };
const body = makeEl('body');
const head = makeEl('head');
const documentElement = makeEl('html');
global.document = {
  nodeType: 9,
  head, body, documentElement,
  addEventListener(type, fn) { if (listeners[type]) listeners[type].push(fn); },
  createElement(tag) { return makeEl(tag); },
  getElementById() { return null; },
  querySelector(sel) { return routeQuery(sel, null); },
};
global.window = global;
global.MutationObserver = class { constructor(cb) { this.cb = cb; } observe() { this.observing = true; } };

function fireTouchend(target) {
  const e = { target, changedTouches: [{ clientX: 120, clientY: 340 }] };
  listeners.touchend.forEach(fn => fn(e));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  // ---- generic player script ----
  eval(`__PAYLOAD_GENERIC__`);
  const videoInPlayer = makeEl('video');
  const player = makeEl('div', 'html5-video-player');
  player.appendChild(videoInPlayer);
  const videoInReel = makeEl('video');
  const reelWrap = makeEl('div', 'shortsContainer');
  reelWrap.appendChild(videoInReel);

  fireTouchend(videoInPlayer); await sleep(50); fireTouchend(videoInPlayer);
  check(watchLike.clicked === 1, 'generic: double tap on player clicks the watch Like button once');

  const heartsBefore = body.children.length;
  fireTouchend(videoInReel); await sleep(50); fireTouchend(videoInReel);
  check(watchLike.clicked === 1, 'generic: double tap inside a Shorts reel is ignored');
  check(body.children.length === heartsBefore, 'generic: no heart popped for reel taps');

  // ---- dedicated Shorts script ----
  const likeClicksBefore = shortLike.clicked;
  eval(`__PAYLOAD_SHORTS__`);
  fireTouchend(reel); await sleep(50); fireTouchend(reel);
  check(shortLike.clicked === likeClicksBefore + 1, 'shorts: double tap clicks the Shorts Like button once');
  check(body.children.length === heartsBefore + 1, 'shorts: heart overlay appended at tap position');

  await sleep(800);
  const heart = body.children[body.children.length - 1];
  check(heart.removed === true, 'shorts: heart removes itself after the animation');

  // like-control taps must not double-toggle
  const likeBtnTarget = makeEl('button', 'like-button');
  likeBtnTarget.closest = sel => (sel.indexOf('like') !== -1 ? likeBtnTarget : null);
  const before = shortLike.clicked;
  fireTouchend(likeBtnTarget); await sleep(50); fireTouchend(likeBtnTarget);
  check(shortLike.clicked === before, 'shorts: double tap on the Like control itself is ignored');

  console.log(failures === 0 ? 'SMOKE PASS' : 'SMOKE FAIL(' + failures + ')');
  process.exit(failures === 0 ? 0 : 1);
})();
"""


if __name__ == "__main__":
    sys.exit(main())
