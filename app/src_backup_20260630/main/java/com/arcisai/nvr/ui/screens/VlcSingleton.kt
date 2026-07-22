package com.arcisai.nvr.ui.screens

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * One LibVLC instance shared across all players in the process.
 * LibVLC's native init is expensive (~200-500ms) and blocks the calling
 * thread. Creating four of them simultaneously in a 2×2 grid causes an ANR.
 * Sharing one instance is the VLC-recommended usage pattern.
 */
internal object VlcSingleton {
    @Volatile private var vlc: LibVLC? = null

    fun get(context: Context): LibVLC =
        vlc ?: synchronized(this) {
            vlc ?: create(context.applicationContext).also { vlc = it }
        }

    private fun create(ctx: Context) = LibVLC(ctx, arrayListOf(
        "--drop-late-frames",
        "--skip-frames",
        "--rtsp-frame-buffer-size=1100000",
        "--network-caching=300",
        "--live-caching=300",
        "--clock-jitter=0",
        "--clock-synchro=0",
        "--avcodec-fast",
        "-q",
    ))
}
