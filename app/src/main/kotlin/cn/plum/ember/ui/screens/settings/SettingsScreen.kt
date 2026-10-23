package cn.plum.ember.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.plum.ember.data.App
import cn.plum.ember.data.PlayerSettings
import cn.plum.ember.ui.theme.Ember
import cn.plum.ember.ui.theme.Ink
import cn.plum.ember.ui.theme.InkRaised
import cn.plum.ember.ui.theme.TextHi
import cn.plum.ember.ui.theme.TextLow
import cn.plum.ember.ui.theme.TextMid
import cn.plum.ember.util.Format
import kotlinx.coroutines.launch

/** 设置页：解码 / 缓存 / 播放行为 / 字幕偏移 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = remember { App.prefs.snapshotBlocking() }
    var hwdec by remember { mutableStateOf(s.hwdec) }
    var cacheSecs by remember { mutableFloatStateOf(s.cacheSecs.toFloat()) }
    var autoSkip by remember { mutableStateOf(s.autoSkipIntro) }
    var autoNext by remember { mutableStateOf(s.autoPlayNext) }
    var hdr by remember { mutableStateOf(s.hdrPassthrough) }
    var showInfo by remember { mutableStateOf(s.showDecoderInfo) }
    var seekStep by remember { mutableFloatStateOf(s.seekStep.toFloat()) }
    var landscape by remember { mutableStateOf(s.landscapePlayer) }

    Column(
        Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextHi)
            }
            Text("播放设置", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))

        SettingSection("解码")
        SwitchRow("硬件解码优先", "失败自动回退软解，默认开启", hwdec) {
            hwdec = it
            scope.launch { App.prefs.setHwdec(it) }
        }
        SwitchRow("HDR 直通", "关闭则色调映射为 SDR（老设备更稳）", hdr) {
            hdr = it
            scope.launch { App.prefs.setHdrPassthrough(it) }
        }
        SwitchRow("显示解码信息浮层", "分辨率 / 编解码 / 缓存实时显示", showInfo) {
            showInfo = it
            scope.launch { App.prefs.setShowDecoderInfo(it) }
        }

        SettingSection("缓存")
        SliderRow("预读缓冲", "${cacheSecs.toInt()} 秒", cacheSecs, 10f, 60f) { v ->
            cacheSecs = v
            scope.launch { App.prefs.setCacheSecs(v.toInt()) }
        }

        SettingSection("播放行为")
        SwitchRow("自动跳过片头片尾", "根据片头时长智能跳过", autoSkip) {
            autoSkip = it
            scope.launch { App.prefs.setAutoSkipIntro(it) }
        }
        SwitchRow("自动连播下一集", "播放结束自动切下一集", autoNext) {
            autoNext = it
            scope.launch { App.prefs.setAutoPlayNext(it) }
        }
        SwitchRow("横屏播放", "进入播放器自动横屏（手机生效）", landscape) {
            landscape = it
            scope.launch { App.prefs.setLandscapePlayer(it) }
        }
        SliderRow("快进快退步长", "${seekStep.toInt()} 秒", seekStep, 5f, 60f) { v ->
            seekStep = v
            scope.launch { App.prefs.setSeekStep(v.toInt()) }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            "Ember v1.0.0\n基于 libmpv 内核 · STRM/302 直连 · 未加密蓝光/UHD/DVD 直读",
            color = TextLow, fontSize = 11.sp, lineHeight = 17.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SettingSection(title: String) {
    Text(
        title,
        color = Ember, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 15.sp)
            Text(subtitle, color = TextLow, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = Ember.copy(alpha = 0.4f),
            checkedThumbColor = Ember
        ))
    }
}

@Composable
private fun SliderRow(title: String, valueLabel: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(InkRaised)
            .padding(14.dp)
    ) {
        Row {
            Text(title, color = TextHi, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text(valueLabel, color = Ember, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = Ember, activeTrackColor = Ember,
                inactiveTrackColor = Color(0x33FFFFFF)
            )
        )
    }
}
