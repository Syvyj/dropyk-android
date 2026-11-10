package dev.syvyj.dropyk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.provider.Settings
import android.util.Log
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
import androidx.core.content.edit
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DropykApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Drop.init(this)
        Update.init(this)
        // Quick Share starts with the screen or the background service, not with the process: a
        // reboot wakes the process (the boot receiver) even with Receive in background off.
        // Lets watch() hand players a file:// path; Android 7+ kills the app for that by default.
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }
}


/**
 * Dark by default, like the TV around it: a light screen glares in a dark living room. The light
 * one is a lavender room with near-white cards. Either way the QR sits on pure white, which the
 * scanner needs. Colors are read through [Theme.dark], so switching recomposes everything.
 */
private object Theme {
    var dark by mutableStateOf(true)
    fun load(context: Context) { dark = context.getSharedPreferences("drop", Context.MODE_PRIVATE).getBoolean("dark", true) }
    fun set(context: Context, on: Boolean) {
        dark = on
        context.getSharedPreferences("drop", Context.MODE_PRIVATE).edit { putBoolean("dark", on) }
    }
}

private fun pick(light: Long, dark: Long) = Color(if (Theme.dark) dark else light)
private val Bg get() = pick(0xFFECE9FA, 0xFF12142B)
private val Blob get() = pick(0xFFE3DEF7, 0xFF1A1D3D)
private val Card get() = pick(0xFFF8F7FD, 0xFF1E2246)
private val Raised get() = pick(0xFFFFFFFF, 0xFF282D58)
private val Line get() = pick(0xFFDCD7F2, 0xFF353B6B)
private val Ink get() = pick(0xFF161B3D, 0xFFECEBFA)
private val Muted get() = pick(0xFF555C7D, 0xFFA6ABCB)
private val Accent get() = pick(0xFF2E5BE0, 0xFF93ADFF)
private val Ok get() = pick(0xFF18794E, 0xFF5BD18B)
private val Bad get() = pick(0xFFB42318, 0xFFFF7A6B)
private val Blue = Color(0xFF3D7BF7)
private val Violet = Color(0xFF7A4CF0)
private val QrInk = Color(0xFF161B3D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.load(this)
        QuickShare.start(this)
        setContent { DropScreen() }
        // Turned on before and the process was gone (an update, a reboot): bring it back.
        if (Background.isOn(this)) Background.start(this)
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { super.onStop(); visible = false }

    /** Closed with Back and not receiving in background: stop listening, as the README promises. */
    override fun onDestroy() {
        if (isFinishing && !Background.isOn(this)) {
            Drop.stop()
            QuickShare.stop()
        }
        super.onDestroy()
    }

    companion object {
        /** On screen now: the background service doesn't bring it up again. */
        @Volatile var visible = false
    }
}

@Composable
private fun DropScreen() {
    val first = remember { Drop.clearFailure(); Drop.start() }
    // "New address" bumps this, so the QR changes at once, not at the next poll.
    var renewed by remember { mutableIntStateOf(0) }
    // Asked again every few seconds: a network that comes up late or a new IP reaches the QR.
    val address by produceState(first, renewed) { while (true) { value = withContext(Dispatchers.IO) { Drop.start() }; delay(5_000) } }
    val url = address
    val state by Drop.state.collectAsStateWithLifecycle()
    // The screen stays on while something comes in, not all the time: a still QR burns into OLED screens.
    val view = LocalView.current
    val busy = state is DropState.Receiving || state is DropState.Incoming
    LaunchedEffect(busy) { view.keepScreenOn = busy }
    val actionFocus = remember { FocusRequester() }
    var support by remember { mutableStateOf(false) }
    val supportFocus = remember { FocusRequester() }
    val idle = state is DropState.Waiting || state is DropState.Connected || state is DropState.Failed
    val update by Update.available.collectAsStateWithLifecycle()
    val updating by Update.progress.collectAsStateWithLifecycle()
    val updateError by Update.error.collectAsStateWithLifecycle()
    val awaitingPermission by Update.awaitingPermission.collectAsStateWithLifecycle()
    // On start and then hourly: a TV left on with Dropyk open still hears about a release; check() itself waits CHECK_MS between asks.
    LaunchedEffect(Unit) { while (true) { Update.check(); delay(60 * 60_000L) } }

    if (support && idle) {
        BackHandler { support = false }
        SupportScreen(onClose = { support = false })
        return
    }
    // Back on a received file, link or text means "done": back to the QR, not out of the app (and its server).
    BackHandler(state is DropState.Received || state is DropState.Link || state is DropState.Text) { Drop.dismiss() }

    Room {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
            Header(receiving = state is DropState.Receiving)
            Text(stringResource(R.string.tagline), color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                when {
                    url == null -> Panel { Text(stringResource(R.string.no_network), color = Ink, fontSize = 22.sp) }
                    idle -> Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(18.dp)) { QrCode(url, 220.dp) }
                        Panel(Modifier.weight(1f)) {
                            Text(stringResource(R.string.step1), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.step2), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                            Text(stringResource(R.string.url_hint), color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 26.dp))
                            Text(url, color = Accent, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                            Box(Modifier.padding(top = 14.dp)) {
                                Action(stringResource(R.string.new_code), null) { Drop.newToken(); renewed++ }
                            }
                        }
                    }
                    else -> Transfer(state, actionFocus)
                }
            }

            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    val pct = updating
                    when {
                        pct != null -> Status(stringResource(R.string.update_downloading, (pct * 100).toInt())) { Spinner() }
                        awaitingPermission -> Status(stringResource(R.string.update_permission)) { Dot(Accent) }
                        updateError != null -> Status(stringResource(R.string.update_failed, updateError.orEmpty()), color = Bad) { Dot(Bad) }
                        else -> when (val s = state) {
                        DropState.Waiting -> if (url != null) Status(stringResource(R.string.waiting)) { Spinner() }
                        DropState.Connected -> Status(stringResource(R.string.connected)) { Dot(Ok) }
                        is DropState.Failed -> Status(stringResource(R.string.failed, s.name), color = Bad) { Dot(Bad) }
                        else -> Unit
                    }
                    }
                }
                val release = update
                if (idle && release != null && updating == null) {
                    Action(stringResource(R.string.update_to, release.version), null) { Update.start(release) }
                    Box(Modifier.width(14.dp))
                } else if (idle && updating == null) {
                    // null: not asked yet; then checking, up to date or no answer.
                    var asked by remember { mutableStateOf<Int?>(null) }
                    val scope = rememberCoroutineScope()
                    Action(stringResource(asked ?: R.string.check_updates), null) {
                        if (asked == R.string.checking) return@Action
                        asked = R.string.checking
                        scope.launch { asked = if (Update.check(force = true)) R.string.up_to_date else R.string.check_failed }
                    }
                    Box(Modifier.width(14.dp))
                }
                if (idle) {
                    val context = LocalContext.current
                    var background by remember { mutableStateOf(Background.isOn(context)) }
                    Action(stringResource(if (background) R.string.background_on else R.string.background_off), null) {
                        background = !background
                        Background.set(context, background)
                        if (background && !Background.canPopUp(context)) Background.askToPopUp(context)
                    }
                    Box(Modifier.width(14.dp))
                    Action(stringResource(if (Theme.dark) R.string.theme_dark else R.string.theme_light), null) { Theme.set(context, !Theme.dark) }
                    Box(Modifier.width(14.dp))
                }
                if (idle) Action(stringResource(R.string.support), supportFocus, heart = true) { support = true }
            }
        }
    }
    if (idle) FocusOnShow(supportFocus, support)

    // Something arrived: focus jumps to the action — the person is holding a remote now.
    if (state is DropState.Received || state is DropState.Link || state is DropState.Text || state is DropState.Incoming) FocusOnShow(actionFocus, state)
    // Cancel is focused once per file, not on every progress update.
    (state as? DropState.Receiving)?.let { if (it.cancel != null) FocusOnShow(actionFocus, "receiving " + it.name) }
}

/** Everything that isn't the QR: progress, the received file with its actions, a link. */
@Composable
private fun Transfer(state: DropState, actionFocus: FocusRequester) {
    val context = LocalContext.current
    when (val s = state) {
        is DropState.Receiving -> Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.receiving, s.name, (s.done * 100 / s.total.coerceAtLeast(1)).toInt(), size(s.total)), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Line)) {
                Box(Modifier.fillMaxWidth(s.done.toFloat() / s.total.coerceAtLeast(1)).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Brush.horizontalGradient(listOf(Blue, Violet))))
            }
            s.cancel?.let { cancel -> Box(Modifier.padding(top = 20.dp)) { Action(stringResource(R.string.cancel), actionFocus) { cancel() } } }
        }
        is DropState.Text -> Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.text_title), color = Muted, fontSize = 16.sp)
            // Big, so a password or a code reads from the sofa.
            Text(s.text, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 6, modifier = Modifier.padding(top = 6.dp))
            Box(Modifier.padding(top = 20.dp)) { Action(stringResource(R.string.done), actionFocus) { Drop.dismiss() } }
        }
        is DropState.Received -> Panel(Modifier.fillMaxWidth()) { Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.mime.startsWith("image/")) Preview(s.uri)
            Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Dot(Ok)
                Text(stringResource(R.string.received, s.name, s.count), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 20.dp)) {
                when {
                    s.mime.startsWith("video/") || s.mime.startsWith("audio/") ->
                        Action(stringResource(R.string.watch), actionFocus) { watch(context, s.path, s.name, s.mime) }
                    s.mime == Drop.APK -> Action(stringResource(R.string.install), actionFocus) { install(context, s.uri, s.name) }
                    s.mime.startsWith("image/") -> Unit
                    else -> Action(stringResource(R.string.open), actionFocus) { openFile(context, s.uri, s.name, s.mime) }
                }
                Action(stringResource(R.string.done), if (s.mime.startsWith("image/")) actionFocus else null) { Drop.dismiss() }
            }
            Text(stringResource(R.string.saved_to, s.folder), color = Muted, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
            }
        } }
        is DropState.Incoming -> Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.incoming_from, s.device), color = Muted, fontSize = 16.sp)
            Text(
                if (s.count > 1) pluralStringResource(R.plurals.incoming_files, s.count - 1, s.what, s.count - 1) else s.what,
                color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, modifier = Modifier.padding(top = 4.dp),
            )
            Text(stringResource(R.string.incoming_pin, s.pin), color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 20.dp)) {
                Action(stringResource(R.string.accept), actionFocus) { s.accept() }
                Action(stringResource(R.string.decline), null) { s.decline() }
            }
        }
        is DropState.Link -> Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.link_title), color = Muted, fontSize = 16.sp)
            Text(s.url, color = Accent, fontSize = 22.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 20.dp)) {
                Action(stringResource(R.string.open_link), actionFocus) {
                    openUri(context, Intent(Intent.ACTION_VIEW, s.url.toUri()))
                    Drop.dismiss()
                }
                Action(stringResource(R.string.done), null) { Drop.dismiss() }
            }
        }
        else -> Unit
    }
}

/** The lavender background with two soft circles in the corners, drawn once — no animation. */
@Composable
private fun Room(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Bg).drawBehind {
            drawCircle(Blob, radius = size.height * 0.42f, center = Offset(size.width * 0.97f, size.height * 0.02f))
            drawCircle(Blob, radius = size.height * 0.30f, center = Offset(size.width * 0.01f, size.height * 1.02f))
        },
    ) { content() }
}

/** One line: droplet, Dropyk, a slash, what this screen is for. */
@Composable
private fun Header(receiving: Boolean, title: String = stringResource(R.string.send_title)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Droplet(receiving)
        Text("Dropyk", color = Ink, fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp))
        Text("/", color = Muted.copy(alpha = 0.6f), fontSize = 34.sp, modifier = Modifier.padding(horizontal = 18.dp))
        Text(title, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Card).padding(horizontal = 36.dp, vertical = 30.dp)) { content() }
}

/**
 * Focus [requester] once [key] changes. When a screen swaps, Compose hands focus to the new button
 * before the button listens for focus events, so it stays drawn unfocused (seen on the TV box).
 * Clearing and requesting again a frame later sends a focus event the button does see.
 */
@Composable
private fun FocusOnShow(requester: FocusRequester, key: Any) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(key) {
        withFrameNanos { }
        focusManager.clearFocus(force = true)
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun Status(text: String, color: Color = Ink, icon: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon()
        Text(text, color = color, fontSize = 20.sp, maxLines = 2)
    }
}

@Composable
private fun Dot(color: Color) = Box(Modifier.size(12.dp).clip(CircleShape).background(color))

/** A ring of dots turning while the TV waits — only then: nothing is being received yet. */
@Composable
private fun Spinner() {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "turn",
    )
    Canvas(Modifier.size(28.dp).graphicsLayer { rotationZ = turn }) {
        val r = size.minDimension / 2 - 3.dp.toPx()
        for (i in 0 until 10) {
            val a = Math.toRadians(i * 36.0)
            drawCircle(
                Brush.linearGradient(listOf(Blue, Violet)), radius = 2.4.dp.toPx(), alpha = 0.25f + 0.75f * i / 9f,
                center = Offset(center.x + r * kotlin.math.cos(a).toFloat(), center.y + r * kotlin.math.sin(a).toFloat()),
            )
        }
    }
}

/**
 * White pill with a hairline and accent text, so it reads as a button on a card too; focused it
 * fills blue — the remote's position is never in doubt.
 */
@Composable
private fun Action(label: String, focus: FocusRequester?, heart: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(22.dp)),
        colors = ButtonDefaults.colors(containerColor = Raised, contentColor = Accent, focusedContainerColor = Blue, focusedContentColor = Color.White),
        border = ButtonDefaults.border(border = Border(BorderStroke(1.dp, Line), shape = RoundedCornerShape(22.dp))),
        contentPadding = PaddingValues(horizontal = 21.dp, vertical = 10.dp),
    ) {
        if (heart) Heart(Modifier.padding(end = 9.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** An outlined heart in the button's text color. */
@Composable
private fun Heart(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(18.dp, 17.dp)) {
        val w = size.width; val h = size.height
        val heart = Path().apply {
            moveTo(w / 2, h * 0.92f)
            cubicTo(w * 0.1f, h * 0.62f, -w * 0.02f, h * 0.28f, w * 0.26f, h * 0.1f)
            cubicTo(w * 0.4f, h * 0.02f, w * 0.48f, h * 0.12f, w / 2, h * 0.22f)
            cubicTo(w * 0.52f, h * 0.12f, w * 0.6f, h * 0.02f, w * 0.74f, h * 0.1f)
            cubicTo(w * 1.02f, h * 0.28f, w * 0.9f, h * 0.62f, w / 2, h * 0.92f)
            close()
        }
        drawPath(heart, color, style = Stroke(width = 1.8.dp.toPx(), join = StrokeJoin.Round))
    }
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
    Canvas(Modifier.size(width = 46.dp, height = 64.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
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
            modifier = Modifier.width(320.dp).heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)),
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

/** The QR matrix comes from zxing; drawing its squares on one Canvas beats making a Bitmap. */
@Composable
private fun QrCode(content: String, size: Dp) {
    val matrix = remember(content) {
        runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 25, 25, mapOf(EncodeHintType.MARGIN to 0)) }.getOrNull()
    } ?: return
    Canvas(Modifier.size(size)) {
        val cell = this.size.width / matrix.width
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            if (matrix.get(x, y)) drawRect(QrInk, Offset(x * cell, y * cell), Size(cell, cell))
        }
    }
}

@Composable
private fun size(bytes: Long): String =
    if (bytes > 1_000_000_000) stringResource(R.string.size_gb, bytes / 1e9) else stringResource(R.string.size_mb, bytes / 1e6)

/** Where donations go: the same pages as on the site and in Likhtar. */
private class Donate(val name: Int, val note: Int, val url: String)

private val KOFI = Donate(R.string.support_kofi, R.string.support_kofi_note, "https://ko-fi.com/syvyj")
private val BMC = Donate(R.string.support_bmc, R.string.support_bmc_note, "https://buymeacoffee.com/syvyj")
private val MONO = Donate(R.string.support_mono, R.string.support_mono_note, "https://send.monobank.ua/jar/5juJPj142b")

/** Three QR codes side by side: scan the one you like to pay with, the payment opens on the phone. Monobank first in Ukrainian. */
@Composable
private fun SupportScreen(onClose: () -> Unit) {
    val ukrainian = LocalConfiguration.current.locales[0].language == "uk"
    val options = if (ukrainian) listOf(MONO, KOFI, BMC) else listOf(KOFI, BMC, MONO)
    val focus = remember { FocusRequester() }
    Room {
        Column(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 40.dp)) {
            Header(receiving = false, title = stringResource(R.string.support))
            Text(stringResource(R.string.support_lead), color = Muted, fontSize = 18.sp, modifier = Modifier.padding(top = 6.dp))
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    Column(
                        Modifier.width(230.dp).clip(RoundedCornerShape(24.dp)).background(Card).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) { QrCode(option.url, 140.dp) }
                        Text(stringResource(option.name), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                        Text(stringResource(option.note), color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Action(stringResource(R.string.close), focus, onClick = onClose)
            }
        }
    }
    FocusOnShow(focus, Unit)
}


/**
 * Video and audio go to the player as a file path, the way file managers open them. Handed the
 * content:// link instead, every player on a Realtek Google TV box (Just Player, Vimu, the file
 * manager's own) played the sound of a phone video with no picture; by path the same file shows.
 * A player without storage access can't open a path — that's the price.
 */
private fun watch(context: Context, path: String, name: String, mime: String) {
    openUri(context, Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(java.io.File(path)), mime), name)
}

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
        openUri(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
        return
    }
    openFile(context, uri, name, Drop.APK)
}
