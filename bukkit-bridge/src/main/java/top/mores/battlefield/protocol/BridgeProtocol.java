package top.mores.battlefield.protocol;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;

/** Strict bounded JSON. No polymorphic/deserializable Java type names are accepted. */
public final class BridgeProtocol {
    public static final int API = 3, BACKEND_PROTOCOL = 2, VERSION = 1;
    public static final int MAX_JSON = 131072, MAX_DEPTH = 24, MAX_STRING = 2048, MAX_ENTRIES = 512;
    public static final Gson JSON = new Gson();
    private BridgeProtocol() {}
    public static void requireVersions(long api, long backendProtocol, long bridgeProtocol) {
        if (api != API || backendProtocol != BACKEND_PROTOCOL || bridgeProtocol != VERSION) throw new IllegalArgumentException("API_INCOMPATIBLE");
    }
    public static JsonObject parse(String json) {
        if (json == null || json.length() > MAX_JSON || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON) throw new IllegalArgumentException("JSON size limit");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) throw new IllegalArgumentException("JSON object required");
            return value.getAsJsonObject();
        } catch (IOException | IllegalStateException e) { throw new IllegalArgumentException("Invalid JSON", e); }
    }
    private static JsonElement read(JsonReader r, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON depth limit");
        switch (r.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject(); r.beginObject(); int count = 0;
                while (r.hasNext()) {
                    String name = r.nextName();
                    if (++count > MAX_ENTRIES || name.length() > 128 || result.has(name)) throw new IllegalArgumentException("Duplicate/oversized JSON field");
                    result.add(name, read(r, depth + 1));
                }
                r.endObject(); return result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray(); r.beginArray();
                while (r.hasNext()) { if (result.size() >= MAX_ENTRIES) throw new IllegalArgumentException("JSON array limit"); result.add(read(r, depth + 1)); }
                r.endArray(); return result;
            }
            case STRING -> { String s = r.nextString(); if (s.length() > MAX_STRING) throw new IllegalArgumentException("JSON string limit"); return new JsonPrimitive(s); }
            case NUMBER -> { String s = r.nextString(); if (!s.matches("-?[0-9]{1,19}")) throw new IllegalArgumentException("Integer required"); return new JsonPrimitive(Long.parseLong(s)); }
            case BOOLEAN -> { return new JsonPrimitive(r.nextBoolean()); }
            case NULL -> { r.nextNull(); return JsonNull.INSTANCE; }
            default -> throw new IllegalArgumentException("Unexpected JSON token");
        }
    }
    public static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing string " + key);
        return e.getAsString();
    }
    public static String id(String raw) {
        if (raw == null || raw.length() > 128 || !raw.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw new IllegalArgumentException("Invalid resource ID");
        return raw;
    }
    public static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key); if (e == null || !e.isJsonObject()) throw new IllegalArgumentException("Missing object " + key); return e.getAsJsonObject();
    }
    public static long number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Missing integer " + key);
        long value = e.getAsLong(); if (value < 0) throw new IllegalArgumentException("Negative " + key); return value;
    }
    public static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key); if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Missing boolean " + key); return e.getAsBoolean();
    }
    public static String failure(String code, String message) {
        JsonObject r = new JsonObject(); r.addProperty("code", code); r.addProperty("message", message); return JSON.toJson(r);
    }
    public static String compatibility(String state, String code, String reason) {
        JsonObject r = new JsonObject(); r.addProperty("state", state); r.addProperty("detail", code); r.addProperty("reason", reason); return JSON.toJson(r);
    }
}
