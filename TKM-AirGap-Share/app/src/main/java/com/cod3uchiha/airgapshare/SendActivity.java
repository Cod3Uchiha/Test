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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> picker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::onFilePicked);

    private ImageView qrView;
    private TextView status;
    private TextView counter;
    private ProgressBar progress;
    private Button pauseButton;
    private TransferCodec.PreparedTransfer transfer;
    private int frameIndex;
    private boolean playing;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = Ui.page(this);
        TextView title = Ui.title(this, "Send", 28);
        status = Ui.body(this, "Choose a file. The app will turn it into a repeating stream of QR frames.");
        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        qrParams.topMargin = Ui.dp(this, 16);
        qrParams.bottomMargin = Ui.dp(this, 10);
        qrView.setLayoutParams(qrParams);

        counter = Ui.body(this, "No file selected");
        counter.setGravity(Gravity.CENTER);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        pauseButton = Ui.button(this, "Choose file");
        Button brightness = Ui.button(this, "Open brightness settings");

        root.addView(title, Ui.fullWidth(this));
        root.addView(status, Ui.fullWidth(this));
        root.addView(qrView);
        root.addView(counter, Ui.fullWidth(this));
        root.addView(progress, Ui.fullWidth(this));
        root.addView(pauseButton, Ui.fullWidth(this));
        root.addView(brightness, Ui.fullWidth(this));
        setContentView(root);

        pauseButton.setOnClickListener(v -> {
            if (transfer == null) picker.launch("*/*");
            else {
                playing = !playing;
                pauseButton.setText(playing ? "Pause QR stream" : "Resume QR stream");
                if (playing) scheduleNext(0);
            }
        });
        brightness.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        playing = false;
        transfer = null;
        status.setText("Preparing file…");
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
        frameIndex = 0;
        playing = true;
        progress.setIndeterminate(false);
        pauseButton.setEnabled(true);
        pauseButton.setText("Pause QR stream");
        status.setText(prepared.fileName + " • " + humanSize(prepared.originalSize) +
                (prepared.compressed ? " • compressed" : ""));
        scheduleNext(0);
    }

    private void scheduleNext(long delayMs) {
        handler.postDelayed(this::renderNext, delayMs);
    }

    private void renderNext() {
        if (!playing || transfer == null || rendering) return;
        rendering = true;
        int current = frameIndex;
        String frame = transfer.frames.get(current);
        worker.execute(() -> {
            try {
                Bitmap bitmap = qrBitmap(frame, 920);
                runOnUiThread(() -> {
                    if (isFinishing() || transfer == null) return;
                    qrView.setImageBitmap(bitmap);
                    counter.setText("Frame " + (current + 1) + " / " + transfer.frames.size() + " • keep both phones steady");
                    progress.setProgress((int) (((current + 1L) * 1000L) / transfer.frames.size()));
                    frameIndex = (current + 1) % transfer.frames.size();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("QR generation error: " + error.getMessage()));
            } finally {
                rendering = false;
                runOnUiThread(() -> {
                    if (playing) scheduleNext(175);
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
            for (int x = 0; x < size; x++) pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    @Override
    protected void onDestroy() {
        playing = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
