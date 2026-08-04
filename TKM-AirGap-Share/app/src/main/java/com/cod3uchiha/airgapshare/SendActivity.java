package com.cod3uchiha.airgapshare;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendActivity extends AppCompatActivity {
    private static final int TURBO_DELAY_MS = 65;
    private static final int RELIABLE_DELAY_MS = 130;
    private static final int QR_SIZE = 700;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onFilePicked);

    private ImageView qrView;
    private TextView status;
    private TextView counter;
    private ProgressBar progress;
    private Button pauseButton;
    private Button speedButton;
    private TransferCodec.PreparedTransfer transfer;
    private Bitmap displayedBitmap;
    private int dataIndex;
    private int framesSinceHeader;
    private int frameDelayMs = TURBO_DELAY_MS;
    private boolean playing;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.page(this);
        TextView title = Ui.title(this, "Send — Turbo optical", 28);
        status = Ui.body(this,
                "Choose a file. Version 2 streams larger QR payloads directly from storage instead of loading the whole file into memory.");
        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        qrParams.topMargin = Ui.dp(this, 12);
        qrParams.bottomMargin = Ui.dp(this, 8);
        qrView.setLayoutParams(qrParams);

        counter = Ui.body(this, "No file selected");
        counter.setGravity(Gravity.CENTER);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        pauseButton = Ui.button(this, "Choose file");
        speedButton = Ui.button(this, "Speed: Turbo");
        Button brightness = Ui.button(this, "Open brightness settings");

        root.addView(title, Ui.fullWidth(this));
        root.addView(status, Ui.fullWidth(this));
        root.addView(qrView);
        root.addView(counter, Ui.fullWidth(this));
        root.addView(progress, Ui.fullWidth(this));
        root.addView(pauseButton, Ui.fullWidth(this));
        root.addView(speedButton, Ui.fullWidth(this));
        root.addView(brightness, Ui.fullWidth(this));
        setContentView(root);

        pauseButton.setOnClickListener(v -> {
            if (transfer == null) {
                picker.launch("*/*");
            } else {
                playing = !playing;
                pauseButton.setText(playing ? "Pause QR stream" : "Resume QR stream");
                if (playing) scheduleNext(0);
            }
        });

        speedButton.setOnClickListener(v -> {
            frameDelayMs = frameDelayMs == TURBO_DELAY_MS ? RELIABLE_DELAY_MS : TURBO_DELAY_MS;
            speedButton.setText(frameDelayMs == TURBO_DELAY_MS ? "Speed: Turbo" : "Speed: Reliable");
            if (transfer != null) updateStatus();
        });
        brightness.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        playing = false;
        closeTransfer();
        status.setText("Preparing a disk-backed transfer…");
        pauseButton.setEnabled(false);
        progress.setIndeterminate(true);

        worker.execute(() -> {
            try {
                TransferCodec.PreparedTransfer prepared = TransferCodec.prepare(this, uri);
                runOnUiThread(() -> begin(prepared));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progress.setIndeterminate(false);
                    status.setText("Could not prepare file: " + error.getMessage());
                    pauseButton.setEnabled(true);
                    pauseButton.setText("Choose another file");
                });
            }
        });
    }

    private void begin(TransferCodec.PreparedTransfer prepared) {
        transfer = prepared;
        dataIndex = 0;
        framesSinceHeader = TransferCodec.HEADER_INTERVAL;
        playing = true;
        progress.setIndeterminate(false);
        pauseButton.setEnabled(true);
        pauseButton.setText("Pause QR stream");
        updateStatus();
        scheduleNext(0);
    }

    private void updateStatus() {
        if (transfer == null) return;
        long idealBytesPerSecond = (transfer.chunkBytes * 1000L) / frameDelayMs;
        status.setText(transfer.fileName + " • " + humanSize(transfer.originalSize) +
                (transfer.compressed ? " • GZIP" : "") + "\n" +
                (frameDelayMs == TURBO_DELAY_MS ? "Turbo" : "Reliable") +
                " mode • up to " + humanRate(idealBytesPerSecond) + " optical payload");
    }

    private void scheduleNext(long delayMs) {
        handler.postDelayed(this::renderNext, delayMs);
    }

    private void renderNext() {
        if (!playing || transfer == null || rendering) return;
        rendering = true;

        TransferCodec.PreparedTransfer active = transfer;
        boolean isHeader = framesSinceHeader >= TransferCodec.HEADER_INTERVAL;
        int currentDataIndex = dataIndex;

        worker.execute(() -> {
            Bitmap bitmap = null;
            try {
                String frame = isHeader ? active.headerFrame : active.dataFrame(currentDataIndex);
                bitmap = qrBitmap(frame, QR_SIZE);
                Bitmap ready = bitmap;
                runOnUiThread(() -> {
                    if (isFinishing() || transfer != active) {
                        ready.recycle();
                        return;
                    }
                    Bitmap oldBitmap = displayedBitmap;
                    displayedBitmap = ready;
                    qrView.setImageBitmap(ready);
                    if (oldBitmap != null && oldBitmap != ready && !oldBitmap.isRecycled()) oldBitmap.recycle();
                    if (isHeader) {
                        framesSinceHeader = 0;
                        counter.setText("Sync frame • point the receiver at the entire QR square");
                    } else {
                        int shown = currentDataIndex + 1;
                        counter.setText("Chunk " + shown + " / " + active.totalChunks +
                                " • missing chunks are recovered on the next loop");
                        progress.setProgress((int) ((shown * 1000L) / active.totalChunks));
                        dataIndex = shown % active.totalChunks;
                        framesSinceHeader++;
                        if (dataIndex == 0) framesSinceHeader = TransferCodec.HEADER_INTERVAL;
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (transfer == active) status.setText("QR generation error: " + error.getMessage());
                });
            } finally {
                rendering = false;
                runOnUiThread(() -> {
                    if (playing && transfer == active) scheduleNext(frameDelayMs);
                });
            }
        });
    }

    private static Bitmap qrBitmap(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    private static String humanRate(long bytesPerSecond) {
        if (bytesPerSecond < 1024) return bytesPerSecond + " B/s";
        return String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0);
    }

    private void closeTransfer() {
        TransferCodec.PreparedTransfer old = transfer;
        transfer = null;
        if (old != null) old.close();
    }

    @Override
    protected void onDestroy() {
        playing = false;
        handler.removeCallbacksAndMessages(null);
        closeTransfer();
        if (displayedBitmap != null && !displayedBitmap.isRecycled()) displayedBitmap.recycle();
        worker.shutdownNow();
        super.onDestroy();
    }
}
