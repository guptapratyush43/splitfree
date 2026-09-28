package com.splitfree

import android.app.Application
import com.splitfree.backup.Backup
import com.splitfree.data.Auth
import com.splitfree.data.Recurring
import com.splitfree.data.Repo
import com.splitfree.push.PushService

/** Wikimedia refuses image requests without an identifying User-Agent, so the image loader sends one. */
/** App-wide scope for work that should outlive a screen, like an update download. */
val AppScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

class SplitApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "SplitFree/1.3 (Android expense-splitting app)").build())
            }.build()
        }
        .crossfade(false)
        // Keep downloaded banners on the phone for good; each photo is fetched only once.
        .respectCacheHeaders(false)
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("banners")).maxSizeBytes(100L * 1024 * 1024).build() }
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .build()

    override fun onCreate() {
        super.onCreate()
        PushService.createChannel(this)
        Auth.init()
        Repo.init(this)
        Backup.init(this)
        Recurring.schedule(this)
        com.splitfree.update.UpdateManager.init(this)
        com.splitfree.update.UpdateWorker.schedule(this)
    }
}
