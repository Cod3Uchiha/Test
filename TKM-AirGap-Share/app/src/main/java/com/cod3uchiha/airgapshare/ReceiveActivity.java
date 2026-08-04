package com.cod3uchiha.airgapshare;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public class ReceiveActivity extends AppCompatActivity {
    private DecoratedBarcodeView scanner;
    private TextView status;
    private TextView details;
    private ProgressBar progress;
    private Session session;
    private boolean saving;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startScanner();
                else if (status != null) status.setText("Camera permission is required to receive QR frames.");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.page(this);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 16));
        TextView title = Ui.title(this, "Receive — Turbo optical", 26);
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        scanner = new DecoratedBarcodeView(this);
        scanner.setStatusText("");
        scanner.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scanParams.topMargin = Ui.dp(this, 10);
        scanner.setLayoutParams(scanParams);

        status = Ui.body(this, "Fill the camera view with the sender’s QR square.");
        status.setGravity(Gravity.CENTER);
        details = Ui.body(this, "Waiting for a version 2 transfer…");
        details.setGravity(Gravity.CENTER);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);

        root.addView(title, Ui.fullWidth(this));
        root.addView(scanner);
        root.addView(status, Ui.fullWidth(this));
        root.addView(details, Ui.fullWidth(this));
        root.addView(progress, Ui.fullWidth(this));
        setContentView(root);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanner();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startScanner() {
        scanner.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || result.getText() == null || saving) return;
                acceptFrame(result.getText());
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {}
        });
        scanner.resume();
    }

    private void acceptFrame(@NonNull String frame) {
        if (!frame.startsWith("AGS2")) return;
        try {
            String[] parts = TransferCodec.split(frame);
            if (parts.length < 2) return;
            if ("AGS2H".equals(parts[0]) && parts.length == 10) {
                acceptHeader(parts);
            } else if ("AGS2D".equals(parts[0]) && parts.length == 6) {
                acceptData(parts);
            }
        } catch (Exception ignored) {
            // Dense optical frames can be partially seen while changing; invalid frames are ignored.
        }
    }

    private void acceptHeader(String[] parts) throws Exception {
        String sid = parts[1];
        int total = Integer.parseInt(parts[2]);
        int chunkBytes = Integer.parseInt(parts[3]);
        long payloadSize = Long.parseLong(parts[4]);
        long originalSize = Long.parseLong(parts[5]);
        boolean compressed = "1".equals(parts[6]);
        String hash = parts[7];
        String mime = new String(TransferCodec.fromB64(parts[8]), StandardCharsets.UTF_8);
        String name = safeName(new String(TransferCodec.fromB64(parts[9]), StandardCharsets.UTF_8));

        if (sid.length() < 4 || total < 1 || total > 300000) return;
        if (chunkBytes < 256 || chunkBytes > 2200) return;
        if (payloadSize < 0 || payloadSize > TransferCodec.MAX_FILE_BYTES + 2L * 1024L * 1024L) return;
        if (originalSize < 0 || originalSize > TransferCodec.MAX_FILE_BYTES) return;
        int expectedTotal = (int) Math.max(1L, (payloadSize + chunkBytes - 1L) / chunkBytes);
        if (total != expectedTotal || hash.length() != 64) return;

        if (session != null && !session.id.equals(sid)) return;
        if (session == null) {
            session = new Session(sid, total, chunkBytes, payloadSize, originalSize,
                    compressed, hash, mime, name, getCacheDir());
        }
        update(session);
    }

    private void acceptData(String[] parts) throws Exception {
        if (session == null || !session.id.equals(parts[1])) return;
        int index = Integer.parseInt(parts[2]);
        int total = Integer.parseInt(parts[3]);
        if (total != session.total || index < 0 || index >= total) return;
        if (session.received.get(index)) return;

        byte[] chunk = TransferCodec.fromB64(parts[5]);
        long offset = (long) index * session.chunkBytes;
        int expectedLength = (int) Math.min(session.chunkBytes, Math.max(0L, session.payloadSize - offset));
        if (chunk.length != expectedLength) return;
        if (!TransferCodec.crc32Hex(chunk).equalsIgnoreCase(parts[4])) return;

        session.random.seek(offset);
        session.random.write(chunk);
        session.received.set(index);
        session.receivedCount++;
        update(session);
        maybeFinish(session);
    }

    private void update(Session active) {
        int total = Math.max(1, active.total);
        progress.setProgress((int) ((active.receivedCount * 1000L) / total));
        status.setText("Receiving " + active.name);

        long captured = Math.min(active.payloadSize, active.receivedCount * (long) active.chunkBytes);
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - active.startedAtMs);
        long bytesPerSecond = (captured * 1000L) / elapsedMs;
        details.setText(active.receivedCount + " / " + active.total + " chunks • " +
                humanSize(captured) + " captured • " + humanRate(bytesPerSecond));
    }

    private void maybeFinish(Session active) {
        if (saving || active.receivedCount != active.total) return;
        saving = true;
        scanner.pause();
        try { active.random.close(); } catch (Exception ignored) {}
        status.setText("Rebuilding and verifying the file…");
        worker.execute(() -> finishTransfer(active));
    }

    private void finishTransfer(Session active) {
        try {
            String location = saveVerified(active);
            if (!active.payloadFile.delete()) active.payloadFile.deleteOnExit();
            runOnUiThread(() -> {
                progress.setProgress(1000);
                status.setText("Transfer complete");
                details.setText(active.name + " saved to " + location +
                        "\nSHA-256 verified. The sender can stop the stream.");
                new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                        .startTone(ToneGenerator.TONE_PROP_ACK, 350);
            });
        } catch (Exception error) {
            if (!active.payloadFile.delete()) active.payloadFile.deleteOnExit();
            runOnUiThread(() -> {
                session = null;
                saving = false;
                progress.setProgress(0);
                status.setText("Transfer failed verification: " + error.getMessage());
                details.setText("Keep both phones steady and let the sender restart its QR loop.");
                scanner.resume();
            });
        }
    }

    private String saveVerified(Session active) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, active.name);
            values.put(MediaStore.Downloads.MIME_TYPE, active.mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirGapShare");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Android could not create the download.");
            try {
                try (OutputStream rawOutput = getContentResolver().openOutputStream(uri)) {
                    if (rawOutput == null) throw new IllegalStateException("Android could not open the download.");
                    writeAndVerify(active, rawOutput);
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
                return "Downloads/AirGapShare";
            } catch (Exception error) {
                getContentResolver().delete(uri, null, null);
                throw error;
            }
        }

        File baseFolder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (baseFolder == null) throw new IllegalStateException("External download storage is unavailable.");
        File folder = new File(baseFolder, "AirGapShare");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Could not create download folder.");
        File outputFile = uniqueFile(folder, active.name);
        try {
            try (OutputStream output = new FileOutputStream(outputFile)) {
                writeAndVerify(active, output);
            }
            return outputFile.getAbsolutePath();
        } catch (Exception error) {
            if (!outputFile.delete()) outputFile.deleteOnExit();
            throw error;
        }
    }

    private static void writeAndVerify(Session active, OutputStream rawOutput) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written = 0;
        try (InputStream fileInput = new BufferedInputStream(new FileInputStream(active.payloadFile), 64 * 1024);
             InputStream decodedInput = active.compressed ? new GZIPInputStream(fileInput, 64 * 1024) : fileInput;
             OutputStream output = new BufferedOutputStream(rawOutput, 64 * 1024)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = decodedInput.read(buffer)) != -1) {
                written += count;
                if (written > TransferCodec.MAX_FILE_BYTES) throw new IllegalStateException("Decoded file is too large.");
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            output.flush();
        }
        if (written != active.originalSize) throw new IllegalStateException("File size check failed.");
        if (!TransferCodec.hex(digest.digest()).equals(active.hash)) {
            throw new IllegalStateException("SHA-256 check failed.");
        }
    }

    private static File uniqueFile(File folder, String name) {
        File candidate = new File(folder, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return new File(folder, base + "-" + System.currentTimeMillis() + extension);
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[\\r\\n/\\\\:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "shared-file.bin" : safe;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static String humanRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!saving && scanner != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.resume();
        }
    }

    @Override
    protected void onPause() {
        if (scanner != null) scanner.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (scanner != null) scanner.pause();
        if (session != null) session.closeAndDelete();
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Session {
        final String id;
        final int total;
        final int chunkBytes;
        final long payloadSize;
        final long originalSize;
        final boolean compressed;
        final String hash;
        final String mime;
        final String name;
        final File payloadFile;
        final RandomAccessFile random;
        final BitSet received;
        final long startedAtMs = System.currentTimeMillis();
        int receivedCount;

        Session(String id, int total, int chunkBytes, long payloadSize, long originalSize,
                boolean compressed, String hash, String mime, String name, File cacheDir) throws Exception {
            this.id = id;
            this.total = total;
            this.chunkBytes = chunkBytes;
            this.payloadSize = payloadSize;
            this.originalSize = originalSize;
            this.compressed = compressed;
            this.hash = hash;
            this.mime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
            this.name = name;
            this.payloadFile = File.createTempFile("airgap-receive-", ".part", cacheDir);
            this.random = new RandomAccessFile(payloadFile, "rw");
            this.random.setLength(payloadSize);
            this.received = new BitSet(total);
        }

        void closeAndDelete() {
            try { random.close(); } catch (Exception ignored) {}
            if (!payloadFile.delete()) payloadFile.deleteOnExit();
        }
    }
}
