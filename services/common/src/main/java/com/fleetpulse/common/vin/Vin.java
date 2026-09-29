package com.fleetpulse.common.vin;

/**
 * ISO 3779 / North American VIN rules: 17 characters, no I, O or Q, and a check digit
 * in position 9 computed from transliterated values and positional weights (mod 11).
 * All operations are O(17): a single pass with table lookups, no allocation on the
 * validation path, so it is safe to call for every event on the hot path.
 */
public final class Vin {

    public static final int LENGTH = 17;
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] VALUE = new int[128];
    private static final String YEAR_CODES = "ABCDEFGHJKLMNPRSTVWXY123456789"; // 2010..2039

    static {
        java.util.Arrays.fill(VALUE, -1);
        for (char c = '0'; c <= '9'; c++) VALUE[c] = c - '0';
        String letters = "ABCDEFGHJKLMNPRSTUVWXYZ";
        int[] vals =    {1, 2, 3, 4, 5, 6, 7, 8, 1, 2, 3, 4, 5, 7, 9, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int i = 0; i < letters.length(); i++) VALUE[letters.charAt(i)] = vals[i];
    }

    private Vin() {}

    /** Result of validation; {@code reason} is null when valid. */
    public record Check(boolean valid, String reason) {
        static final Check OK = new Check(true, null);
        static Check fail(String reason) { return new Check(false, reason); }
    }

    public static boolean isValid(String vin) {
        return check(vin).valid();
    }

    public static Check check(String vin) {
        if (vin == null) return Check.fail("VIN_MISSING");
        if (vin.length() != LENGTH) return Check.fail("VIN_LENGTH");
        int sum = 0;
        for (int i = 0; i < LENGTH; i++) {
            char c = vin.charAt(i);
            if (c >= 128 || VALUE[c] < 0) return Check.fail("VIN_ILLEGAL_CHAR");
            sum += VALUE[c] * WEIGHTS[i];
        }
        return vin.charAt(8) == checkChar(sum) ? Check.OK : Check.fail("VIN_CHECK_DIGIT");
    }

    /** World Manufacturer Identifier: the first three characters. */
    public static String wmi(String vin) {
        return vin.substring(0, 3);
    }

    /**
     * Builds a valid VIN for simulated vehicles. Deterministic for a given serial so the
     * seeded database and the running simulator always agree.
     *
     * @param wmi       3-character manufacturer code
     * @param modelCode 5-character vehicle descriptor (positions 4-8)
     * @param modelYear 2010..2039
     * @param plant     plant code (position 11)
     * @param serial    0..999_999
     */
    public static String generate(String wmi, String modelCode, int modelYear, char plant, int serial) {
        if (wmi.length() != 3 || modelCode.length() != 5) throw new IllegalArgumentException("bad WMI/VDS length");
        if (modelYear < 2010 || modelYear > 2039) throw new IllegalArgumentException("model year out of range");
        if (serial < 0 || serial > 999_999) throw new IllegalArgumentException("serial out of range");
        char[] v = (wmi + modelCode + '0' + YEAR_CODES.charAt(modelYear - 2010) + plant
                + String.format("%06d", serial)).toCharArray();
        int sum = 0;
        for (int i = 0; i < LENGTH; i++) {
            if (v[i] >= 128 || VALUE[v[i]] < 0) throw new IllegalArgumentException("illegal VIN character " + v[i]);
            sum += VALUE[v[i]] * WEIGHTS[i];
        }
        v[8] = checkChar(sum);
        return new String(v);
    }

    private static char checkChar(int sum) {
        int r = sum % 11;
        return r == 10 ? 'X' : (char) ('0' + r);
    }
}
