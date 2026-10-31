package com.sleektv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sleektv.data.JellyfinApi
import com.sleektv.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SleekApp : Application(), ImageLoaderFactory {
    lateinit var session: Session
        private set
    lateinit var api: JellyfinApi
        private set

    /** For work that must outlive a screen, like reporting that playback stopped. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        session = Session(this)
        api = JellyfinApi(session)
    }

    // Images go through the same HTTP client, so they carry the Jellyfin auth header.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(api.http)
            .crossfade(false) // fading every card in costs frames on TV boxes
            .allowRgb565(true) // half the memory per image, decodes faster; fine for photos
            .respectCacheHeaders(false) // Jellyfin image URLs carry a tag, so cached copies are safe to reuse
            .memoryCache {
                coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .build()
}
