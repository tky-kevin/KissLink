package com.kisslink.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import kotlinx.coroutines.delay
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

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
class BeamStageView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
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

        fun setPhase(p: Int) {
            phaseState.value = p
        }

        fun setDirection(d: Int) {
            directionState.value = d
        }

        fun setProgress(p: Float) {
            progressState.value = p.coerceIn(0f, 1f)
        }

        fun setPeerAvatar(b: Bitmap?) {
            peerAvatarState.value = b
        }

        fun setSelfAvatar(b: Bitmap?) {
            selfAvatarState.value = b
        }

        fun setPeerIdentity(name: String?) {
            peerNameState.value = monogram(name)
        }

        fun setSelfIdentity(name: String?) {
            selfNameState.value = monogram(name)
        }

        /** 名片飛出動畫已移除；保留無作用的呼叫點以相容傳送流程。 */
        fun playCardFly() { /* no-op */ }

        private fun monogram(name: String?): String? {
            val s = name?.trim().orEmpty()
            return if (s.isEmpty()) null else s.substring(0, 1).uppercase()
        }

        @Composable
        override fun Content() {
            beamStage(
                phase = phaseState.value,
                rawProgress = progressState.value,
                peerAvatar = peerAvatarState.value,
                peerMono = peerNameState.value,
            )
        }
    }

private val ACCENT = Color(0xFF1A73E8)

@Composable
private fun beamStage(
    phase: Int,
    rawProgress: Float,
    peerAvatar: Bitmap?,
    peerMono: String?,
) {
    // 動態顏色：跟隨深/淺模式（不依賴 Material3，直接讀系統主題）
    val dark = isSystemInDarkTheme()
    val track = if (dark) Color(0xFF38342F) else Color(0xFFDCD8CF)
    val panel = if (dark) Color(0xFF242220) else Color(0xFFF4F2EC)

    val connected =
        phase == BeamStageView.CONNECTED ||
            phase == BeamStageView.TRANSFERRING || phase == BeamStageView.DONE
    val transferring = phase == BeamStageView.TRANSFERRING
    val done = phase == BeamStageView.DONE
    val error = phase == BeamStageView.ERROR

    // ── 狀態以「顏色」傳達（取代僅靠文字搬動）──
    // 連線中→藍↔藍綠之間平滑循環（積極感）；錯誤→紅；其餘→品牌藍。
    // 波紋／進度環／打勾統一吃這個顏色，光看中央光環的色彩就能表達狀態，標題文字退為輔助。
    val errorColor = if (dark) Color(0xFFCF6E60) else Color(0xFFB0463A)
    val tealColor = Color(0xFF12B5A6)
    val connecting = phase == BeamStageView.CONNECTING

    val infinite = rememberInfiniteTransition(label = "beam")
    // 連線中：藍↔藍綠來回補間。
    val connectPulse by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "connectPulse",
    )
    // 非連線中的狀態色（錯誤紅/品牌藍）平滑補間；連線中改用上面的藍綠循環。
    val baseColor by animateColorAsState(
        targetValue = if (error) errorColor else ACCENT,
        animationSpec = tween(420),
        label = "baseColor",
    )
    // 連線中與傳輸中都用藍↔藍綠循環（傳輸中的顏色變化比照連線中）。
    val stateColor = if (connecting || transferring) lerp(ACCENT, tealColor, connectPulse) else baseColor

    // 律動也編碼狀態：配對/連線中加快波紋（積極感），錯誤放慢（凝滯感），其餘為待機呼吸。
    val rippleMs =
        when (phase) {
            BeamStageView.CONNECTING -> 1500
            BeamStageView.ERROR -> 3200
            else -> 2600
        }
    // 波紋「密度」(同時在場的環數)依中央內容分三類：
    //  · 中央是頭像/打勾(已連線/傳輸/完成) → 5 環；
    //  · 中央是 Lottie 雷達(就緒/連線中) 與 錯誤 → 維持 3 環。
    val rippleCount = if (connected) 5 else 3
    val ripple by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(rippleMs, easing = LinearEasing)),
        label = "ripple",
    )

    // #4 平滑進度
    // 線性補間：配合外層已單調化的進度，視覺速度穩定（不忽快忽慢、不回退）
    val animProgress by animateFloatAsState(
        targetValue =
            when {
                done -> 1f
                transferring -> rawProgress
                else -> 0f
            },
        animationSpec = tween(400, easing = LinearEasing),
        label = "progress",
    )

    // 完成打勾 / 頭像切換動畫
    val avatarAlpha = remember { Animatable(0f) }
    val checkAlpha = remember { Animatable(0f) }
    val checkDraw = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            BeamStageView.DONE -> {
                avatarAlpha.animateTo(0f, tween(140)) // 隱藏頭像
                checkDraw.snapTo(0f)
                checkAlpha.snapTo(1f)
                checkDraw.animateTo(1f, tween(440, easing = FastOutSlowInEasing)) // 畫勾
                delay(560)
                checkAlpha.animateTo(0f, tween(220)) // 勾淡出
                avatarAlpha.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) // 切回頭像
            }
            BeamStageView.CONNECTED, BeamStageView.TRANSFERRING -> {
                checkAlpha.snapTo(0f)
                checkDraw.snapTo(0f)
                if (avatarAlpha.value < 1f) avatarAlpha.animateTo(1f, tween(340))
            }
            else -> {
                avatarAlpha.snapTo(0f)
                checkAlpha.snapTo(0f)
                checkDraw.snapTo(0f)
            }
        }
    }

    val avatarR = 56.dp // 比之前(48dp)更大
    // 進度環貼著頭像外緣：環內緣≈頭像邊（環寬 5dp，半徑外推約半個環寬即可，不留空隙）。
    val ringGap = 2.dp

    // 用整個可用空間置中（不再用固定 240dp，避免畫面較矮時上/下緣被裁切）
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // ── 環/波紋層 ──
            Canvas(modifier = Modifier.matchParentSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val avatarPx = avatarR.toPx()
                val ringR = avatarPx + ringGap.toPx()
                val strokeW = 5.dp.toPx()

                // 常駐 NFC 漣漪 (現代流體感)。傳輸/完成時從外圈進度環外緣起跑(不穿過頭像)。
                val rippleStart = if (transferring || done) ringR + 3.dp.toPx() else 0f
                val rippleMax = (if (transferring || done) ringR else avatarPx) + 80.dp.toPx()
                for (i in 0 until rippleCount) {
                    val t = (ripple + i.toFloat() / rippleCount) % 1f
                    val r = rippleStart + (rippleMax - rippleStart) * t
                    val fadeIn = (t / 0.15f).coerceIn(0f, 1f)
                    val fadeOut = (1f - t).coerceIn(0f, 1f)
                    val a = fadeIn * fadeOut * 0.35f
                    if (a > 0f) {
                        drawCircle(
                            color = stateColor.copy(alpha = a),
                            radius = r,
                            center = c,
                        )
                        drawCircle(
                            color = stateColor.copy(alpha = a * 1.5f),
                            radius = r,
                            center = c,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }

                // 傳輸/完成：頭像外圈進度環（底環 track + 進度弧 stateColor）。
                // 已連線待機不畫環（僅頭像 + 波紋）。
                if (transferring || done) {
                    drawArc(
                        color = track,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = Size(ringR * 2, ringR * 2),
                    )
                    drawArc(
                        color = stateColor,
                        startAngle = -90f,
                        sweepAngle = 360f * animProgress,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = Size(ringR * 2, ringR * 2),
                    )
                }
            }

            // ── 中央節點（Lottie 雷達）──
            // 雷達整體染成 stateColor → 各狀態（就緒藍／連線中藍綠循環／錯誤紅）雷達跟著變色。
            if (!connected) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(com.kisslink.R.raw.lottie_radar))
                val radarTint =
                    rememberLottieDynamicProperties(
                        rememberLottieDynamicProperty(
                            LottieProperty.COLOR_FILTER,
                            PorterDuffColorFilter(stateColor.toArgb(), PorterDuff.Mode.SRC_ATOP),
                            "**",
                        ),
                    )
                Box(modifier = Modifier.size(avatarR * 2.5f), contentAlignment = Alignment.Center) {
                    LottieAnimation(
                        composition = composition,
                        iterations = LottieConstants.IterateForever,
                        dynamicProperties = radarTint,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            } else {
                Box(modifier = Modifier.size(avatarR * 2), contentAlignment = Alignment.Center) {
                    // 頭像（依 avatarAlpha 淡入/縮放）
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .graphicsLayer {
                                    alpha = avatarAlpha.value
                                    val sc = 0.86f + 0.14f * avatarAlpha.value
                                    scaleX = sc
                                    scaleY = sc
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bmp = peerAvatar
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier =
                                    Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .border(1.5.dp, track, CircleShape),
                            )
                        } else {
                            // 對方沒有自訂頭像 → 顯示預設頭像圖（不再顯示姓名首字）
                            Box(
                                modifier =
                                    Modifier
                                        .matchParentSize()
                                        .clip(CircleShape)
                                        .background(panel)
                                        .border(1.5.dp, track, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(com.kisslink.R.drawable.ic_avatar_default),
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .matchParentSize()
                                            .padding(avatarR * 0.30f),
                                )
                            }
                        }
                    }

                    // 完成打勾（頭像隱藏時顯示，改為平滑線條）
                    if (checkAlpha.value > 0f) {
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val s = size.minDimension * 0.40f
                            drawModernCheckMark(c, s, checkDraw.value, stateColor, 6.dp.toPx(), checkAlpha.value)
                        }
                    }
                }
            }

            // ── Konfetti 慶祝特效 ──
            // konfetti-compose 2.0.4 的 KonfettiView 內部用 LaunchedEffect(Unit)，
            // 只在「首次組合」時依當下的 parties 建一次 PartySystem 並永久跑逐幀迴圈；
            // 之後即使傳入新的 parties 也不會重新發射（這就是第二次以後彩帶不出現的原因）。
            // 因此每次完成都用遞增的 key 讓整個 KonfettiView 重建，強迫它重跑發射。
            var celebrateKey by remember { mutableStateOf(0) }
            LaunchedEffect(phase) {
                if (phase == BeamStageView.DONE) celebrateKey++
            }
            if (celebrateKey > 0) {
                key(celebrateKey) {
                    KonfettiView(
                        modifier = Modifier.fillMaxSize(),
                        parties =
                            listOf(
                                Party(
                                    speed = 0f,
                                    maxSpeed = 30f,
                                    damping = 0.9f,
                                    spread = 360,
                                    colors = listOf(0xFFFCE18A.toInt(), 0xFFFF726D.toInt(), 0xFFF4306D.toInt(), 0xFFB48DEF.toInt()),
                                    emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(100),
                                    position = Position.Relative(0.5, 0.5),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 現代平滑打勾線條：依 fraction f（0..1）繪出長度
 */
private fun DrawScope.drawModernCheckMark(
    c: Offset,
    s: Float,
    f: Float,
    color: Color,
    w: Float,
    alpha: Float,
) {
    if (f <= 0f) return
    val p1 = Offset(c.x - s * 0.4f, c.y)
    val p2 = Offset(c.x - s * 0.1f, c.y + s * 0.3f)
    val p3 = Offset(c.x + s * 0.5f, c.y - s * 0.4f)

    val l1 = hypot(p2.x - p1.x, p2.y - p1.y)
    val l2 = hypot(p3.x - p2.x, p3.y - p2.y)
    val totalL = l1 + l2

    val drawL = totalL * f
    val col = color.copy(alpha = alpha)

    if (drawL <= l1) {
        val curP =
            Offset(
                p1.x + (p2.x - p1.x) * (drawL / l1),
                p1.y + (p2.y - p1.y) * (drawL / l1),
            )
        drawLine(col, p1, curP, strokeWidth = w, cap = StrokeCap.Round)
    } else {
        drawLine(col, p1, p2, strokeWidth = w, cap = StrokeCap.Round)
        val rem = drawL - l1
        val curP =
            Offset(
                p2.x + (p3.x - p2.x) * (rem / l2),
                p2.y + (p3.y - p2.y) * (rem / l2),
            )
        drawLine(col, p2, curP, strokeWidth = w, cap = StrokeCap.Round)
    }
}
