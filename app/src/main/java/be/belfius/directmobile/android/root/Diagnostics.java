package be.belfius.directmobile.android.root;

/**
 * Compact, readable rendering of the values the diagnostics log.
 *
 * <p>All of it is debug-only output: the {@code shortVal}/{@code tokenDump} forms are what makes a
 * token {@code Object[]} or a field dump fit on a logcat line. Pure functions, no state.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    /** Reflectively dump all fields of an object (its class plus each superclass). */
    static String dumpFields(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getName()).append("@")
                .append(Integer.toHexString(System.identityHashCode(o)));
        try {
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        sb.append("\n  ").append(k.getSimpleName()).append('.').append(f.getName())
                                .append(" (").append(f.getType().getSimpleName()).append(") = ")
                                .append(fmtVal(v));
                    } catch (Throwable t) {
                        sb.append("\n  ").append(k.getSimpleName()).append('.').append(f.getName())
                                .append(" = <err ").append(t).append('>');
                    }
                }
            }
        } catch (Throwable t) {
            sb.append("\n  <dump failed: ").append(t).append('>');
        }
        return sb.toString();
    }

    /** Value rendering for field dumps: scalars verbatim, arrays truncated to their first entries. */
    static String fmtVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return v.toString();
        if (v instanceof String) return "\"" + v + "\"";
        if (v.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(v);
            StringBuilder sb = new StringBuilder().append(v.getClass().getSimpleName())
                    .append('[').append(n).append("]{");
            for (int i = 0; i < Math.min(n, 16); i++) {
                if (i > 0) sb.append(',');
                sb.append(fmtVal(java.lang.reflect.Array.get(v, i)));
            }
            return sb.append('}').toString();
        }
        return v.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(v));
    }

    /** Compact readable form of a single value — the building block of every diagnostic line. */
    static String shortVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof Character) return String.valueOf(v);
        if (v instanceof int[]) {
            int[] a = (int[]) v;
            StringBuilder sb = new StringBuilder("int[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 40; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof long[]) {
            long[] a = (long[]) v;
            StringBuilder sb = new StringBuilder("long[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 20; i++) sb.append(i == 0 ? "" : ",").append(a[i]);
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof byte[]) {
            byte[] a = (byte[]) v;
            StringBuilder sb = new StringBuilder("byte[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 24; i++) {
                sb.append(i == 0 ? "" : ",").append(a[i]);
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof String[]) {
            String[] a = (String[]) v;
            StringBuilder sb = new StringBuilder("String[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 12; i++) {
                String s = a[i];
                sb.append(i == 0 ? "" : " | ").append(s == null ? "null" : s.substring(0, Math.min(s.length(), 120)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder sb = new StringBuilder("Obj[");
            sb.append(a.length).append("]{");
            for (int i = 0; i < a.length && i < 12; i++) sb.append(i == 0 ? "" : ", ").append(shortVal(a[i]));
            sb.append('}');
            return sb.toString();
        }
        return v.getClass().getSimpleName() + "@" + System.identityHashCode(v);
    }

    /** Full dump of the token {@code Object[]}: identity plus every slot with its array contents. */
    static String tokenDump(Object[] arr) {
        if (arr == null) return "null";
        StringBuilder sb = new StringBuilder("objArr@" + System.identityHashCode(arr) + " len=" + arr.length);
        for (int i = 0; i < arr.length; i++) {
            sb.append(' ').append(i).append('=').append(shortVal(arr[i]));
        }
        return sb.toString();
    }
}
