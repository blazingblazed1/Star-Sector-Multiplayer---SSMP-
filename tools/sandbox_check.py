#!/usr/bin/env python3
"""Replicates Starsector 0.98a's mod class-loader filter (com.fs.starfarer.loading.scripts.new) and checks
every class referenced by the compiled mod. Usage: sandbox_check.py <classes dir>"""
import subprocess, sys, re, pathlib
IO_OK = {"java.io.BufferedInputStream","java.io.BufferedReader","java.io.FilterInputStream","java.io.InputStreamReader",
 "java.io.Reader","java.io.Serializable","java.io.InvalidClassException","java.io.ObjectStreamException","java.io.InputStream",
 "java.io.IOException","java.io.PrintStream","java.io.PrintWriter","java.io.ByteArrayInputStream","java.io.FilterOutputStream",
 "java.io.OutputStream","java.io.Closeable","java.io.Flushable","java.nio.ByteBuffer","java.nio.CharBuffer","java.nio.IntBuffer",
 "java.io.StringReader","java.io.FileReader","java.lang.Class"}
REFL_OK = {"java.lang.reflect.AnnotatedElement","java.lang.reflect.InvocationTargetException","java.lang.reflect.Type","java.lang.reflect.GenericDeclaration"}
def blocked(n):
    if (n.startswith("java.io") or n.startswith("java.nio.file.File")) and n not in IO_OK: return "file access"
    if n.startswith("javax.script"): return "javax.script"
    if n.startswith("java.util.prefs"): return "prefs"
    if n.startswith("java.lang.reflect") and n not in REFL_OK: return "reflection"
    if n == "sun.reflect.misc.MethodUtil": return "reflection"
    return None
bad = 0
for f in sorted(pathlib.Path(sys.argv[1]).rglob("*.class")):
    out = subprocess.run(["javap","-v",str(f)],capture_output=True,text=True).stdout
    names = set(re.findall(r"= Class\s+#\d+\s+// \"?\[*L?([\w/$]+)", out))
    names |= set(m.replace("/",".") for m in re.findall(r"L(java/[\w/$]+);", out))
    for n in sorted(x.replace("/",".") for x in names):
        why = blocked(n)
        if why: print(f"BLOCKED {f.name}: {n} ({why})"); bad += 1
print("sandbox check:", "PASS" if bad == 0 else f"FAIL ({bad})")
sys.exit(1 if bad else 0)
