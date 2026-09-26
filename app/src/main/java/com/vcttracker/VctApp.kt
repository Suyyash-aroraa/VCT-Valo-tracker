package com.vcttracker

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.vcttracker.data.Repository

class VctApp : Application(), ImageLoaderFactory {
    val repository: Repository by lazy { Repository(this) }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        .respectCacheHeaders(false)
        .build()
}
