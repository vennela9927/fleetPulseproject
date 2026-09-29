package com.fleetpulse.common.dtc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses OBD-II Diagnostic Trouble Codes (SAE J2012) from the shapes OEMs actually send:
 * <ul>
 *   <li>text codes, possibly lower-case or embedded in free text: {@code "p0301, P0420"}</li>
 *   <li>raw two-byte hex as read from the bus: {@code "0301"} → P0301, {@code "C100"} → U0100</li>
 * </ul>
 * In the raw form the top two bits select the system (00=P powertrain, 01=C chassis,
 * 10=B body, 11=U network), the next two bits are the first digit (0-3) and the remaining
 * twelve bits are three hex digits.
 */
public final class Dtc {

    /** Canonical form: system letter, digit 0-3, three hex digits. */
    public static final Pattern CANONICAL = Pattern.compile("^[PCBU][0-3][0-9A-F]{3}$");
    private static final Pattern IN_TEXT = Pattern.compile("(?i)\\b([PCBU][0-3][0-9A-F]{3})\\b");
    private static final Pattern RAW_HEX = Pattern.compile("^(?:0x)?([0-9A-Fa-f]{4})$");
    private static final char[] SYSTEMS = {'P', 'C', 'B', 'U'};

    private Dtc() {}

    public static boolean isCanonical(String code) {
        return code != null && CANONICAL.matcher(code).matches();
    }

    /** Decodes a raw 16-bit DTC, e.g. {@code 0x0301} → {@code P0301}. */
    public static String fromRaw(int raw) {
        if (raw < 0 || raw > 0xFFFF) throw new IllegalArgumentException("DTC must fit in 16 bits");
        char system = SYSTEMS[(raw >> 14) & 0b11];
        int firstDigit = (raw >> 12) & 0b11;
        return String.format("%c%d%03X", system, firstDigit, raw & 0x0FFF);
    }

    /** Inverse of {@link #fromRaw(int)}. */
    public static int toRaw(String code) {
        if (!isCanonical(code)) throw new IllegalArgumentException("not a canonical DTC: " + code);
        int system = "PCBU".indexOf(code.charAt(0));
        return (system << 14) | ((code.charAt(1) - '0') << 12) | Integer.parseInt(code.substring(2), 16);
    }

    /** Normalises one token in either text or raw-hex form; returns null if it is neither. */
    public static String normalise(String token) {
        if (token == null) return null;
        String t = token.trim();
        if (t.isEmpty()) return null;
        String upper = t.toUpperCase(Locale.ROOT);
        if (isCanonical(upper)) return upper;
        Matcher hex = RAW_HEX.matcher(t);
        if (hex.matches()) return fromRaw(Integer.parseInt(hex.group(1), 16));
        return null;
    }

    /** Extracts every DTC from free text, de-duplicated, in first-seen order. */
    public static List<String> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        Matcher m = IN_TEXT.matcher(text);
        while (m.find()) found.add(m.group(1).toUpperCase(Locale.ROOT));
        return new ArrayList<>(found);
    }
}
