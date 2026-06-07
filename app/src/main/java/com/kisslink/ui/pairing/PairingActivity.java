package com.kisslink.ui.pairing;

import android.animation.ObjectAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.kisslink.R;
import com.kisslink.model.BusinessCard;
import com.kisslink.model.GroupCredential;
import com.kisslink.nfc.NFCManager;
import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SessionState;
import com.kisslink.ui.ThemeManager;
import com.kisslink.ui.card.CardDisplayActivity;
import com.kisslink.ui.transfer.TransferActivity;

import java.util.ArrayList;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class PairingActivity extends AppCompatActivity {

    public enum Role { SENDER, RECEIVER }

    private static final String EXTRA_ROLE = "role";
    private static final String EXTRA_URIS = "uris";

    private TextView tvRole, tvStatus, tvHint;
    private View     btnCancel;
    private View     ring1, ring2, ring3, ring4;
    private View     ring1Inner;
    private ImageView ivNfcIcon;

    private ObjectAnimator animRing1, animRing2, animRing3, animRing4;

    private NFCManager nfcManager;
    private Role       role;

    private FileTransferService.TransferBinder binder;
    private boolean bound         = false;
    private boolean navigating    = false;
    private boolean filesEnqueued = false;
    @Nullable private GroupCredential pendingCredential;

    public static Intent newIntent(Context ctx, Role role, @Nullable ArrayList<Uri> uris) {
        Intent i = new Intent(ctx, PairingActivity.class);
        i.putExtra(EXTRA_ROLE, role.name());
        if (uris != null && !uris.isEmpty()) i.putParcelableArrayListExtra(EXTRA_URIS, uris);
        return i;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (FileTransferService.TransferBinder) service;
            bound  = true;

            binder.getSessionState().observe(PairingActivity.this, st -> {
                updateStatusText(st);
                if (st.isTransferStartedOrConnected()) goToTransfer();
                else if (st.isError() && st.error != null) showError(st.error);
            });

            if (role == Role.RECEIVER && pendingCredential != null) {
                binder.submitReceiverCredential(pendingCredential);
                pendingCredential = null;
            }

            if (role == Role.SENDER && !filesEnqueued) {
                ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
                if (uris != null && !uris.isEmpty()) {
                    binder.enqueueFiles(uris);
                    filesEnqueued = true;
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound  = false;
            binder = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);
        bindViews();

        role       = Role.valueOf(getIntent().getStringExtra(EXTRA_ROLE));
        nfcManager = new NFCManager(this);
        setupUiForRole(role);

        btnCancel.setOnClickListener(v -> { stopSessionService(); finish(); });

        Intent svc = (role == Role.SENDER)
                ? FileTransferService.senderIntent(this)
                : FileTransferService.receiverIntent(this);
        startForegroundService(svc);
        bindService(svc, connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPulseAnimation();
        if (role == Role.RECEIVER) {
            nfcManager.enableReaderMode(
                    this,
                    cred -> runOnUiThread(() -> onCredential(cred)),
                    card -> runOnUiThread(() -> onCardReceived(card)),
                    err  -> runOnUiThread(() -> showError(err)));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelPulseAnimation();
        if (role == Role.RECEIVER) nfcManager.disableReaderMode(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bound) { unbindService(connection); bound = false; }
        if (isFinishing() && !navigating) stopSessionService();
    }

    private void onCredential(GroupCredential cred) {
        if (binder != null) binder.submitReceiverCredential(cred);
        else pendingCredential = cred;
    }

    private void onCardReceived(BusinessCard card) {
        stopSessionService();
        navigating = true;
        startActivity(CardDisplayActivity.newIntent(this, card));
        finish();
    }

    private void goToTransfer() {
        if (navigating) return;
        navigating = true;
        ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
        startActivity(TransferActivity.newIntent(
                this,
                role == Role.SENDER ? TransferActivity.ROLE_SENDER : TransferActivity.ROLE_RECEIVER,
                uris));
        finish();
    }

    private void stopSessionService() {
        stopService(new Intent(this, FileTransferService.class));
    }

    private void bindViews() {
        tvRole    = findViewById(R.id.tvRole);
        tvStatus  = findViewById(R.id.tvStatus);
        tvHint    = findViewById(R.id.tvHint);
        btnCancel = findViewById(R.id.btnCancel);
        ring1     = findViewById(R.id.ring1);
        ring2     = findViewById(R.id.ring2);
        ring3     = findViewById(R.id.ring3);
        ring4     = findViewById(R.id.ring4);
        ring1Inner = findViewById(R.id.ring1Inner);
        ivNfcIcon  = findViewById(R.id.ivNfcIcon);
    }

    private void startPulseAnimation() {
        setRingTint();

        animRing1 = ObjectAnimator.ofFloat(ring1, "alpha", 0.8f, 0.2f);
        animRing1.setDuration(2000);
        animRing1.setRepeatMode(ObjectAnimator.REVERSE);
        animRing1.setRepeatCount(ObjectAnimator.INFINITE);
        animRing1.setStartDelay(0);

        animRing2 = ObjectAnimator.ofFloat(ring2, "alpha", 0.8f, 0.2f);
        animRing2.setDuration(2000);
        animRing2.setRepeatMode(ObjectAnimator.REVERSE);
        animRing2.setRepeatCount(ObjectAnimator.INFINITE);
        animRing2.setStartDelay(400);

        animRing3 = ObjectAnimator.ofFloat(ring3, "alpha", 0.8f, 0.2f);
        animRing3.setDuration(2000);
        animRing3.setRepeatMode(ObjectAnimator.REVERSE);
        animRing3.setRepeatCount(ObjectAnimator.INFINITE);
        animRing3.setStartDelay(800);

        animRing4 = ObjectAnimator.ofFloat(ring4, "alpha", 0.8f, 0.2f);
        animRing4.setDuration(2000);
        animRing4.setRepeatMode(ObjectAnimator.REVERSE);
        animRing4.setRepeatCount(ObjectAnimator.INFINITE);
        animRing4.setStartDelay(1200);

        animRing1.start();
        animRing2.start();
        animRing3.start();
        animRing4.start();
    }

    private void setRingTint() {
        int color = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorPrimary,
                0xFF2EAADC);
        tintViewBg(ring2, color);
        tintViewBg(ring3, color);
        tintViewBg(ring4, color);
        tintViewBg(ring1Inner, color);
        if (ivNfcIcon != null) {
            ivNfcIcon.setColorFilter(color);
        }
    }

    private static void tintViewBg(View v, int color) {
        if (v == null || v.getBackground() == null) return;
        Drawable bg = DrawableCompat.wrap(v.getBackground()).mutate();
        DrawableCompat.setTint(bg, color);
        v.setBackground(bg);
    }

    private void cancelPulseAnimation() {
        if (animRing1 != null) animRing1.cancel();
        if (animRing2 != null) animRing2.cancel();
        if (animRing3 != null) animRing3.cancel();
        if (animRing4 != null) animRing4.cancel();
    }

    private void setupUiForRole(Role role) {
        if (role == Role.SENDER) {
            tvRole.setText("傳送方");
            tvStatus.setText("正在建立連線…");
            ArrayList<Uri> uris = getIntent().getParcelableArrayListExtra(EXTRA_URIS);
            tvHint.setText(uris != null && !uris.isEmpty()
                    ? "已選 " + uris.size() + " 個檔案，等待碰觸後立即傳送"
                    : "等待對方靠近並碰觸");
        } else {
            tvRole.setText("接收方");
            tvStatus.setText("請靠近傳送方手機");
            tvHint.setText("輕碰兩台手機 NFC 感應區");
        }
    }

    private void updateStatusText(SessionState st) {
        switch (st.phase) {
            case CREATING_GROUP: tvStatus.setText("建立 Wi-Fi Direct 群組…"); break;
            case HOSTING:        tvStatus.setText("等待對方碰觸 NFC…");        break;
            case CONNECTING:     tvStatus.setText("靜默連線中，請稍候…");       break;
            case CONNECTED:      tvStatus.setText("連線成功！");                break;
            case ERROR:          tvStatus.setText("連線失敗，請重試");           break;
            default: break;
        }
    }

    private void showError(String msg) {
        Snackbar.make(btnCancel, msg, Snackbar.LENGTH_LONG).show();
    }
}
