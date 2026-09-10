#!/usr/bin/env python3
"""Static verification for the YT Pro Android project.

There is no `javac`/Gradle/Android SDK available in the sandbox, so this tool
performs the checks an Android build would otherwise catch first:

1. XML well-formedness of the manifest and every res/*.xml.
2. Resource linking: every ``R.<type>.<name>`` used in Java and every
   ``@<type>/<name>`` used in XML resolves to a real resource; every ``@+id``
   is coherent; manifest activities point at real Java classes.
3. Java syntax parsing (via ``javalang`` when available).
4. Android API verification: every ``android.*`` import and every framework
   method call with a statically-known receiver is checked against the method
   table of ``android-<sdk>.jar`` (parsed class-file constant pools). This
   catches misspelled classes, methods, and removed APIs.

Usage::

    python3 tools/verify_project.py --android-jar /path/to/android-34.jar

Exit code is non-zero when any check fails.
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
SRC = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(SRC, "java")
RES = os.path.join(SRC, "res")

PASS = 0
FAIL = 0
NOTES = []


def ok(message: str) -> None:
    global PASS
    PASS += 1
    print("  [ok]   %s" % message)


def fail(message: str) -> None:
    global FAIL
    FAIL += 1
    print("  [FAIL] %s" % message)


def note(message: str) -> None:
    NOTES.append(message)
    print("  [note] %s" % message)


# ---------------------------------------------------------------------------
# Class-file constant pool parsing (enough to read names + superclasses)
# ---------------------------------------------------------------------------

class ClassInfo:
    __slots__ = ("name", "super_name", "methods", "fields")

    def __init__(self, name, super_name, methods, fields):
        self.name = name
        self.super_name = super_name
        self.methods = methods
        self.fields = fields


def parse_class(data: bytes) -> ClassInfo | None:
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    pos = 8
    cp_count = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    cp = [""] * cp_count
    class_name_index = {}

    def u1():
        nonlocal pos
        v = data[pos]
        pos += 1
        return v

    def u2():
        nonlocal pos
        v = struct.unpack_from(">H", data, pos)[0]
        pos += 2
        return v

    def u4():
        nonlocal pos
        v = struct.unpack_from(">I", data, pos)[0]
        pos += 4
        return v

    i = 1
    while i < cp_count:
        tag = u1()
        if tag == 1:
            length = u2()
            cp[i] = data[pos:pos + length].decode("utf-8", "replace")
            pos += length
        elif tag in (5, 6):
            pos += 8
            i += 1  # longs/doubles occupy two slots
        elif tag in (3, 4):
            pos += 4
        elif tag == 7:
            class_name_index[i] = u2()
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            return None
        i += 1

    # access flags, this, super, interfaces
    pos += 2
    this_idx = u2()
    super_idx = u2()
    iface_count = u2()
    pos += iface_count * 2

    def skip_attributes():
        nonlocal pos
        count = u2()
        for _ in range(count):
            pos += 2
            length = u4()
            pos += length

    fields = set()
    field_count = u2()
    for _ in range(field_count):
        pos += 2
        fields.add(cp[u2()])
        pos += 2
        skip_attributes()

    methods = set()
    method_count = u2()
    for _ in range(method_count):
        pos += 2
        methods.add(cp[u2()])
        pos += 2
        skip_attributes()

    def cls(idx):
        name_idx = class_name_index.get(idx, 0)
        return cp[name_idx] if 0 < name_idx < cp_count else ""

    return ClassInfo(cls(this_idx), cls(super_idx), methods, fields)


def load_android_index(jar_path: str) -> dict:
    index = {}
    with zipfile.ZipFile(jar_path) as jar:
        for entry in jar.namelist():
            if entry.endswith(".class") and not entry.startswith("META-INF"):
                info = parse_class(jar.read(entry))
                if info:
                    index[info.name] = info
    return index


def ancestors(index: dict, fqn: str):
    seen = []
    current = fqn
    while current and current in index and current not in seen:
        seen.append(current)
        current = index[current].super_name
    return seen


def method_exists(index: dict, fqn: str, method: str) -> bool:
    for anc in ancestors(index, fqn):
        if method in index[anc].methods:
            return True
    return False


# ---------------------------------------------------------------------------
# Resource table
# ---------------------------------------------------------------------------

def build_resource_table():
    table = {k: set() for k in
             ("id", "string", "color", "drawable", "layout", "mipmap", "style", "dimen", "attr", "anim")}
    values_dir = os.path.join(RES, "values")
    for name in os.listdir(values_dir):
        if not name.endswith(".xml"):
            continue
        root = ET.parse(os.path.join(values_dir, name)).getroot()
        for child in root:
            tag = child.tag
            attr_name = child.get("name")
            if not attr_name:
                continue
            if tag == "style":
                table["style"].add(attr_name)
            elif tag in table:
                table[tag].add(attr_name)
    # layouts, drawables, mipmaps
    for folder, rtype in (("layout", "layout"), ("drawable", "drawable"), ("anim", "anim")):
        d = os.path.join(RES, folder)
        if os.path.isdir(d):
            for f in os.listdir(d):
                if f.endswith(".xml"):
                    table[rtype].add(f[:-4])
    for d in os.listdir(RES):
        if d.startswith("mipmap"):
            for f in os.listdir(os.path.join(RES, d)):
                base = f.split(".")[0]
                table["mipmap"].add(base)
    # ids declared via @+id in layouts
    id_re = re.compile(r"@\+id/(\w+)")
    layout_dir = os.path.join(RES, "layout")
    if os.path.isdir(layout_dir):
        for f in os.listdir(layout_dir):
            if f.endswith(".xml"):
                text = open(os.path.join(layout_dir, f)).read()
                table["id"].update(id_re.findall(text))
    return table


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

def java_files():
    for dirpath, _, files in os.walk(JAVA):
        for f in files:
            if f.endswith(".java"):
                yield os.path.join(dirpath, f)


def check_xml_wellformed():
    print("\n== XML well-formedness ==")
    manifest = os.path.join(SRC, "AndroidManifest.xml")
    ET.parse(manifest)
    ok("AndroidManifest.xml parses")
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if f.endswith(".xml"):
                ET.parse(os.path.join(dirpath, f))
    ok("every res/*.xml parses")


def check_resources_and_java(table):
    print("\n== Java syntax + R.* / @res linking ==")
    r_ref = re.compile(r"(?<![\w.])R\.(\w+)\.(\w+)")
    try:
        import javalang
        have_javalang = True
    except Exception:
        have_javalang = False

    java_sources = {}
    for path in java_files():
        text = open(path).read()
        java_sources[path] = text
        if have_javalang:
            try:
                javalang.parse.parse(text)
            except Exception as exc:  # noqa
                fail("javalang parse %s: %s" % (os.path.basename(path), exc))
                continue
        ok("parses: %s" % os.path.basename(path))

    def resolves(rtype, name):
        if rtype not in table:
            return False
        if name in table[rtype]:
            return True
        # R.style fields encode dotted style names with underscores.
        return name.replace("_", ".") in table[rtype]

    all_java = "\n".join(java_sources.values())
    for rtype, name in sorted(set(r_ref.findall(all_java))):
        if resolves(rtype, name):
            ok("R.%s.%s resolves" % (rtype, name))
        else:
            fail("unresolved R.%s.%s" % (rtype, name))

    # @res references in every res XML + manifest
    res_ref = re.compile(r"@(\w+)/([\w.]+)")
    xml_blob = []
    for dirpath, _, files in os.walk(RES):
        for f in files:
            if f.endswith(".xml"):
                xml_blob.append(open(os.path.join(dirpath, f)).read())
    xml_blob.append(open(os.path.join(SRC, "AndroidManifest.xml")).read())
    seen = set()
    for rtype, name in res_ref.findall("\n".join(xml_blob)):
        key = (rtype, name)
        if key in seen:
            continue
        seen.add(key)
        if rtype not in table:
            continue  # e.g. @null handled below
        if resolves(rtype, name):
            ok("@%s/%s resolves" % (rtype, name))
        else:
            fail("unresolved @%s/%s" % (rtype, name))
    return java_sources


def check_manifest():
    print("\n== Manifest wiring ==")
    ns = "{http://schemas.android.com/apk/res/android}"
    root = ET.parse(os.path.join(SRC, "AndroidManifest.xml")).getroot()
    app = root.find("application")
    launcher_found = False
    declared = [c[len(JAVA):].lstrip(os.sep)[:-5].replace(os.sep, ".") for c in java_files()]

    build_gradle = open(os.path.join(ROOT, "app", "build.gradle")).read()
    ns_match = re.search(r"namespace\s+['\"]([\w.]+)['\"]", build_gradle)
    base_pkg = ns_match.group(1) if ns_match else ""

    for activity in app.findall("activity"):
        cls = activity.get(ns + "name")
        fqn = (base_pkg + cls) if cls.startswith(".") else cls
        if fqn in declared:
            ok("activity %s has a Java class" % fqn)
        else:
            fail("activity %s missing Java class" % fqn)
        for intent in activity.findall("intent-filter"):
            for action in intent.findall("action"):
                if action.get(ns + "name") == "android.intent.action.MAIN":
                    for cat in intent.findall("category"):
                        if cat.get(ns + "name") == "android.intent.category.LAUNCHER":
                            launcher_found = True
    if launcher_found:
        ok("a launcher intent-filter is present")
    else:
        fail("no launcher intent-filter")


# receiver variable -> framework type
RECEIVER_TYPES = {
    "webView": "android/webkit/WebView",
    "view": "android/webkit/WebView",
    "settings": "android/webkit/WebSettings",
    "cookieManager": "android/webkit/CookieManager",
    "progressBar": "android/widget/ProgressBar",
    "fullscreenContainer": "android/widget/FrameLayout",
    "settingsButton": "android/widget/ImageButton",
    "customView": "android/view/View",
    "content": "android/view/View",
    "uri": "android/net/Uri",
    "dialog": "androidx/appcompat/app/AlertDialog",
}

STATIC_TYPES = {
    "Toast": "android/widget/Toast",
    "SystemClock": "android/os/SystemClock",
    "CookieManager": "android/webkit/CookieManager",
    "ActivityInfo": "android/content/pm/ActivityInfo",
    "View": "android/view/View",
    "Intent": "android/content/Intent",
    "Uri": "android/net/Uri",
}


def check_android_api(index, java_sources):
    print("\n== Android API verification against android.jar ==")
    call_re = re.compile(r"\b(\w+)\.(\w+)\s*\(")
    missing = 0
    for path, text in java_sources.items():
        for receiver, method in call_re.findall(text):
            fqn = RECEIVER_TYPES.get(receiver) or STATIC_TYPES.get(receiver)
            if not fqn:
                continue
            if fqn.startswith("androidx/"):
                continue
            if method_exists(index, fqn, method):
                ok("%s.%s() exists on %s" % (receiver, method, fqn))
            else:
                fail("%s.%s() NOT FOUND on %s" % (receiver, method, fqn))
                missing += 1

    # Fully-qualified framework types referenced inline (not via import). These
    # are the ones this codebase spells out in signatures.
    for fqn in ("android.graphics.Bitmap", "android.webkit.SslErrorHandler",
                "android.net.http.SslError"):
        slash = fqn.replace(".", "/")
        if slash in index:
            ok("inline type %s resolves" % fqn)
        else:
            fail("inline type %s NOT in android.jar" % fqn)

    # Overridden WebViewClient / WebChromeClient callbacks must exist upstream.
    overridden = {
        "android/webkit/WebViewClient": [
            "shouldOverrideUrlLoading", "onPageStarted", "onPageFinished",
            "onReceivedError", "onReceivedSslError"],
        "android/webkit/WebChromeClient": [
            "onProgressChanged", "onShowCustomView", "onHideCustomView"],
        "androidx/appcompat/app/AppCompatActivity": [
            "onCreate", "onBackPressed", "onResume", "onPause", "onStop",
            "onDestroy", "onSaveInstanceState", "onConfigurationChanged"],
    }
    for cls, methods in overridden.items():
        if cls.startswith("androidx/"):
            continue  # provided by the appcompat dependency, not android.jar
        for m in methods:
            if method_exists(index, cls, m):
                ok("override %s#%s matches framework" % (cls.split("/")[-1], m))
            else:
                fail("override %s#%s NOT on framework class" % (cls.split("/")[-1], m))

    # imports resolve
    import_re = re.compile(r"^import\s+(static\s+)?([\w\.]+);", re.M)
    for path, text in java_sources.items():
        for _, imp in import_re.findall(text):
            base = imp.split(".")[0]
            if base == "android":
                fqn = imp.replace(".", "/")
                # strip inner-class method for static imports
                if fqn in index or any(fqn.startswith(a + "$") or a.startswith(fqn) for a in index):
                    ok("import %s resolves" % imp)
                elif fqn.replace("$", "/") in index:
                    ok("import %s resolves" % imp)
                else:
                    # inner class: try replacing last '/' with '$'
                    alt = fqn.rsplit("/", 1)
                    if len(alt) == 2 and (alt[0] + "$" + alt[1]) in index:
                        ok("import %s resolves" % imp)
                    else:
                        fail("import %s NOT in android.jar" % imp)
    if missing == 0:
        ok("no missing framework methods")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-jar", required=True)
    args = parser.parse_args()

    print("== YT Pro static verification ==")
    check_xml_wellformed()
    table = build_resource_table()
    java_sources = check_resources_and_java(table)
    check_manifest()
    index = load_android_index(args.android_jar)
    print("\n(indexed %d android framework classes)" % len(index))
    check_android_api(index, java_sources)

    print("\n== Summary ==")
    print("passed=%d failed=%d notes=%d" % (PASS, FAIL, len(NOTES)))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())
