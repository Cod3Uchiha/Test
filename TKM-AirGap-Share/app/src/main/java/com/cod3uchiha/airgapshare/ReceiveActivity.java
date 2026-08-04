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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReceiveActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Session> sessions = new HashMap<>();
    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startScanner();
                else status.setText("Camera permission is required to receive QR frames.");
            });

    private DecoratedBarcodeView scanner;
    private TextView status;
    private TextView details;
    private ProgressBar progress;
    private String activeSession;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.page(this);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 16));
        TextView title = Ui.title(this, "Receive", 26);
        title.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        scanner = new DecoratedBarcodeView(this);
        scanner.setStatusText("");
        scanner.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scanParams.topMargin = Ui.dp(this, 12);
        scanner.setLayoutParams(scanParams);

        status = Ui.body(this, "Point this camera at the sender’s QR square.");
        status.setGravity(Gravity.CENTER);
        details = Ui.body(this, "Waiting for a transfer…");
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
        if (!frame.startsWith("AGS1")) return;
        try {
            String[] parts = TransferCodec.split(frame);
            if (parts.length < 2) return;
            if ("AGS1H".equals(parts[0]) && parts.length == 8) {
                String sid = parts[1];
                if (!selectSession(sid)) return;
                Session session = sessions.computeIfAbsent(sid, Session::new);
                session.total = Integer.parseInt(parts[2]);
                session.originalSize = Long.parseLong(parts[3]);
                session.compressed = "1".equals(parts[4]);
                session.hash = parts[5];
                session.mime = new String(TransferCodec.fromB64(parts[6]), StandardCharsets.UTF_8);
                session.name = safeName(new String(TransferCodec.fromB64(parts[7]), StandardCharsets.UTF_8));
                session.hasHeader = true;
                update(session);
                maybeFinish(session);
            } else if ("AGS1D".equals(parts[0]) && parts.length == 5) {
                String sid = parts[1];
                if (!selectSession(sid)) return;
                int index = Integer.parseInt(parts[2]);
                int total = Integer.parseInt(parts[3]);
                if (total < 1 || total > 50000 || index < 0 || index >= total) return;
                Session session = sessions.computeIfAbsent(sid, Session::new);
                if (session.total == 0) session.total = total;
                if (session.total != total) return;
                if (!session.chunks.containsKey(index)) session.chunks.put(index, TransferCodec.fromB64(parts[4]));
                update(session);
                maybeFinish(session);
            }
        } catch (Exception ignored) {
            // A partial or unrelated QR frame is simply ignored.
        }
    }

    private boolean selectSession(String sid) {
        if (activeSession == null) activeSession = sid;
        return activeSession.equals(sid);
    }

    private void update(Session session) {
        int total = Math.max(1, session.total);
        int have = session.chunks.size();
        progress.setProgress((int) ((have * 1000L) / total));
        status.setText(session.hasHeader ? "Receiving " + session.name : "Transfer detected — waiting for header…");
        details.setText(have + " / " + total + " data frames captured");
    }

    private void maybeFinish(Session session) {
        if (saving || !session.hasHeader || session.total <= 0 || session.chunks.size() != session.total) return;
        saving = true;
        scanner.pause();
        status.setText("Rebuilding and verifying file…");
        worker.execute(() -> finishTransfer(session));
    }

    private void finishTransfer(Session session) {
        try {
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (int i = 0; i < session.total; i++) {
                byte[] chunk = session.chunks.get(i);
                if (chunk == null) throw new IllegalStateException("A frame is missing.");
                packed.write(chunk);
            }
            byte[] bytes = packed.toByteArray();
            if (session.compressed) bytes = TransferCodec.gunzip(bytes);
            if (bytes.length != session.originalSize) throw new IllegalStateException("File size verification failed.");
            if (!TransferCodec.sha256(bytes).equals(session.hash)) throw new IllegalStateException("SHA-256 verification failed.");
            String location = saveFile(session.name, session.mime, bytes);
            runOnUiThread(() -> {
                progress.setProgress(1000);
                status.setText("Transfer complete");
                details.setText(session.name + " saved to " + location + "\nThe sender can stop the QR stream.");
                new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                        .startTone(ToneGenerator.TONE_PROP_ACK, 350);
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                saving = false;
                status.setText("Transfer failed verification: " + error.getMessage());
                details.setText("Restart Receive and try again with both phones steady.");
            });
        }
    }

    private String saveFile(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AirGapShare");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            android.net.Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Android could not create the download.");
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IllegalStateException("Android could not open the download.");
                output.write(bytes);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            return "Downloads/AirGapShare";
        }

        File folder = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AirGapShare");
        if (!folder.exists() && !folder.mkdirs()) throw new IllegalStateException("Could not create download folder.");
        File file = new File(folder, name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return file.getAbsolutePath();
    }

    private static String safeName(String value) {
        String safe = value.replaceAll("[\\r\\n/\\\\:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "shared-file.bin" : safe;
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
        worker.shutdownNow();
        super.onDestroy();
    }

    private static final class Session {
        final String id;
        final Map<Integer, byte[]> chunks = new HashMap<>();
        int total;
        long originalSize;
        boolean compressed;
        boolean hasHeader;
        String hash;
        String mime = "application/octet-stream";
        String name = "shared-file.bin";

        Session(String id) { this.id = id; }
    }
}
