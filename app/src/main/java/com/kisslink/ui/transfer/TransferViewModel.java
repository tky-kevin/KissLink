package com.kisslink.ui.transfer;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.kisslink.transfer.FileTransferService;
import com.kisslink.transfer.SendItem;
import com.kisslink.transfer.SessionState;

import java.util.List;

/**
 * TransferActivity 的 ViewModel，負責與 {@link FileTransferService} 通訊。
 *
 * <p>透過 Service Binding 取得單一 {@link SessionState} LiveData；即使 Activity 重建
 * （螢幕旋轉）也能持續觀察。橋接觀察者在解綁時會被移除，避免洩漏。
 */
public class TransferViewModel extends AndroidViewModel {

    private static final String TAG = "TransferViewModel";

    private FileTransferService.TransferBinder serviceBinder;
    private boolean bound = false;

    // 中繼 LiveData，確保未綁定前也能安全觀察
    private final MutableLiveData<SessionState> stateLd = new MutableLiveData<>();

    // 橋接：把 Service 的 SessionState 轉送到 stateLd（保留參照以便移除，避免 observeForever 洩漏）
    private LiveData<SessionState> boundSource;
    private final Observer<SessionState> bridge =
            s -> { if (s != null) stateLd.postValue(s); };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceBinder = (FileTransferService.TransferBinder) service;
            bound = true;
            Log.d(TAG, "Service bound");
            boundSource = serviceBinder.getSessionState();
            boundSource.observeForever(bridge);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            serviceBinder = null;
            detachSource();
            Log.d(TAG, "Service disconnected");
        }
    };

    public TransferViewModel(@NonNull Application application) {
        super(application);
    }

    // ── Service 綁定 ──────────────────────────────────────────

    public void bindService(Context ctx) {
        Intent intent = new Intent(ctx, FileTransferService.class);
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    public void unbindService(Context ctx) {
        if (bound) {
            detachSource();
            ctx.unbindService(connection);
            bound = false;
        }
    }

    private void detachSource() {
        if (boundSource != null) {
            boundSource.removeObserver(bridge);
            boundSource = null;
        }
    }

    // ── peer 傳送 API ─────────────────────────────────────────

    /** 連上後送出內容（任一端皆可、可多輪）。 */
    public void sendItems(List<SendItem> items) {
        if (serviceBinder != null) {
            serviceBinder.sendItems(items);
        } else {
            Log.w(TAG, "Service not bound, cannot send");
        }
    }

    /** 取消傳輸。 */
    public void cancel() {
        if (serviceBinder != null) serviceBinder.cancel();
    }

    // ── LiveData ──────────────────────────────────────────────

    public LiveData<SessionState> getState() { return stateLd; }

    @Override
    protected void onCleared() {
        super.onCleared();
        detachSource();
    }
}
