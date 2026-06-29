package com.kisslink.pairing.ble;

import android.os.ParcelUuid;
import java.util.UUID;

/**
 * BLE 憑證側通道(方案 B2)的共用識別碼。
 *
 * <h3>GATT 服務</h3>
 *
 * <ul>
 *   <li>{@link #CHAR_PEER_TOKEN}:central(reader)把<b>自己的</b> token 寫給 peripheral, 讓雙方都湊齊兩份 token 做
 *       GO 選舉。
 *   <li>{@link #CHAR_CREDENTIAL}:由當選 GO 的一方把 {@code GroupCredential} 交給對方 (GO=peripheral →
 *       notify;GO=central → write)。
 * </ul>
 *
 * <p>peripheral(tag)在廣告的 manufacturer data 放入 8-byte nonce; central(reader)以對方 nonce 作
 * ScanFilter,精準鎖定同一場配對,避免多台混淆。
 */
public final class BleConstants {

    private BleConstants() {}

    public static final UUID SERVICE_UUID = UUID.fromString("f04b4953-0001-4b9d-9e1a-4b6973734c6b");
    public static final UUID CHAR_PEER_TOKEN =
            UUID.fromString("f04b4953-0002-4b9d-9e1a-4b6973734c6b");
    public static final UUID CHAR_CREDENTIAL =
            UUID.fromString("f04b4953-0003-4b9d-9e1a-4b6973734c6b");

    /** Client Characteristic Configuration Descriptor(標準 0x2902)。 */
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final ParcelUuid SERVICE_PARCEL = new ParcelUuid(SERVICE_UUID);

    /** 廣告用的廠商 ID:0xFFFF 為 BT SIG 保留給測試/內部用途。 */
    public static final int MANUFACTURER_ID = 0xFFFF;
}
