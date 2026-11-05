package dev.syvyj.dropyk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DropykApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Drop.init(this)
    }
}

private val Bg = Color(0xFF12141F)
private val Card = Color(0xFF1C2030)
private val Line = Color(0xFF2A3045)
private val TextPrimary = Color(0xFFEEF1FA)
private val TextMuted = Color(0xFF9AA3B8)
private val Blue = Color(0xFF4C8DFF)
private val Violet = Color(0xFF7A4CF2)
private val Ok = Color(0xFF7FD1A8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A big upload takes minutes; the screensaver shouldn't hide the progress.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { DropScreen() }
    }
}

@Composable
private fun DropScreen() {
    val url = remember { Drop.start() }
    val state by Drop.state.collectAsStateWithLifecycle()
    val actionFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Bg).padding(horizontal = 64.dp, vertical = 48.dp)) {
        Column(Modifier.align(Alignment.Center).width(820.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Droplet(receiving = state is DropState.Receiving)
                Column {
                    Text("Dropyk", color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.tagline), color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Box(Modifier.height(28.dp))

            val showQr = state is DropState.Waiting || state is DropState.Connected || state is DropState.Failed
            if (url == null) {
                Status(stringResource(R.string.no_network))
            } else if (showQr) {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(url, 240.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.step1), color = TextPrimary, fontSize = 22.sp)
                        Text(stringResource(R.string.step2), color = TextPrimary, fontSize = 22.sp)
                        Text(url, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Box(Modifier.height(28.dp))
            }

            when (val s = state) {
                DropState.Waiting -> if (url != null) Status(stringResource(R.string.waiting))
                DropState.Connected -> Status(stringResource(R.string.connected))
                is DropState.Receiving -> {
                    Status(stringResource(R.string.receiving, s.name, (s.done * 100 / s.total.coerceAtLeast(1)).toInt(), size(s.total)))
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Line, RoundedCornerShape(3.dp))) {
                        Box(
                            Modifier.fillMaxWidth(s.done.toFloat() / s.total.coerceAtLeast(1)).height(6.dp)
                                .background(Brush.horizontalGradient(listOf(Blue, Violet)), RoundedCornerShape(3.dp)),
                        )
                    }
                }
                is DropState.Received -> {
                    val context = LocalContext.current
                    Status(stringResource(R.string.received, s.name, s.count), ok = true)
                    if (s.mime.startsWith("image/")) Preview(s.uri)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
                        when {
                            s.mime.startsWith("video/") || s.mime.startsWith("audio/") ->
                                Action(stringResource(R.string.watch), actionFocus) { openFile(context, s.uri, s.name, s.mime) }
                            s.mime == Drop.APK -> Action(stringResource(R.string.install), actionFocus) { install(context, s.uri, s.name) }
                            s.mime.startsWith("image/") -> Unit
                            else -> Action(stringResource(R.string.open), actionFocus) { openFile(context, s.uri, s.name, s.mime) }
                        }
                        Action(stringResource(R.string.done), if (s.mime.startsWith("image/")) actionFocus else null) { Drop.dismiss() }
                    }
                    Text(stringResource(R.string.saved_to, "Download/${Drop.FOLDER}"), color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                }
                is DropState.Link -> {
                    val context = LocalContext.current
                    Status(s.url, ok = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Action(stringResource(R.string.open_link), actionFocus) {
                            openUri(context, Intent(Intent.ACTION_VIEW, Uri.parse(s.url)))
                            Drop.dismiss()
                        }
                        Action(stringResource(R.string.done), null) { Drop.dismiss() }
                    }
                }
                is DropState.Failed -> Status(stringResource(R.string.failed, s.name))
            }
        }
    }

    // Something arrived: focus jumps to the action — the person is holding a remote now.
    LaunchedEffect(state) {
        if (state is DropState.Received || state is DropState.Link) runCatching { actionFocus.requestFocus() }
    }
}

@Composable
private fun Status(text: String, ok: Boolean = false) {
    Text(text, color = if (ok) Ok else TextMuted, fontSize = 20.sp, maxLines = 2, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun Action(label: String, focus: FocusRequester?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        colors = ButtonDefaults.colors(containerColor = Card, contentColor = TextPrimary, focusedContainerColor = Blue, focusedContentColor = Color.White),
    ) { Text(label, fontSize = 18.sp) }
}

/**
 * The app icon's droplet. Idle it breathes slowly; while a file is coming in it gives one short
 * pulse a second and holds still in between — every frame drawn during a transfer costs Wi-Fi
 * throughput on cheap boxes (see PROGRESS_EVERY_MS in Drop).
 */
@Composable
private fun Droplet(receiving: Boolean) {
    val transition = rememberInfiniteTransition(label = "droplet")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            if (receiving) keyframes {
                durationMillis = 1000
                1f at 0
                1.12f at 150
                1f at 350
                1f at 1000
            } else keyframes {
                durationMillis = 2800
                1f at 0 using FastOutSlowInEasing
                0.9f at 1400 using FastOutSlowInEasing
                1f at 2800
            },
        ),
        label = "scale",
    )
    val gradient = remember { Brush.verticalGradient(listOf(Blue, Violet)) }
    Canvas(Modifier.size(width = 52.dp, height = 72.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        // The icon's droplet, drawn in its 1024-unit space and cropped to x 282..742, y 180..815.
        val k = size.height / 635f
        fun p(x: Float, y: Float) = Offset((x - 282f) * k, (y - 180f) * k)
        val drop = Path().apply {
            moveTo(p(512f, 194f).x, p(512f, 194f).y)
            cubicTo(p(572f, 304f).x, p(572f, 304f).y, p(727f, 424f).x, p(727f, 424f).y, p(727f, 584f).x, p(727f, 584f).y)
            arcTo(Rect(p(297f, 369f), p(727f, 799f)), 0f, 180f, false)
            cubicTo(p(297f, 424f).x, p(297f, 424f).y, p(452f, 304f).x, p(452f, 304f).y, p(512f, 194f).x, p(512f, 194f).y)
            close()
        }
        drawPath(drop, gradient)
        val arrow = Path().apply {
            moveTo(p(512f, 464f).x, p(512f, 464f).y); lineTo(p(512f, 684f).x, p(512f, 684f).y)
            moveTo(p(417f, 589f).x, p(417f, 589f).y); lineTo(p(512f, 684f).x, p(512f, 684f).y); lineTo(p(607f, 589f).x, p(607f, 589f).y)
        }
        drawPath(arrow, Color.White, style = Stroke(width = 46f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Photo preview, downsampled: a 48 MP shot decoded as is would eat a TV box's memory. */
@Composable
private fun Preview(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { decodeSampled(context, uri, 1600) }.getOrNull() }
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp)),
        )
    }
}

private fun decodeSampled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

/** The QR matrix comes from zxing; drawing 25×25 squares on one Canvas beats making a Bitmap. */
@Composable
private fun QrCode(content: String, size: Dp) {
    val matrix = remember(content) {
        runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 25, 25, mapOf(EncodeHintType.MARGIN to 0)) }.getOrNull()
    } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        val cell = this.size.width / matrix.width
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

private fun size(bytes: Long): String =
    if (bytes > 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

private fun openFile(context: Context, uri: Uri, name: String, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    openUri(context, intent, name)
}

private fun openUri(context: Context, intent: Intent, title: String? = null) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching { context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            Log.w("Dropyk", "open ${intent.data}", it)
            Toast.makeText(context, R.string.no_app, Toast.LENGTH_LONG).show()
        }
}

/** APK: first the one-time "install unknown apps" permission, then the system installer. */
private fun install(context: Context, uri: Uri, name: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
        openUri(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        return
    }
    openFile(context, uri, name, Drop.APK)
}
