package com.vinaykpro.ludoking.update;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java, zero-dependency RFC-8259 JSON parser and strict validator for UpdateManifest.
 * Works identically on Android devices and pure JVM JUnit tests without Robolectric stubs.
 */
public final class UpdateManifestParser {

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }

    private UpdateManifestParser() {}

    public static boolean isConfiguredManifestUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.contains("<OWNER>") || trimmed.contains("<REPO>") || trimmed.contains("PLACEHOLDER")) {
            return false;
        }
        return isValidHttpsUrl(trimmed);
    }

    public static boolean isValidHttpsUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.US).startsWith("https://")) {
            return false;
        }
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && !host.trim().isEmpty()
                    && !trimmed.contains("<")
                    && !trimmed.contains(">")
                    && !trimmed.contains(" ");
        } catch (Exception e) {
            return false;
        }
    }

    public static UpdateManifest parse(String rawJson) throws ParseException {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            throw new ParseException("Manifest JSON is empty.");
        }
        Object parsed;
        try {
            parsed = new JsonReader(rawJson).parseRoot();
        } catch (IllegalArgumentException e) {
            throw new ParseException("Invalid JSON syntax: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new ParseException("Manifest root must be a JSON object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) parsed;

        int manifestVersion = requirePositiveInt(root, "manifestVersion");
        String generatedAt = optionalString(root, "generatedAt", "");
        Object appsObj = root.get("apps");
        if (!(appsObj instanceof Map)) {
            throw new ParseException("Manifest missing required 'apps' object.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> appsMapRaw = (Map<String, Object>) appsObj;
        if (appsMapRaw.isEmpty()) {
            throw new ParseException("Manifest 'apps' map must contain at least one application.");
        }

        Map<String, UpdateManifest.AppReleaseInfo> apps = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : appsMapRaw.entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                throw new ParseException("App entry '" + key + "' must be a JSON object.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> appObj = (Map<String, Object>) entry.getValue();
            UpdateManifest.AppReleaseInfo info = parseAppEntry(key, appObj);
            apps.put(info.appId, info);
        }

        return new UpdateManifest(manifestVersion, generatedAt, apps);
    }

    private static UpdateManifest.AppReleaseInfo parseAppEntry(String mapKey, Map<String, Object> obj) throws ParseException {
        String appId = optionalString(obj, "appId", mapKey);
        if (appId.trim().isEmpty()) {
            throw new ParseException("App entry missing 'appId'.");
        }
        String packageName = requireNonEmptyString(obj, "packageName", appId);
        String displayName = optionalString(obj, "displayName", appId);
        String latestVersionName = requireNonEmptyString(obj, "latestVersionName", appId);
        int latestVersionCode = requirePositiveInt(obj, "latestVersionCode");
        int minSupportedVersionCode = obj.containsKey("minSupportedVersionCode")
                ? requirePositiveInt(obj, "minSupportedVersionCode")
                : 1;
        String apkUrl = requireNonEmptyString(obj, "apkUrl", appId);
        String sha256 = requireNonEmptyString(obj, "sha256", appId).toLowerCase(Locale.US);
        String releaseNotes = optionalString(obj, "releaseNotes", "");
        String releaseDate = optionalString(obj, "releaseDate", "");
        boolean mandatory = optionalBoolean(obj, "mandatory", false);

        return new UpdateManifest.AppReleaseInfo(
                appId.trim(),
                packageName.trim(),
                displayName.trim(),
                latestVersionName.trim(),
                latestVersionCode,
                minSupportedVersionCode,
                apkUrl.trim(),
                sha256.trim(),
                releaseNotes,
                releaseDate,
                mandatory
        );
    }

    private static String requireNonEmptyString(Map<String, Object> obj, String field, String context) throws ParseException {
        Object val = obj.get(field);
        if (!(val instanceof String) || ((String) val).trim().isEmpty()) {
            throw new ParseException("Missing or empty string field '" + field + "' in '" + context + "'.");
        }
        return (String) val;
    }

    private static String optionalString(Map<String, Object> obj, String field, String fallback) throws ParseException {
        if (!obj.containsKey(field) || obj.get(field) == null) {
            return fallback;
        }
        Object val = obj.get(field);
        if (!(val instanceof String)) {
            throw new ParseException("Field '" + field + "' must be a string.");
        }
        return (String) val;
    }

    private static int requirePositiveInt(Map<String, Object> obj, String field) throws ParseException {
        Object val = obj.get(field);
        if (!(val instanceof Number)) {
            throw new ParseException("Missing or non-numeric field '" + field + "'.");
        }
        double d = ((Number) val).doubleValue();
        int i = ((Number) val).intValue();
        if (d != i || i < 1) {
            throw new ParseException("Field '" + field + "' must be a positive integer.");
        }
        return i;
    }

    private static boolean optionalBoolean(Map<String, Object> obj, String field, boolean fallback) throws ParseException {
        if (!obj.containsKey(field) || obj.get(field) == null) {
            return fallback;
        }
        Object val = obj.get(field);
        if (!(val instanceof Boolean)) {
            throw new ParseException("Field '" + field + "' must be a boolean.");
        }
        return (Boolean) val;
    }

    private static final class JsonReader {
        private final String s;
        private int pos = 0;

        JsonReader(String s) {
            this.s = s;
        }

        Object parseRoot() {
            skipWhitespace();
            Object val = readValue();
            skipWhitespace();
            if (pos < s.length()) {
                throw new IllegalArgumentException("Unexpected trailing characters at index " + pos);
            }
            return val;
        }

        private Object readValue() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char '" + c + "' at index " + pos);
            }
        }

        private Map<String, Object> readObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) {
                    throw new IllegalArgumentException("Expected object key string at index " + pos);
                }
                String key = readString();
                skipWhitespace();
                expectChar(':');
                Object val = readValue();
                map.put(key, val);
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek('}')) {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at index " + pos);
                }
            }
        }

        private List<Object> readArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (peek(',')) {
                    pos++;
                } else if (peek(']')) {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at index " + pos);
                }
            }
        }

        private String readString() {
            expectChar('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private Number readNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            boolean hasDigits = false;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
                hasDigits = true;
            }
            if (!hasDigits) {
                throw new IllegalArgumentException("Invalid number at index " + start);
            }
            boolean isFloating = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = s.substring(start, pos);
            try {
                if (isFloating) {
                    return Double.parseDouble(numStr);
                }
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number '" + numStr + "'");
            }
        }

        private void expectLiteral(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw new IllegalArgumentException("Expected '" + literal + "' at index " + pos);
            }
            pos += literal.length();
        }

        private void expectChar(char expected) {
            if (pos >= s.length() || s.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + pos);
            }
            pos++;
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
