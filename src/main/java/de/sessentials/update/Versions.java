package de.sessentials.update;

/** Vergleicht Versionen wie 1.2.10 > 1.2.9; Teile werden an '.' und '-' getrennt, Zahlen numerisch verglichen. */
final class Versions {

    private Versions() {}

    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+")
                    ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }
}
