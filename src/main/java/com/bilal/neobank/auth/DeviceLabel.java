package com.bilal.neobank.auth;

/**
 * Turns a User-Agent into something a person recognises ("Chrome on Windows"). Deliberately crude: it only has to help a
 * customer tell their own devices apart, and the raw header (which can be long and fingerprint-y) is never stored.
 */
public final class DeviceLabel {

    static final int MAX_LENGTH = 60;
    static final String UNKNOWN = "Unknown device";

    private DeviceLabel() {
    }

    public static String of(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String browser = browser(userAgent);
        String os = os(userAgent);
        String label = browser == null && os == null ? UNKNOWN
                : browser == null ? os : os == null ? browser : browser + " on " + os;
        return label.length() <= MAX_LENGTH ? label : label.substring(0, MAX_LENGTH);
    }

    /** Order matters: Edge and Opera also say "Chrome", and Chrome also says "Safari". */
    private static String browser(String ua) {
        if (ua.contains("Edg/") || ua.contains("EdgA/") || ua.contains("EdgiOS/")) return "Edge";
        if (ua.contains("OPR/") || ua.contains("Opera")) return "Opera";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/") && ua.contains("Version/")) return "Safari";
        if (ua.startsWith("curl/")) return "curl";
        return null;
    }

    /** Order matters: Android says "Linux", and iPhones say "like Mac OS X". */
    private static String os(String ua) {
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) return "iOS";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "macOS";
        if (ua.contains("CrOS")) return "ChromeOS";
        if (ua.contains("Linux") || ua.contains("X11")) return "Linux";
        return null;
    }
}
