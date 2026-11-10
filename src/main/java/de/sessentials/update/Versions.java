package de.sessentials.update;

/** Vergleicht Versionen wie 1.2.10 > 1.2.9; Teile werden an '.' und '-' getrennt, Zahlen numerisch verglichen.
 * Eine Version ohne Zusatz ist neuer als dieselbe mit Zusatz (1.3.0 > 1.3.0-beta.1). */
final class Versions {

    private Versions() {}

    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : null, q = i < y.length ? y[i] : null;
            // fehlender Teil gegen Text (1.3.0 vs. 1.3.0-beta.1): die Version ohne Zusatz ist neuer
            if (p == null && !q.matches("\\d+")) return 1;
            if (q == null && !p.matches("\\d+")) return -1;
            if (p == null) p = "0";
            if (q == null) q = "0";
            int c = p.matches("\\d+") && q.matches("\\d+")
                    ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }
}
