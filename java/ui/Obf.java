package starmp.ui;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds Starsector's obfuscated internal names at runtime by reading the game's own class files (the obfuscated
 * names differ between the Windows and Linux builds of the same version). Anchors are the few names the game leaves
 * readable (TitleScreenState, its fields titleWidget / missionWidget, method getMainMenu, CombatEngine.setDoNotEndCombat...).
 * Uses only a class-file parser over ByteBuffer: the mod sandbox blocks java.lang.reflect.
 */
public final class Obf {
    private Obf() {}

    public static final class Ref { public final int op; public final String owner, name, desc; Ref(int op, String o, String n, String d) { this.op = op; owner = o; name = n; desc = d; } }

    static final class Info {
        String self, sup;
        final List<String[]> fields = new ArrayList<>();   // {name, desc}
        final List<String[]> methods = new ArrayList<>();  // {name, desc}
        final List<byte[]> codes = new ArrayList<>();      // code per method (or null)
        Object[] cp;
    }

    private static final Map<String, Info> cache = new HashMap<>();
    private static ClassLoader loader;

    public static void setLoader(ClassLoader cl) { if (cl != null) loader = cl; }

    /** "com.fs.Foo" -> parsed class, or null. */
    static synchronized Info info(String className) {
        if (className == null) return null;
        String key = className.replace('/', '.');
        if (cache.containsKey(key)) return cache.get(key);
        Info in = null;
        try { in = parse(read(key.replace('.', '/') + ".class")); } catch (Throwable ignored) {}
        cache.put(key, in);
        return in;
    }

    private static byte[] read(String res) throws java.io.IOException {
        ClassLoader cl = loader != null ? loader : Obf.class.getClassLoader();
        InputStream is = cl.getResourceAsStream(res);
        if (is == null && cl.getParent() != null) is = cl.getParent().getResourceAsStream(res);
        if (is == null) return null;
        try {
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = is.read(chunk)) > 0) {
                if (buf.remaining() < n) { ByteBuffer b2 = ByteBuffer.allocate(buf.capacity() * 2 + n); buf.flip(); b2.put(buf); buf = b2; }
                buf.put(chunk, 0, n);
            }
            byte[] out = new byte[buf.position()]; buf.flip(); buf.get(out); return out;
        } finally { is.close(); }
    }

    private static Info parse(byte[] d) {
        if (d == null) return null;
        ByteBuffer b = ByteBuffer.wrap(d);
        b.position(8);
        int n = b.getShort() & 0xFFFF;
        Object[] cp = new Object[n];
        for (int k = 1; k < n; k++) {
            int t = b.get() & 0xFF;
            switch (t) {
                case 1: { int l = b.getShort() & 0xFFFF; byte[] s = new byte[l]; b.get(s); cp[k] = modUtf8(s); break; }
                case 3: case 4: b.getInt(); break;
                case 5: case 6: b.getLong(); k++; break;
                case 7: case 8: case 16: case 19: case 20: cp[k] = new int[]{t, b.getShort() & 0xFFFF}; break;
                case 9: case 10: case 11: case 12: case 17: case 18: cp[k] = new int[]{t, b.getShort() & 0xFFFF, b.getShort() & 0xFFFF}; break;
                case 15: b.get(); b.getShort(); break;
                default: return null;
            }
        }
        Info in = new Info();
        in.cp = cp;
        b.getShort();
        in.self = cname(cp, b.getShort() & 0xFFFF);
        in.sup = cname(cp, b.getShort() & 0xFFFF);
        int ni = b.getShort() & 0xFFFF; for (int i = 0; i < ni; i++) b.getShort();
        for (int pass = 0; pass < 2; pass++) {
            int c = b.getShort() & 0xFFFF;
            for (int i = 0; i < c; i++) {
                b.getShort(); String name = (String) cp[b.getShort() & 0xFFFF], desc = (String) cp[b.getShort() & 0xFFFF];
                int ac = b.getShort() & 0xFFFF; byte[] code = null;
                for (int a = 0; a < ac; a++) {
                    String an = (String) cp[b.getShort() & 0xFFFF]; int al = b.getInt();
                    if ("Code".equals(an)) { int p = b.position(); b.position(p + 4); int cl = b.getInt(); code = new byte[cl]; b.get(code); b.position(p + al); }
                    else b.position(b.position() + al);
                }
                if (pass == 0) in.fields.add(new String[]{name, desc}); else { in.methods.add(new String[]{name, desc}); in.codes.add(code); }
            }
        }
        return in;
    }

    /** Java "modified UTF-8" (class files) -> String; plain UTF-8 decoding is right for everything the game uses. */
    private static String modUtf8(byte[] s) { return new String(s, StandardCharsets.UTF_8); }

    private static String cname(Object[] cp, int k) { if (k == 0) return null; int[] c = (int[]) cp[k]; return ((String) cp[c[1]]).replace('/', '.'); }

    /** "Lcom/fs/Foo;" -> "com.fs.Foo" (null for primitives/arrays). */
    public static String descClass(String desc) {
        if (desc == null || !desc.startsWith("L")) return null;
        return desc.substring(1, desc.length() - 1).replace('/', '.');
    }

    public static String fieldDesc(String cls, String field) {
        Info in = info(cls); if (in == null) return null;
        for (String[] f : in.fields) if (f[0].equals(field)) return f[1];
        return null;
    }

    /** 0.9.2.4: names of the fields declared in cls with exactly that descriptor (e.g. "Ljava/util/Map;"). */
    public static List<String> fieldsWithDesc(String cls, String desc) {
        List<String> out = new ArrayList<>();
        Info in = info(cls); if (in == null) return out;
        for (String[] f : in.fields) if (f[1].equals(desc)) out.add(f[0]);
        return out;
    }

    public static String fieldClass(String cls, String field) { return descClass(fieldDesc(cls, field)); }

    /** First method with that name: its descriptor. */
    public static String methodDesc(String cls, String method) {
        Info in = info(cls); if (in == null) return null;
        for (String[] m : in.methods) if (m[0].equals(method)) return m[1];
        return null;
    }

    public static String returnClass(String methodDesc) { return methodDesc == null ? null : descClass(methodDesc.substring(methodDesc.indexOf(')') + 1)); }
    public static String firstParamClass(String methodDesc) {
        if (methodDesc == null || methodDesc.length() < 3 || methodDesc.charAt(1) != 'L') return null;
        return descClass(methodDesc.substring(1, methodDesc.indexOf(';') + 1));
    }

    public static String superOf(String cls) { Info in = info(cls); return in == null ? null : in.sup; }

    /** The class (cls or a superclass) that declares the method, or null. */
    public static String declaring(String cls, String method) {
        for (int i = 0; i < 20 && cls != null; i++) {
            Info in = info(cls); if (in == null) return null;
            for (String[] m : in.methods) if (m[0].equals(method)) return cls;
            cls = in.sup;
        }
        return null;
    }

    /** All methods of that name declared by the class: descriptors. */
    public static List<String> methodDescs(String cls, String method) {
        List<String> out = new ArrayList<>();
        Info in = info(cls); if (in == null) return out;
        for (String[] m : in.methods) if (m[0].equals(method)) out.add(m[1]);
        return out;
    }

    /** getfield/putfield references in a method body, in order (op 0xB4 = get, 0xB5 = put). Naive byte scan. */
    public static List<Ref> fieldRefs(String cls, String method, String desc) {
        List<Ref> out = new ArrayList<>();
        Info in = info(cls); if (in == null) return out;
        for (int i = 0; i < in.methods.size(); i++) {
            String[] m = in.methods.get(i); byte[] code = in.codes.get(i);
            if (!m[0].equals(method) || (desc != null && !m[1].equals(desc)) || code == null) continue;
            for (int p = 0; p + 2 < code.length; p++) {
                int op = code[p] & 0xFF;
                if (op != 0xB4 && op != 0xB5) continue;
                int k = ((code[p + 1] & 0xFF) << 8) | (code[p + 2] & 0xFF);
                if (k <= 0 || k >= in.cp.length || !(in.cp[k] instanceof int[]) || ((int[]) in.cp[k])[0] != 9) continue;
                int[] fr = (int[]) in.cp[k]; int[] nt = (int[]) in.cp[fr[2]];
                out.add(new Ref(op, cname(in.cp, fr[1]), (String) in.cp[nt[1]], (String) in.cp[nt[2]]));
            }
        }
        return out;
    }

    /** 0.1.6.5: invokestatic references (op 0xB8) to Methodrefs in a method body, in order. Naive byte scan. */
    public static List<Ref> staticCalls(String cls, String method) {
        List<Ref> out = new ArrayList<>();
        Info in = info(cls); if (in == null) return out;
        for (int i = 0; i < in.methods.size(); i++) {
            String[] m = in.methods.get(i); byte[] code = in.codes.get(i);
            if (!m[0].equals(method) || code == null) continue;
            for (int p = 0; p + 2 < code.length; p++) {
                if ((code[p] & 0xFF) != 0xB8) continue;
                int k = ((code[p + 1] & 0xFF) << 8) | (code[p + 2] & 0xFF);
                if (k <= 0 || k >= in.cp.length || !(in.cp[k] instanceof int[]) || ((int[]) in.cp[k])[0] != 10) continue;
                int[] fr = (int[]) in.cp[k]; int[] nt = (int[]) in.cp[fr[2]];
                out.add(new Ref(0xB8, cname(in.cp, fr[1]), (String) in.cp[nt[1]], (String) in.cp[nt[2]]));
            }
        }
        return out;
    }
}
