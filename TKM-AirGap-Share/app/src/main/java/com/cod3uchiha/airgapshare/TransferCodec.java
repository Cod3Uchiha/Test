package com.cod3uchiha.airgapshare;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

final class TransferCodec {
    static final long MAX_FILE_BYTES = 512L * 1024L * 1024L;
    static final int CHUNK_BYTES = 2048;
    static final int HEADER_INTERVAL = 28;
    private static final int B64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    private TransferCodec() {}

    static final class PreparedTransfer implements Closeable {
        final String fileName;
        final String mimeType;
        final long originalSize;
        final long payloadSize;
        final boolean compressed;
        final int totalChunks;
        final int chunkBytes;
        final String headerFrame;

        private final String session;
        private final File payloadFile;
        private final RandomAccessFile random;
        private boolean closed;

        PreparedTransfer(String fileName, String mimeType, long originalSize, long payloadSize,
                         boolean compressed, int totalChunks, int chunkBytes, String session,
                         String headerFrame, File payloadFile) throws IOException {
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.originalSize = originalSize;
            this.payloadSize = payloadSize;
            this.compressed = compressed;
            this.totalChunks = totalChunks;
            this.chunkBytes = chunkBytes;
            this.session = session;
            this.headerFrame = headerFrame;
            this.payloadFile = payloadFile;
            this.random = new RandomAccessFile(payloadFile, "r");
        }

        synchronized String dataFrame(int index) throws IOException {
            if (closed) throw new IOException("Transfer has been closed.");
            if (index < 0 || index >= totalChunks) throw new IOException("Invalid chunk index.");
            long offset = (long) index * chunkBytes;
            int length = (int) Math.min(chunkBytes, Math.max(0L, payloadSize - offset));
            byte[] chunk = new byte[length];
            random.seek(offset);
            random.readFully(chunk);
            return "AGS2D|" + session + "|" + index + "|" + totalChunks + "|" +
                    crc32Hex(chunk) + "|" + b64(chunk);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try { random.close(); } catch (Exception ignored) {}
            if (!payloadFile.delete()) payloadFile.deleteOnExit();
        }
    }

    static PreparedTransfer prepare(Context context, Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String fileName = displayName(resolver, uri);
        String mime = resolver.getType(uri);
        if (mime == null || mime.isBlank()) mime = "application/octet-stream";

        long declaredSize = declaredSize(resolver, uri);
        if (declaredSize > MAX_FILE_BYTES) {
            throw new IOException("File is larger than the 512 MB optical-transfer limit.");
        }

        boolean compressed = shouldCompress(fileName, mime);
        File payloadFile = File.createTempFile("airgap-send-", compressed ? ".gz" : ".bin", context.getCacheDir());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long originalSize = 0;

        try (InputStream rawInput = resolver.openInputStream(uri)) {
            if (rawInput == null) throw new IOException("The selected file could not be opened.");
            try (BufferedInputStream input = new BufferedInputStream(rawInput, 64 * 1024);
                 FileOutputStream fileOutput = new FileOutputStream(payloadFile);
                 BufferedOutputStream bufferedOutput = new BufferedOutputStream(fileOutput, 64 * 1024);
                 OutputStream output = compressed ? new GZIPOutputStream(bufferedOutput, 64 * 1024) : bufferedOutput) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    originalSize += count;
                    if (originalSize > MAX_FILE_BYTES) {
                        throw new IOException("File is larger than the 512 MB optical-transfer limit.");
                    }
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
        } catch (Exception error) {
            if (!payloadFile.delete()) payloadFile.deleteOnExit();
            throw error;
        }

        long payloadSize = payloadFile.length();
        int total = (int) Math.max(1L, (payloadSize + CHUNK_BYTES - 1L) / CHUNK_BYTES);
        String session = randomSession();
        String hash = hex(digest.digest());
        String header = "AGS2H|" + session + "|" + total + "|" + CHUNK_BYTES + "|" +
                payloadSize + "|" + originalSize + "|" + (compressed ? "1" : "0") + "|" +
                hash + "|" + b64(mime.getBytes(StandardCharsets.UTF_8)) + "|" +
                b64(fileName.getBytes(StandardCharsets.UTF_8));

        return new PreparedTransfer(fileName, mime, originalSize, payloadSize, compressed,
                total, CHUNK_BYTES, session, header, payloadFile);
    }

    static String[] split(String frame) {
        return frame.split("\\|", -1);
    }

    static byte[] fromB64(String value) {
        return Base64.decode(value, B64_FLAGS);
    }

    static String crc32Hex(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return String.format(Locale.US, "%08x", crc.getValue());
    }

    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
        return out.toString();
    }

    private static boolean shouldCompress(String fileName, String mime) {
        String lowerName = fileName.toLowerCase(Locale.US);
        String lowerMime = mime.toLowerCase(Locale.US);
        if (lowerMime.startsWith("text/")) return true;
        if (lowerMime.contains("json") || lowerMime.contains("xml") || lowerMime.contains("javascript") ||
                lowerMime.contains("csv") || lowerMime.contains("yaml") || lowerMime.contains("svg")) return true;
        return lowerName.endsWith(".txt") || lowerName.endsWith(".json") || lowerName.endsWith(".xml") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".html") || lowerName.endsWith(".css") ||
                lowerName.endsWith(".js") || lowerName.endsWith(".ts") || lowerName.endsWith(".md") ||
                lowerName.endsWith(".log") || lowerName.endsWith(".sql") || lowerName.endsWith(".yaml") ||
                lowerName.endsWith(".yml");
    }

    private static long declaredSize(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private static String randomSession() {
        byte[] bytes = new byte[6];
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
        String safe = result.replaceAll("[\\r\\n]", "_");
        return safe.length() > 180 ? safe.substring(0, 180) : safe;
    }
}
