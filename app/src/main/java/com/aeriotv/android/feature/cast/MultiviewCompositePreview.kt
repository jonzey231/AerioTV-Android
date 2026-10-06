package com.aeriotv.android.feature.cast

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.cast.multiview.MultiviewCastController
import com.aeriotv.android.core.cast.multiview.MultiviewCompositeLayout

/**
 * The phone's view of a composited Multiview cast (2026-10-06): the exact
 * frame the receiver gets, drawn by the compositor into a TextureView. Tap a
 * tile to give it the audio; the highlight follows on both screens and the
 * cast keeps running.
 */
@Composable
fun MultiviewCompositePreview(controller: MultiviewCastController, modifier: Modifier = Modifier) {
    val session by controller.session.collectAsStateWithLifecycle()
    val count = session?.tiles?.size ?: 0
    val latestCount by rememberUpdatedState(count)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(MultiviewCompositeLayout.WIDTH / MultiviewCompositeLayout.HEIGHT.toFloat())
            .clip(RoundedCornerShape(8.dp)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        private var surface: Surface? = null
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            val s = Surface(st)
                            surface = s
                            controller.attachPreview(s)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surface?.let {
                                controller.detachPreview(it)
                                it.release()
                            }
                            surface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
        )
        // Taps on a transparent layer above the TextureView (the view would
        // otherwise see them first).
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val index = MultiviewCompositeLayout.hitTestView(
                            pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), latestCount,
                        )
                        if (index >= 0) {
                            Log.i("AerioCast", "[MV-CAST] preview tap tile=$index")
                            controller.setFocus(index)
                        }
                    }
                },
        )
    }
}
