package com.kisslink.ui.home;

import static org.junit.Assert.assertEquals;

import com.kisslink.ui.home.HomeNfcDelegate.Readiness;

import org.junit.Test;

/**
 * 配對前置就緒判定的單元測試。
 *
 * <p>{@link HomeNfcDelegate} 本身與 Android framework 耦合（建構子吃 AppCompatActivity、
 * 直接呼叫 PermissionHelper 靜態 API），無法在純 JUnit 下實例化。故將優先序判定抽成純函式
 * {@link HomeNfcDelegate#evaluateReadiness} 後在此鎖住其優先序：權限 → 無線電 → 定位 → 熱點。
 *
 * <p>參數順序：{@code evaluateReadiness(hasPerms, anyRadioOff, locationRequiredButOff, hotspotOn)}。
 */
public class HomeNfcDelegateTest {

    @Test
    public void allReady_returnsReady() {
        assertEquals(Readiness.READY,
                HomeNfcDelegate.evaluateReadiness(true, false, false, false));
    }

    @Test
    public void missingPerms_takesPrecedenceOverEverything() {
        // 權限缺失時即使無線電未開、定位關閉、熱點開啟，也一律先回 NEED_PERMS。
        assertEquals(Readiness.NEED_PERMS,
                HomeNfcDelegate.evaluateReadiness(false, true, true, true));
        assertEquals(Readiness.NEED_PERMS,
                HomeNfcDelegate.evaluateReadiness(false, false, false, false));
    }

    @Test
    public void radioOff_takesPrecedenceOverLocationAndHotspot() {
        // 權限已給、無線電未開：即使定位關閉、熱點開著，先要求開無線電。
        assertEquals(Readiness.NEED_RADIO,
                HomeNfcDelegate.evaluateReadiness(true, true, true, true));
        assertEquals(Readiness.NEED_RADIO,
                HomeNfcDelegate.evaluateReadiness(true, true, false, false));
    }

    @Test
    public void locationOff_takesPrecedenceOverHotspot() {
        // 權限+無線電就緒，定位服務關閉：即使熱點也開著，先要求開定位。
        assertEquals(Readiness.NEED_LOCATION,
                HomeNfcDelegate.evaluateReadiness(true, false, true, true));
        assertEquals(Readiness.NEED_LOCATION,
                HomeNfcDelegate.evaluateReadiness(true, false, true, false));
    }

    @Test
    public void hotspotOn_blocksOnlyWhenPermsRadioAndLocationOk() {
        assertEquals(Readiness.HOTSPOT_ON,
                HomeNfcDelegate.evaluateReadiness(true, false, false, true));
    }
}
