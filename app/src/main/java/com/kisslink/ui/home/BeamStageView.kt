package com.kisslink.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 中央視覺模組（Compose）。
 *
 * 設計：
 *  - **NFC 波紋常駐**：像素方格組成的環往外擴散，所有狀態都顯示（待機呼吸感 = 與未連線相同的波紋，只是中央換成頭像）。
 *  - **中央**：未連線=NFC 節點；已連線=對方頭像（較大）。
 *  - **傳輸中**：頭像外圈環形進度條（12 點鐘起跑、半徑大於頭像 → 不被遮擋）；速度由外層 headline 顯示，頭像上不放數字。
 *  - **完成**：頭像處先播打勾動畫（此時隱藏頭像），再淡回頭像。
 *  - **名片飛出**：[playCardFly] genie 縮入頭像。
 */
class BeamStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AbstractComposeView(context, attrs) {

    companion object {
        const val READY = 0
        const val CONNECTING = 1
        const val CONNECTED = 2
        const val TRANSFERRING = 3
        const val DONE = 4
        const val ERROR = 5
        const val SEND = 0
        const val RECEIVE = 1
    }

    private val phaseState = mutableStateOf(READY)
    private val directionState = mutableStateOf(SEND)
    private val progressState = mutableStateOf(0f)
    private val peerAvatarState = mutableStateOf<Bitmap?>(null)
    private val selfAvatarState = mutableStateOf<Bitmap?>(null)
    private val peerNameState = mutableStateOf<String?>(null)
    private val selfNameState = mutableStateOf<String?>(null)
    private val cardFlyTrigger = mutableStateOf(0)

    fun setPhase(p: Int) { phaseState.value = p }
    fun setDirection(d: Int) { directionState.value = d }
    fun setProgress(p: Float) { progressState.value = p.coerceIn(0f, 1f) }
    fun setPeerAvatar(b: Bitmap?) { peerAvatarState.value = b }
    fun setSelfAvatar(b: Bitmap?) { selfAvatarState.value = b }
    fun setPeerIdentity(name: String?) { peerNameState.value = monogram(name) }
    fun setSelfIdentity(name: String?) { selfNameState.value = monogram(name) }

    /** 觸發名片飛向頭像的 genie 動畫。 */
    fun playCardFly() { cardFlyTrigger.value++ }

    private fun monogram(name: String?): String? {
        val s = name?.trim().orEmpty()
        return if (s.isEmpty()) null else s.substring(0, 1).uppercase()
    }

    @Composable
    override fun Content() {
        BeamStage(
            phase = phaseState.value,
            rawProgress = progressState.value,
            peerAvatar = peerAvatarState.value,
            peerMono = peerNameState.value,
            cardFlyTrigger = cardFlyTrigger.value
        )
    }
}

private val ACCENT = Color(0xFF1A73E8)

@Composable
private fun BeamStage(
    phase: Int,
    rawProgress: Float,
    peerAvatar: Bitmap?,
    peerMono: String?,
    cardFlyTrigger: Int
) {
    // 動態顏色：跟隨深/淺模式（不依賴 Material3，直接讀系統主題）
    val dark  = isSystemInDarkTheme()
    val INK   = if (dark) Color(0xFFEDE9E3) else Color(0xFF23201B)
    val TRACK = if (dark) Color(0xFF38342F) else Color(0xFFDCD8CF)
    val PANEL = if (dark) Color(0xFF242220) else Color(0xFFF4F2EC)

    val connected = phase == BeamStageView.CONNECTED ||
        phase == BeamStageView.TRANSFERRING || phase == BeamStageView.DONE
    val transferring = phase == BeamStageView.TRANSFERRING
    val done = phase == BeamStageView.DONE

    // 常駐 NFC 漣漪
    val infinite = rememberInfiniteTransition(label = "beam")
    val ripple by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "ripple"
    )

    // #4 平滑進度
    // 線性補間：配合外層已單調化的進度，視覺速度穩定（不忽快忽慢、不回退）
    val animProgress by animateFloatAsState(
        targetValue = when {
            done -> 1f
            transferring -> rawProgress
            else -> 0f
        },
        animationSpec = tween(400, easing = LinearEasing),
        label = "progress"
    )

    // 完成打勾 / 頭像切換動畫
    val avatarAlpha = remember { Animatable(0f) }
    val checkAlpha = remember { Animatable(0f) }
    val checkDraw = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            BeamStageView.DONE -> {
                avatarAlpha.animateTo(0f, tween(140))      // 隱藏頭像
                checkDraw.snapTo(0f); checkAlpha.snapTo(1f)
                checkDraw.animateTo(1f, tween(440, easing = FastOutSlowInEasing)) // 畫勾
                delay(560)
                checkAlpha.animateTo(0f, tween(220))       // 勾淡出
                avatarAlpha.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) // 切回頭像
            }
            BeamStageView.CONNECTED, BeamStageView.TRANSFERRING -> {
                checkAlpha.snapTo(0f); checkDraw.snapTo(0f)
                if (avatarAlpha.value < 1f) avatarAlpha.animateTo(1f, tween(340))
            }
            else -> {
                avatarAlpha.snapTo(0f); checkAlpha.snapTo(0f); checkDraw.snapTo(0f)
            }
        }
    }

    // #14 名片飛出
    val fly = remember { Animatable(0f) }
    LaunchedEffect(cardFlyTrigger) {
        if (cardFlyTrigger > 0) {
            fly.snapTo(0f)
            fly.animateTo(1f, animationSpec = tween(640, easing = FastOutSlowInEasing))
        }
    }

    val density = LocalDensity.current
    val avatarR = 56.dp        // 比之前(48dp)更大
    val ringGap = 16.dp

    // 用整個可用空間置中（不再用固定 240dp，避免畫面較矮時上/下緣被裁切）
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {

            // ── 環/波紋層 ──
            Canvas(modifier = Modifier.matchParentSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val avatarPx = avatarR.toPx()
                val ringR = avatarPx + ringGap.toPx()
                val strokeW = 5.dp.toPx()

                // 常駐 NFC 漣漪（像素網格）。傳輸時從進度環往外擴散；其餘狀態從中心點往外（避免突現）。
                val rippleStart = if (transferring || done) ringR + 3.dp.toPx() else 0f
                val rippleMax = (if (transferring || done) ringR else avatarPx) + 46.dp.toPx()
                val px = 4.dp.toPx()
                for (i in 0..2) {
                    val t = (ripple + i / 3f) % 1f
                    val r = rippleStart + (rippleMax - rippleStart) * t
                    // 淡入（t < 0.15）+ 淡出（t > 0.5），避免波紋在起點突然出現
                    val fadeIn  = (t / 0.15f).coerceIn(0f, 1f)
                    val fadeOut = (1f - t).coerceIn(0f, 1f)
                    val a = fadeIn * fadeOut * 0.55f
                    if (a <= 0f) continue
                    val ringW = px * 2.5f
                    val rIn = (r - ringW / 2f).coerceAtLeast(0f)
                    val rOut = r + ringW / 2f
                    val col = ACCENT.copy(alpha = a)
                    val x0 = ((c.x - rOut) / px).roundToInt().coerceAtLeast(0)
                    val x1 = ((c.x + rOut) / px).roundToInt().coerceAtMost((size.width / px).roundToInt())
                    val y0 = ((c.y - rOut) / px).roundToInt().coerceAtLeast(0)
                    val y1 = ((c.y + rOut) / px).roundToInt().coerceAtMost((size.height / px).roundToInt())
                    for (gx in x0..x1) {
                        for (gy in y0..y1) {
                            val cx = gx * px + px / 2f
                            val cy = gy * px + px / 2f
                            val d = hypot((cx - c.x).toDouble(), (cy - c.y).toDouble()).toFloat()
                            if (d in rIn..rOut) {
                                drawRect(col, topLeft = Offset(gx * px, gy * px), size = Size(px, px))
                            }
                        }
                    }
                }

                // 傳輸/完成：頭像外圈進度環（像素格）
                if (transferring || done) {
                    drawPixelRing(c, ringR, px, TRACK, 1f)           // 底環
                    drawPixelRing(c, ringR, px, ACCENT, animProgress) // 進度環
                }
            }

            // ── 中央節點（像素方格）──
            if (!connected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(ACCENT)
                )
            } else {
                Box(modifier = Modifier.size(avatarR * 2), contentAlignment = Alignment.Center) {
                    // 頭像（依 avatarAlpha 淡入/縮放）
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = avatarAlpha.value
                                val sc = 0.86f + 0.14f * avatarAlpha.value
                                scaleX = sc; scaleY = sc
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = peerAvatar
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                                    .border(1.5.dp, TRACK, CircleShape)
                            )
                        } else {
                            // 對方沒有自訂頭像 → 顯示預設頭像圖（不再顯示姓名首字）
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(CircleShape)
                                    .background(PANEL)
                                    .border(1.5.dp, TRACK, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(com.kisslink.R.drawable.ic_avatar_default),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .matchParentSize()
                                        .padding(avatarR * 0.30f)
                                )
                            }
                        }
                    }

                    // 完成打勾（頭像隱藏時顯示）
                    if (checkAlpha.value > 0f) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val s = size.minDimension * 0.30f
                            drawCheckMark(c, s, checkDraw.value, ACCENT, 4.dp.toPx(), checkAlpha.value)
                        }
                    }
                }
            }

            // ── #14 名片飛出：置中、小尺寸縮入對方頭像（完全在可見範圍內，不會被裁切）──
            val t = fly.value
            if (t > 0f && t < 1f) {
                val e = FastOutSlowInEasing.transform(t)
                val driftPx = with(density) { 22.dp.toPx() }
                Box(
                    modifier = Modifier
                        .size(width = 92.dp, height = 116.dp)
                        .graphicsLayer {
                            val s = lerp(0.82f, 0.06f, e)
                            scaleX = s
                            scaleY = s
                            translationY = lerp(-driftPx, 0f, e)  // 由頭像上方些微下沉，縮入頭像中心
                            alpha = (1f - ((t - 0.55f) / 0.45f)).coerceIn(0f, 1f)
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .background(PANEL)
                        .border(1.dp, TRACK, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "名片",
                        style = TextStyle(color = INK, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    )
                }
            }
        }
    }
}

/**
 * 像素風打勾：11 個固定像素格，依 fraction f（0..1）逐格點亮。
 * 格子座標以 (-5..5, -5..5) 的 11×11 網格定義，單格 = px 大小。
 */
private fun DrawScope.drawCheckMark(
    c: Offset, s: Float, f: Float, color: Color, w: Float, alpha: Float
) {
    // 像素勾：以中心為原點的相對格座標（col, row），從左下往右上
    val pixels = listOf(
        Pair(-4,  0),
        Pair(-3, -1),
        Pair(-2, -2),
        Pair(-1, -1),
        Pair( 0,  0),
        Pair( 1,  1),
        Pair( 2,  2),
        Pair( 3,  1),
        Pair( 4,  0),
        Pair( 5, -1),
        Pair( 6, -2)
    )
    val col = color.copy(alpha = alpha)
    val cell = s * 0.28f          // 單格大小（s ≒ size.minDimension * 0.30）
    val count = (f * pixels.size).toInt().coerceAtMost(pixels.size)
    for (i in 0 until count) {
        val (gx, gy) = pixels[i]
        drawRect(
            col,
            topLeft = Offset(c.x + gx * cell - cell / 2f, c.y - gy * cell - cell / 2f),
            size    = Size(cell, cell)
        )
    }
}

/**
 * 像素格圓環：把半徑 r 附近的像素格著色，fraction 控制繞圓的比例（0..1）。
 * 從 12 點鐘方向順時針繪出。
 */
private fun DrawScope.drawPixelRing(
    c: Offset, r: Float, px: Float, color: Color, fraction: Float
) {
    // 遍歷包圍環的方格，取落在環寬範圍內的格子
    val halfW = px * 1.5f
    val rIn   = r - halfW
    val rOut  = r + halfW
    val x0 = ((c.x - rOut) / px).toInt().coerceAtLeast(0)
    val x1 = ((c.x + rOut) / px).toInt().coerceAtMost((size.width / px).toInt())
    val y0 = ((c.y - rOut) / px).toInt().coerceAtLeast(0)
    val y1 = ((c.y + rOut) / px).toInt().coerceAtMost((size.height / px).toInt())
    for (gx in x0..x1) {
        for (gy in y0..y1) {
            val cx = gx * px + px / 2f
            val cy = gy * px + px / 2f
            val d  = hypot((cx - c.x).toDouble(), (cy - c.y).toDouble()).toFloat()
            if (d !in rIn..rOut) continue
            // 計算此格的角度（0 = 12 點鐘，順時針）
            val angle = (Math.toDegrees(
                Math.atan2((cy - c.y).toDouble(), (cx - c.x).toDouble())
            ).toFloat() + 90f + 360f) % 360f
            if (angle <= fraction * 360f) {
                drawRect(color, topLeft = Offset(gx * px, gy * px), size = Size(px, px))
            }
        }
    }
}
