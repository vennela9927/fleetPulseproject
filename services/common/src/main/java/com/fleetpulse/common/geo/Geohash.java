package com.fleetpulse.common.geo;

/**
 * Geohash encoding and great-circle distance.
 * Geohash interleaves longitude and latitude bits so nearby points share a prefix;
 * a precision-6 cell is roughly 1.2 km x 0.6 km, which is what we bucket vehicles by
 * when looking for clusters parked away from depots.
 */
public final class Geohash {

    private static final char[] BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz".toCharArray();
    private static final double EARTH_RADIUS_M = 6_371_000;

    private Geohash() {}

    public static String encode(double lat, double lon, int precision) {
        if (precision < 1 || precision > 12) throw new IllegalArgumentException("precision 1..12");
        double latLo = -90, latHi = 90, lonLo = -180, lonHi = 180;
        StringBuilder out = new StringBuilder(precision);
        boolean evenBit = true;  // longitude first
        int bit = 0, ch = 0;
        while (out.length() < precision) {
            if (evenBit) {
                double mid = (lonLo + lonHi) / 2;
                if (lon >= mid) { ch = (ch << 1) | 1; lonLo = mid; } else { ch <<= 1; lonHi = mid; }
            } else {
                double mid = (latLo + latHi) / 2;
                if (lat >= mid) { ch = (ch << 1) | 1; latLo = mid; } else { ch <<= 1; latHi = mid; }
            }
            evenBit = !evenBit;
            if (++bit == 5) {
                out.append(BASE32[ch]);
                bit = 0;
                ch = 0;
            }
        }
        return out.toString();
    }

    /** Haversine distance in metres. */
    public static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }
}
