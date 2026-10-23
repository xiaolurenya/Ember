package cn.plum.ember.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

object Format {
    /** 秒 → "1:23:45" 或 "23:45" */
    fun duration(totalSec: Long): String {
        if (totalSec <= 0) return "0:00"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 「已看 42%」这类进度 */
    fun percent(pos: Long, total: Long): Int {
        if (total <= 0) return 0
        return ((pos * 100.0) / total).toInt().coerceIn(0, 100)
    }

    /** 文件大小 → "1.2 GB" */
    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f%s", v, units[max(0, i)])
    }

    /** 相对时间戳 → "3 天前" */
    fun ago(millis: Long): String {
        val diff = System.currentTimeMillis() - millis
        val m = diff / 60000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            m < 1440 -> "${m / 60} 小时前"
            m < 43200 -> "${m / 1440} 天前"
            else -> {
                val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                fmt.format(Date(millis))
            }
        }
    }

    /** 码率 → "18.4 Mbps" */
    fun bitrate(bps: Long): String {
        if (bps <= 0) return "—"
        val mbps = bps / 1_000_000.0
        return if (mbps >= 1) String.format(Locale.US, "%.1f Mbps", mbps)
        else "${bps / 1000} Kbps"
    }

    /** 海报宽高比 2:3 的网格自适应列数：宽屏设备给更多列 */
    fun posterColumns(widthDp: Float): Int {
        if (widthDp < 300f) return 2
        if (widthDp < 480f) return 3
        if (widthDp < 720f) return 4
        if (widthDp < 1000f) return 5
        return 6
    }

    /** 对数刻度的倍速按钮列表 */
    fun speedTable(): List<Float> =
        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)

    fun dB(v: Float): Int = (20 * ln(max(0.01f, v).toDouble())).let { floor(it).toInt() }
}
