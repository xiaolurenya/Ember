package cn.plum.ember.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.R
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.ui.widgets.isTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 开屏动画：品牌 Logo 渐显 + 呼吸缩放（1.0 → 1.05 → 1.0 循环），底部细进度条。
 * 时长 2~2.5 秒：期间并行完成 MPV 内核预热和已保存源检查；点屏跳过。
 */
@Composable
fun SplashScreen(
    hasSource: Boolean,
    onReady: (goHome: Boolean) -> Unit,
    preInit: suspend () -> Unit = {}
) {
    val tv = isTv()
    val logoSize = if (tv) 220.dp else 140.dp

    // 渐显 + 轻微放大（0.85 → 1.0）
    val fade = remember { Animatable(0f) }
    val zoom = remember { Animatable(0.85f) }

    // 呼吸动效：无限循环，幅度极小
    val breathe = rememberInfiniteTransition(label = "breathe")
    val breatheScale = breathe.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "breathe-scale"
    )
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 并行：动画 + 内核预热 + 源检查
        val anim = launch {
            fade.animateTo(1f, tween(700))
            zoom.animateTo(1f, tween(900))
        }
        val bar = launch {
            while (true) {
                progress.animateTo(1f, tween(2400, easing = LinearEasing))
                break
            }
        }
        delay(400)                       // Logo 先露脸再干活
        preInit()                        // MPV 预热 / 源扫描
        delay(1600)                      // 让动画完整播完
        anim.join()
        bar.cancel()
        onReady(hasSource)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0908))
            .pointerInput(Unit) { detectTapGestures { onReady(hasSource) } },   // 点屏跳过
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.logo_ember),
                contentDescription = "Ember",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(logoSize)
                    .alpha(fade.value)
                    .scale(zoom.value * breatheScale.value)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Ember",
                color = Color(0xFFF5F1EC),
                fontSize = if (tv) 30.sp else 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(fade.value)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "原画直出 · 私有影音",
                color = TextLow,
                fontSize = if (tv) 14.sp else 12.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.alpha(fade.value)
            )
        }

        // 底部加载进度条
        LinearProgressIndicator(
            progress = { progress.value },
            color = Ember,
            trackColor = Color(0x22FF7A18),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .size(width = 200.dp, height = 2.dp)
        )
        Text(
            text = "Ember Player v1.0.0",
            color = TextMid.copy(alpha = 0.4f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
        )
    }
}
