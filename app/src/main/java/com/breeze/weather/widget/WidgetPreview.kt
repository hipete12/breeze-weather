package com.breeze.weather.widget

import android.util.Log
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Shows real widget RemoteViews inside the app, at the widget's size in dp, scaled down to fit
 * [maxWidth] × [maxHeight] if needed. What you see is exactly what the launcher will draw.
 */
@Composable
fun RemoteViewsPreview(
    views: RemoteViews,
    widthDp: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
    maxWidth: Dp = Dp.Infinity,
    maxHeight: Dp = Dp.Infinity,
) {
    val scale = minOf(1f, maxWidth.value / widthDp, maxHeight.value / heightDp)
    Box(modifier.size((widthDp * scale).dp, (heightDp * scale).dp), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { FrameLayout(it) },
            update = { frame ->
                frame.removeAllViews()
                runCatching { views.apply(frame.context, frame) }
                    .onSuccess { frame.addView(it) }
                    .onFailure { Log.e("BreezeWidgets", "Preview failed", it) }
            },
            modifier = Modifier
                .requiredSize(widthDp.dp, heightDp.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** [RemoteViewsPreview] that fills the space it's given, keeping the widget's proportions. */
@Composable
fun FittedRemoteViewsPreview(views: RemoteViews, widthDp: Float, heightDp: Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        RemoteViewsPreview(views, widthDp, heightDp, maxWidth = maxWidth, maxHeight = maxHeight)
    }
}
