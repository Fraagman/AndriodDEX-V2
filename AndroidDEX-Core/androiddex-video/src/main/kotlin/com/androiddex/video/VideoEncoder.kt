package com.androiddex.video

import android.view.Surface
import com.androiddex.core.VideoConfig
import com.androiddex.core.EventBus

/**
 * Abstraction for a video encoder pipeline.
 * Implementations could be MediaCodec (Hardware), libx264 (Software), etc.
 */
interface VideoEncoder {
    
    /**
     * Prepares the encoder based on the given configuration.
     * @return A Surface that external sources (like VirtualDisplay) can render to,
     *         or null if the encoder does not use a Surface input.
     */
    fun prepare(config: VideoConfig, eventBus: EventBus): Surface?
    
    /** Starts the encoding process. */
    fun start()
    
    /** Stops encoding and releases resources. */
    fun stop()
    
    /** Requests an immediate IDR (Keyframe) to be generated. Useful for recovering from packet loss. */
    fun requestKeyFrame()
}
