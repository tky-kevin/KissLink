package com.kisslink.model;

/**
 * Wi-Fi Direct Group 的連線憑證。
 *
 * <p>GO 端建立群組後產生；之後在 M3 由 HCEService 序列化並透過 NFC 傳給 Client 端。 Client 端收到後呼叫 {@link
 * com.kisslink.wifidirect.WifiDirectManager#connectAsClient}。
 */
public class GroupCredential {

    private final String ssid;
    private final String passphrase;
    private final String goIpAddress;
    private final int transferPort;

    public GroupCredential(String ssid, String passphrase, String goIpAddress, int transferPort) {
        this.ssid = ssid;
        this.passphrase = passphrase;
        this.goIpAddress = goIpAddress;
        this.transferPort = transferPort;
    }

    public String getSsid() {
        return ssid;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public String getGoIpAddress() {
        return goIpAddress;
    }

    public int getTransferPort() {
        return transferPort;
    }

    @Override
    public String toString() {
        return "GroupCredential{ssid='" + ssid + "', ip=" + goIpAddress + ":" + transferPort + "}";
    }
}
