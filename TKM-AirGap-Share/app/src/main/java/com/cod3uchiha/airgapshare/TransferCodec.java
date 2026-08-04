package com.cod3uchiha.airgapshare;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class TransferCodec {
    static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
    private static final int CHUNK_BYTES = 650;
    private static final int B64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private TransferCodec() {}

    static final class PreparedTransfer {
        final String fileName;
        final String mimeType;
        final long originalSize;
        final boolean compressed;
        final ArrayList<String> frames;

        PreparedTransfer(String fileName, String mimeType, long originalSize,
                         boolean compressed, ArrayList<String> frames) {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.originalSize = originalSize;
            this.compressed = compressed;
            this.frames = frames;
        }
    }

    static PreparedTransfer prepare(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String fileName = displayName(resolver, uri);
        String mime = resolver.getType(uri);
        if (mime == null || mime.isBlank()) mime = "application/octet-stream";

        byte[] original;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("The selected file could not be opened.");
            original = readLimited(input, MAX_FILE_BYTES);
        }

        byte[] zipped = gzip(original);
        boolean compressed = zipped.length + 64 < original.length;
        byte[] payload = compressed ? zipped : original;
        String session = randomSession();
        String hash = sha256(original);
        int total = Math.max(1, (payload.length + CHUNK_BYTES - 1) / CHUNK_BYTES);

        ArrayList<String> frames = new ArrayList<>(total + 1);
        frames.add("AGS1H|" + session + "|" + total + "|" + original.length + "|" +
                (compressed ? "1" : "0") + "|" + hash + "|" + b64(mime.getBytes()) + "|" + b64(fileName.getBytes()));

        for (int index = 0; index < total; index++) {
            int start = index * CHUNK_BYTES;
            int end = Math.min(payload.length, start + CHUNK_BYTES);
            byte[] chunk = new byte[end - start];
            System.arraycopy(payload, start, chunk, 0, chunk.length);
            frames.add("AGS1D|" + session + "|" + index + "|" + total + "|" + b64(chunk));
        }

        return new PreparedTransfer(fileName, mime, original.length, compressed, frames);
    }

    static String[] split(String frame) {
        return frame.split("\\|", -1);
    }

    static byte[] fromB64(String value) {
        return Base64.decode(value, B64_FLAGS);
    }

    static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maxBytes) throw new IOException("File is larger than the 25 MB optical-transfer limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] original) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(original);
        }
        return output.toByteArray();
    }

    private static String randomSession() {
        byte[] bytes = new byte[5];
        new SecureRandom().nextBytes(bytes);
        return b64(bytes);
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, B64_FLAGS);
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        String result = "shared-file.bin";
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isBlank()) result = value;
                }
            }
        } catch (Exception ignored) {}
        return result.replaceAll("[\\r\\n]", "_");
    }
}
