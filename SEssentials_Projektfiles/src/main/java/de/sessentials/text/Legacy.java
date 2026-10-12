package de.sessentials.text;

/** Übersetzt &- und §-Farbcodes (inkl. &#RRGGBB und §x§R§R§G§G§B§B) in MiniMessage-Tags. */
public final class Legacy {

    private Legacy() {}

    public static String toMiniMessage(String in) {
        if (in == null) return "";
        if (in.indexOf('&') < 0 && in.indexOf('§') < 0) return in;

        StringBuilder out = new StringBuilder(in.length() + 16);
        int len = in.length();
        for (int i = 0; i < len; i++) {
            char c = in.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < len) {
                char n = Character.toLowerCase(in.charAt(i + 1));

                // &#RRGGBB
                if (n == '#' && isHex(in, i + 2, 6)) {
                    out.append("<#").append(in, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                // §x§R§R§G§G§B§B
                if (n == 'x' && i + 14 <= len) {
                    StringBuilder hex = new StringBuilder(6);
                    boolean ok = true;
                    for (int k = 0; k < 6; k++) {
                        int pos = i + 2 + k * 2;
                        char marker = in.charAt(pos);
                        char digit = in.charAt(pos + 1);
                        if ((marker != '&' && marker != '§') || Character.digit(digit, 16) < 0) {
                            ok = false;
                            break;
                        }
                        hex.append(digit);
                    }
                    if (ok) {
                        out.append("<#").append(hex).append('>');
                        i += 13;
                        continue;
                    }
                }

                String tag = switch (n) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String s, int start, int count) {
        if (start + count > s.length()) return false;
        for (int i = start; i < start + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }
}
