package com.nfctransfer.app.transfer;

public interface TransferCallback {
    void onProgressUpdate(String fileName, int percent);
    void onFileComplete(String fileName, String filePath, boolean isSend);
    void onAllComplete(int successCount, int failCount);
    void onError(String fileName, Exception e);
}
