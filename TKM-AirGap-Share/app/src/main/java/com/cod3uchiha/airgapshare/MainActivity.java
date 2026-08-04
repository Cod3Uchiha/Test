package com.cod3uchiha.airgapshare;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.page(this);
        TextView eyebrow = Ui.body(this, "OFFLINE • PRIVATE • SERVERLESS");
        TextView title = Ui.title(this, "TKM AirGap Share 2", 32);
        TextView description = Ui.body(this,
                "Move files between Android phones using only the sender’s screen and the receiver’s camera. " +
                "No Bluetooth, Wi‑Fi, mobile data, account, cloud, or nearby-device access.");

        Button send = Ui.button(this, "Send a file");
        Button receive = Ui.button(this, "Receive a file");
        TextView note = Ui.body(this,
                "Turbo mode uses 2 KB QR payloads, rapid frame cycling, disk-backed transfers, automatic recovery of missed chunks, " +
                "and SHA-256 verification. Files up to 512 MB are supported, but optical transfer remains much slower than a cable or radio link.");

        root.addView(eyebrow, Ui.fullWidth(this));
        root.addView(Ui.spacer(this, 12));
        root.addView(title, Ui.fullWidth(this));
        root.addView(description, Ui.fullWidth(this));
        root.addView(Ui.spacer(this, 22));
        root.addView(send, Ui.fullWidth(this));
        root.addView(receive, Ui.fullWidth(this));
        root.addView(Ui.spacer(this, 22));
        root.addView(note, Ui.fullWidth(this));

        send.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        receive.setOnClickListener(v -> startActivity(new Intent(this, ReceiveActivity.class)));
        setContentView(root);
    }
}
