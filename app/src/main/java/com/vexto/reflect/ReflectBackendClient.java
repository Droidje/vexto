package com.vexto.reflect;

import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** The recording API. Each request owns and closes its connection. */
final class ReflectBackendClient {
    private static final int CONNECT_TIMEOUT_MS = 10_000, READ_TIMEOUT_MS = 25_000;
    private static final int MAX_JSON = 2 * 1024 * 1024;
    private static final long MAX_AUDIO = 2L * 1024 * 1024 * 1024;
    private static final String JSON = "application/json";
    private static final String TRANSCRIPT_REVISION = "X-Transcript-Revision";
    private static final Pattern COORDINATES = Pattern.compile("(-?\\d+(?:\\.\\d+)?)°, (-?\\d+(?:\\.\\d+)?)°");

    record LinkResponse(JSONObject value, String revision) {}

    static final class Config {
        final String url, token, scope;

        Config(String rawUrl, String token) {
            url = normalizeUrl(rawUrl);
            if (token == null || !token.matches("[!-~]{24,256}")) {
                throw new IllegalArgumentException("Введите ключ доступа сервера");
            }
            this.token = token;
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest((url + "\0" + token).getBytes(StandardCharsets.UTF_8));
                StringBuilder name = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    name.append(Character.forDigit((value & 0xff) >>> 4, 16));
                    name.append(Character.forDigit(value & 0xf, 16));
                }
                scope = name.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static String normalizeUrl(String rawUrl) {
            try {
                URI uri = new URI(rawUrl.trim()).normalize();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                int port = uri.getPort();
                String path = uri.getRawPath();
                boolean local = host.equals("10.0.2.2") || host.equals("localhost") || host.equals("127.0.0.1");
                if (host.isEmpty() || path == null || !path.matches("(/[A-Za-z0-9._~/-]*)?")
                        || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || port == 0 || port > 65535
                        || !(scheme.equals("https") || (BuildConfig.DEBUG && scheme.equals("http") && local))) {
                    throw new URISyntaxException("", "Invalid backend URL");
                }
                int defaultPort = scheme.equals("https") ? 443 : 80;
                String base = new URI(scheme, null, host, port == defaultPort ? -1 : port,
                        path.replaceFirst("/+$", ""), null, null).toASCIIString();
                return base.endsWith("/v1") ? base.substring(0, base.length() - 3) : base;
            } catch (URISyntaxException invalid) {
                throw new IllegalArgumentException("Укажите HTTPS-адрес сервера");
            }
        }
    }

    static final class HttpError extends IOException {
        final int status;

        HttpError(int status) {
            super("Backend HTTP " + status);
            this.status = status;
        }

        boolean transientFailure() { return status == 408 || status == 429 || status >= 500; }
    }

    private final Config config;

    ReflectBackendClient(Config config) { this.config = config; }

    void uploadAudio(String id, File audio, String mime) throws IOException {
        long bytes = audio.length();
        if (bytes <= 0 || bytes > MAX_AUDIO) throw new HttpError(413);
        request("PUT", recording(id) + "/audio", audioMime(mime), bytes,
                stream -> Files.copy(audio.toPath(), stream), null);
    }

    void uploadMetadata(String id, File audio, String title, int durationMs, RecordingMetadata metadata)
            throws IOException {
        JSONObject value = new JSONObject();
        try {
            value.put("title", title);
            value.put("duration_ms", durationMs);
            Instant date = metadata.date();
            value.put("recorded_at", (date == null ? Instant.ofEpochMilli(audio.lastModified()) : date).toString());
            value.put("filename", audio.getName());
            value.put("mime_type", audioMime(metadata.mime()));
            var match = COORDINATES.matcher(metadata.location());
            if (match.matches()) {
                value.put("location", new JSONObject().put("latitude", Double.parseDouble(match.group(1)))
                        .put("longitude", Double.parseDouble(match.group(2))));
            }
        } catch (JSONException invalid) {
            throw new IOException("Invalid recording metadata", invalid);
        }
        sendJson("PUT", recording(id) + "/metadata", value, null);
    }

    void uploadTranscript(String id, ReflectTranscript transcript) throws IOException {
        byte[] json = transcript.encode();
        request("PUT", recording(id) + "/transcript", JSON, json.length, out -> out.write(json), null);
    }

    record TranscriptResponse(ReflectTranscript value, String revision) {}

    TranscriptResponse transcript(String id) throws IOException {
        try {
            return request("GET", recording(id) + "/transcript", null, 0, null,
                    (response, connection) -> new TranscriptResponse(ReflectTranscript.parse(response),
                            connection.getHeaderField(TRANSCRIPT_REVISION)));
        } catch (HttpError failure) {
            if (failure.status == 404) return null;
            throw failure;
        }
    }

    JSONObject speakers() throws IOException { return getJson("/speakers"); }

    JSONObject createSpeaker(String name) throws IOException {
        return sendJson("POST", "/speakers", object("name", name, "id", UUID.randomUUID().toString()),
                ReflectBackendClient::readJson);
    }

    JSONObject renameSpeaker(String id, String name) throws IOException {
        return sendJson("PATCH", "/speakers/" + Uri.encode(id), object("name", name, null, null),
                ReflectBackendClient::readJson);
    }

    void deleteSpeaker(String id) throws IOException {
        request("DELETE", "/speakers/" + Uri.encode(id), null, 0, null, null);
    }

    LinkResponse speakerLinks(String id, String revision) throws IOException {
        String suffix = revision == null ? "" : "?revision=" + Uri.encode(revision);
        return request("GET", recording(id) + "/speaker-links" + suffix, null, 0, null,
                (response, connection) -> new LinkResponse(readJson(response, connection),
                        connection.getHeaderField(TRANSCRIPT_REVISION)));
    }

    void linkSpeaker(String id, String localId, String speakerId, String revision) throws IOException {
        String label = localId.equals(".") || localId.equals("..") ? localId.replace(".", "%2E") : Uri.encode(localId);
        JSONObject value = object("speaker_id", speakerId == null ? JSONObject.NULL : speakerId, null, null);
        byte[] body = value.toString().getBytes(StandardCharsets.UTF_8);
        request("PUT", recording(id) + "/speaker-links/" + label, JSON, body.length,
                out -> out.write(body), null, revision);
    }

    private static String audioMime(String mime) {
        return mime != null && mime.startsWith("audio/") ? mime : "application/octet-stream";
    }

    private static String recording(String id) { return "/recordings/" + Uri.encode(id); }

    private static JSONObject object(String key, Object value, String optionalKey, Object optionalValue)
            throws IOException {
        try {
            JSONObject result = new JSONObject().put(key, value);
            if (optionalKey != null) result.put(optionalKey, optionalValue);
            return result;
        } catch (JSONException malformed) {
            throw new IOException("Invalid backend request", malformed);
        }
    }

    private <T> T sendJson(String method, String path, JSONObject payload, Reader<T> read) throws IOException {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        return request(method, path, JSON, body.length, out -> out.write(body), read);
    }

    private JSONObject getJson(String path) throws IOException {
        return request("GET", path, null, 0, null, ReflectBackendClient::readJson);
    }

    private <T> T request(String method, String path, String contentType, long bytes, Writer write, Reader<T> read)
            throws IOException {
        return request(method, path, contentType, bytes, write, read, null);
    }

    private <T> T request(String method, String path, String contentType, long bytes, Writer write, Reader<T> read,
                          String revision) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(config.url + "/v1" + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + config.token);
            connection.setRequestProperty("Accept", JSON);
            if (revision != null) connection.setRequestProperty(TRANSCRIPT_REVISION, revision);
            if (write != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes);
                connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream body = connection.getOutputStream()) {
                    write.write(body);
                } catch (IOException interrupted) {
                    int status;
                    try {
                        status = connection.getResponseCode();
                    } catch (IOException unavailable) {
                        throw interrupted;
                    }
                    if (status >= 300) throw new HttpError(status);
                    throw interrupted;
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new HttpError(status);
            try (InputStream response = connection.getInputStream()) {
                return read == null ? null : read.read(response, connection);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream input, HttpURLConnection connection) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (data.size() + count > MAX_JSON) throw new IOException("Backend response is too large");
            data.write(buffer, 0, count);
        }
        try {
            return new JSONObject(new String(data.toByteArray(), StandardCharsets.UTF_8));
        } catch (JSONException invalid) {
            throw new IOException("Invalid backend response", invalid);
        }
    }

    private interface Reader<T> { T read(InputStream input, HttpURLConnection connection) throws IOException; }
    private interface Writer { void write(OutputStream output) throws IOException; }
}
