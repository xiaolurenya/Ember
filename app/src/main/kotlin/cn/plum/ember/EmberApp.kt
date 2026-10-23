package cn.plum.ember

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import cn.plum.ember.data.App

/**
 * Application：初始化数据层 + Coil 图片加载器（海报墙性能的关键）。
 * MPV 内核不放这里初始化——播放会话是页面级的，跟随 PlayerScreen 生命周期。
 */
class EmberApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                // 用掉 1/4 可用内存做海报缓存
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("poster_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)   // 256MB 海报缓存
                    .build()
            }
            .crossfade(180)
            .build()
}
