package com.rotator.server

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle 
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Screen-size-aware scale factor. 1.0 = baseline 360 dp short side. */
val LocalAppScale = compositionLocalOf { 1.0f }

/** Extra sp added to card/info-box text in landscape mode. */
val LocalCardTextOffset = compositionLocalOf { 0 }

class MainActivity : ComponentActivity() {

    companion object {
        val serviceState = MutableStateFlow<RotatorService.ServiceState?>(null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startRotatorService()

        setContent {
            val context = LocalContext.current
            var showSplash by remember { mutableStateOf(true) }
            var currentScreen by remember { mutableStateOf("Main") }
            val state by serviceState.collectAsState()

            val configuration = LocalConfiguration.current
            val appScale = (minOf(configuration.screenWidthDp, configuration.screenHeightDp) / 360f)
                .coerceIn(0.75f, 2.5f)

            LaunchedEffect(Unit) {
                delay(15000)
                showSplash = false
            }

            CompositionLocalProvider(LocalAppScale provides appScale) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (showSplash) showSplash = false
                            }
                    ) {
                        if (showSplash) {
                            SplashScreen()
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when (currentScreen) {
                                    "Main" -> MainContent(
                                        state = state,
                                        onNavigateToCalibrate = { currentScreen = "Calibrate" },
                                        onNavigateToSetup = { currentScreen = "Setup" },
                                        onNavigateToWorldView = { currentScreen = "WorldView" },
                                        onNavigateToGCM = { currentScreen = "GCM" }
                                    )
                                    "Calibrate" -> CalibrateScreen(state = state, onBack = { currentScreen = "Main" })
                                    "Setup" -> SetupScreen(onBack = {
                                        currentScreen = "Main"
                                        // Push updated setup data to any connected Remote clients
                                        context.sendServiceAction(RotatorService.ACTION_BROADCAST_SETUP)
                                    })
                                    "WorldView" -> WorldViewScreen(onBack = { currentScreen = "Main" })
                                    "GCM" -> GreatCircleMapScreen(onBack = { currentScreen = "Main" })
                                }

                                // Copyright footer
                                Text(
                                    text = "© 2026 Gordon McBriar GI6FZI",
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.5f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    private fun startRotatorService() {
        val serviceIntent = Intent(this, RotatorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}

fun Context.sendServiceAction(action: String, targetHeading: Int? = null) {
    val intent = Intent(this, RotatorService::class.java).apply {
        this.action = action
        targetHeading?.let { putExtra(RotatorService.EXTRA_TARGET_HEADING, it) }
    }
    startService(intent)
}

@Composable
fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Logo",
            modifier = Modifier
                .size(300.dp)
                .offset(y = (-80).dp)
        )

        Text(
            text = "© 2026 Gordon McBriar GI6FZI",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFFF8100),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun WarningIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val p1 = Offset(w / 2f, h * 0.02f)
        val p2 = Offset(w * 0.98f, h * 0.95f)
        val p3 = Offset(w * 0.02f, h * 0.95f)

        val trianglePath = Path().apply {
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
            lineTo(p3.x, p3.y)
            close()
        }

        drawPath(
            path = trianglePath,
            color = Color(0xFFBC1E22),
            style = Stroke(width = w * 0.12f, join = StrokeJoin.Round)
        )

        drawPath(
            path = trianglePath,
            color = Color(0xFFFFC107)
        )

        val s = w / 512f

        drawCircle(
            color = Color.Black,
            radius = 22f * s,
            center = Offset(256f * s, 385f * s)
        )

        val barPath = Path().apply {
            moveTo(235f * s, 150f * s)
            lineTo(277f * s, 150f * s)
            lineTo(268f * s, 335f * s)
            lineTo(244f * s, 335f * s)
            close()
        }
        drawPath(barPath, Color.Black)
    }
}

@Composable
fun MainContent(state: RotatorService.ServiceState?, onNavigateToCalibrate: () -> Unit, onNavigateToSetup: () -> Unit, onNavigateToWorldView: () -> Unit, onNavigateToGCM: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val context = LocalContext.current

    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state?.isArduinoConnected) {
        if (state != null && !state.isArduinoConnected) {
            showDialog = true
        } else if (state?.isArduinoConnected == true) {
            showDialog = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        WarningIcon(modifier = Modifier.size(62.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "Hardware Missing",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                },
                text = {
                    Text(
                        "Please connect the Arduino hardware interface.\n\nIf you are prompted to allow access to the USB click OK.",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Start
                    )
                },
                confirmButton = { },
                dismissButton = null,
                containerColor = Color(0xFFFF8100)
            )
        }

        if (!state?.errorMessage.isNullOrBlank()) {
            AlertDialog(
                onDismissRequest = { context.sendServiceAction(RotatorService.ACTION_DISMISS_ERROR) },
                title = { Text("Error") },
                text = { Text(state?.errorMessage.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = { context.sendServiceAction(RotatorService.ACTION_DISMISS_ERROR) }) {
                        Text("OK")
                    }
                }
            )
        }

        if (isLandscape) {
            LandscapeMainContent(state, onNavigateToCalibrate, onNavigateToSetup, onNavigateToWorldView, onNavigateToGCM)
        } else {
            PortraitMainContent(state, onNavigateToCalibrate, onNavigateToSetup, onNavigateToWorldView, onNavigateToGCM)
        }
    }
}

@Composable
fun PortraitMainContent(state: RotatorService.ServiceState?, onNavigateToCalibrate: () -> Unit, onNavigateToSetup: () -> Unit, onNavigateToWorldView: () -> Unit, onNavigateToGCM: () -> Unit) {
    val context = LocalContext.current
    val scale = LocalAppScale.current
    val prefs = context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE)
    val callsign    = prefs.getString("callsign",     "") ?: ""
    val firstName   = prefs.getString("first_name",   "") ?: ""
    val antennaName = prefs.getString("antenna_name", "") ?: ""
    val gridSquare  = prefs.getString("grid_square",  "") ?: ""
    val setupComplete = callsign.isNotBlank() && antennaName.isNotBlank() && gridSquare.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Version + heartbeat LED
        Row(
            modifier = Modifier.width((280 * scale).dp).align(Alignment.CenterHorizontally).padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Rotator Server",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100)
                )
                Text(
                    text = "v.1a.${BuildConfig.TEST_BUILD_NUMBER}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100)
                )
            }
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = if (state?.heartbeatToggle == true) Color.Green else Color(0xFF004400),
                        shape = CircleShape
                    )
            )
        }

        // First name (left) / Callsign (right) at 16pt, Antenna name centred at 30pt
        Row(
            modifier = Modifier.width((280 * scale).dp).align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = firstName, fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8100))
            Text(text = antennaName, fontSize = (if (antennaName.length > 14) 20 * scale else 30 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Text(text = callsign.uppercase(), fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8100), textAlign = TextAlign.End)
        }

        Spacer(modifier = Modifier.height(8.dp))

        HeadingIndicator(
            heading = (state?.currentHeading ?: 0).toFloat(),
            targetHeading = state?.targetHeading ?: state?.remoteTargetHeading,
            onTargetSelected = { heading ->
                context.sendServiceAction(RotatorService.ACTION_SET_TARGET, heading)
            },
            sizeDp = (280 * scale).toInt().coerceAtLeast(160),
            isRotating = state?.isRotating == true || state?.targetHeading != null
        )

        Spacer(modifier = Modifier.height(16.dp))

        RotatorSlider(
            onCCW = { context.sendServiceAction(RotatorService.ACTION_CCW) },
            onCW = { context.sendServiceAction(RotatorService.ACTION_CW) },
            onStop = { context.sendServiceAction(RotatorService.ACTION_STOP) },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Rotation status text
        val statusText = when {
            state?.targetHeading != null -> "Antenna moving"
            state?.isRotating == true -> if (state.isRotatingCCW) "Rotating CCW..." else "Rotating CW..."
            else -> null
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFF8100),
                fontWeight = FontWeight.Black
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Info boxes constrained to scrubber width, centred
        Column(modifier = Modifier
            .width((280 * scale).dp)
            .align(Alignment.CenterHorizontally)
        ) {
            StationInfoCard()
            Spacer(modifier = Modifier.height(6.dp))
            StatusCard(state)
            Spacer(modifier = Modifier.height(6.dp))
            NetworkCard(state)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    LogCard(state?.lastMessage)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToWorldView,
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
                ) {
                    Text("World view", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToGCM,
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
                ) {
                    Text("GCM", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToSetup,
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (setupComplete) Color(0xFFFF8100) else Color(0xFFD32F2F))
                ) {
                    Text("Setup", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onNavigateToCalibrate,
                    shape = MaterialTheme.shapes.extraSmall,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (state?.isCalibrated == true) Color(0xFFFF8100) else Color(0xFFD32F2F))
                ) {
                    Text("Calibrate", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** Convert a Maidenhead grid locator (4 or 6 chars) to (latitude, longitude) in degrees. */
fun maidenheadToLatLon(grid: String): Pair<Double, Double> {
    val g = grid.uppercase()
    var lon = (g[0] - 'A') * 20.0 - 180.0
    var lat = (g[1] - 'A') * 10.0 - 90.0
    lon += (g[2] - '0') * 2.0
    lat += (g[3] - '0') * 1.0
    if (g.length >= 6) {
        lon += (g[4] - 'A') * (2.0 / 24.0)
        lat += (g[5] - 'A') * (1.0 / 24.0)
        lon += 1.0 / 24.0   // centre of subsquare
        lat += 0.5 / 24.0
    } else {
        lon += 1.0           // centre of square
        lat += 0.5
    }
    return Pair(lat, lon)
}

/** Calculate the initial bearing (0–359°) from (lat1,lon1) to (lat2,lon2). */
fun bearingBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
    val φ1 = Math.toRadians(lat1)
    val φ2 = Math.toRadians(lat2)
    val Δλ = Math.toRadians(lon2 - lon1)
    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).roundToInt()
}

/**
 * Convert WGS84 lat/lon to a 4-character WAB square (e.g. "J57", "NT27").
 * Uses the Irish National Grid for Ireland/N.Ireland (lon ≤ −5°),
 * and the OS National Grid (OSGB) for Great Britain.
 */
fun latLonToWAB(lat: Double, lon: Double): String {
    val letters = arrayOf("ABCDE", "FGHJK", "LMNOP", "QRSTU", "VWXYZ")
    return try {
        val useIrish = lon <= -5.0 && lat in 51.0..55.6

        // ── WGS84 → Cartesian ──────────────────────────────────────────
        val aW = 6378137.0; val bW = 6356752.3142; val e2W = 1.0 - bW*bW/(aW*aW)
        val φ = Math.toRadians(lat); val λ = Math.toRadians(lon)
        val sinφ = sin(φ); val cosφ = cos(φ)
        val νW = aW / sqrt(1.0 - e2W * sinφ * sinφ)
        val x1 = νW * cosφ * cos(λ)
        val y1 = νW * cosφ * sin(λ)
        val z1 = (1.0 - e2W) * νW * sinφ

        val E: Double; val N: Double

        if (useIrish) {
            // ── Helmert: WGS84 → Ireland 1965 ─────────────────────────
            val s  = 1.0 + (-8.150e-6)
            val rxR = Math.toRadians(-1.042 / 3600.0)
            val ryR = Math.toRadians(-0.214 / 3600.0)
            val rzR = Math.toRadians(-0.631 / 3600.0)
            val x2 = -482.530 + s * (x1 - rzR*y1 + ryR*z1)
            val y2 =  130.596 + s * (rzR*x1 + y1 - rxR*z1)
            val z2 = -564.557 + s * (-ryR*x1 + rxR*y1 + z1)

            // ── Cartesian → lat/lon on Airy Modified ───────────────────
            val aI = 6377340.189; val bI = 6356034.447; val e2I = 1.0 - bI*bI/(aI*aI)
            val p = sqrt(x2*x2 + y2*y2)
            var φI = atan2(z2, p * (1.0 - e2I))
            repeat(10) { val ν = aI/sqrt(1.0-e2I*sin(φI)*sin(φI)); φI = atan2(z2+e2I*ν*sin(φI), p) }
            val λI = atan2(y2, x2)

            // ── Irish Transverse Mercator ───────────────────────────────
            val F0=1.000035; val φ0=Math.toRadians(53.5); val λ0=Math.toRadians(-8.0)
            val nn=(aI-bI)/(aI+bI); val nn2=nn*nn; val nn3=nn*nn*nn
            val sφ=sin(φI); val cφ=cos(φI); val tφ=tan(φI)
            val ν=aI*F0/sqrt(1.0-e2I*sφ*sφ)
            val ρ=aI*F0*(1.0-e2I)/(1.0-e2I*sφ*sφ).pow(1.5)
            val η2=ν/ρ-1.0
            val M=bI*F0*((1+nn+1.25*nn2+1.25*nn3)*(φI-φ0)-(3*nn+3*nn2+2.625*nn3)*sin(φI-φ0)*cos(φI+φ0)+(1.875*nn2+1.875*nn3)*sin(2*(φI-φ0))*cos(2*(φI+φ0))-(35.0/24.0)*nn3*sin(3*(φI-φ0))*cos(3*(φI+φ0)))
            val dλ=λI-λ0
            E = 200000.0 + ν*cφ*dλ + ν/6.0*cφ.pow(3)*(ν/ρ-tφ*tφ)*dλ.pow(3) + ν/120.0*cφ.pow(5)*(5.0-18.0*tφ*tφ+tφ.pow(4)+14.0*η2-58.0*tφ*tφ*η2)*dλ.pow(5)
            N = 250000.0 + M + ν/2.0*sφ*cφ*dλ*dλ + ν/24.0*sφ*cφ.pow(3)*(5.0-tφ*tφ+9.0*η2)*dλ.pow(4) + ν/720.0*sφ*cφ.pow(5)*(61.0-58.0*tφ*tφ+tφ.pow(4))*dλ.pow(6)

            // ── Irish Grid letter (single 100km square) ─────────────────
            val e100 = (E / 100000).toInt().coerceIn(0, 4)
            val n100 = (N / 100000).toInt().coerceIn(0, 4)
            val letter = letters[4 - n100][e100]
            val e10 = (E.rem(100000.0) / 10000).toInt()
            val n10 = (N.rem(100000.0) / 10000).toInt()
            "$letter$e10$n10"

        } else {
            // ── Helmert: WGS84 → OSGB36 ───────────────────────────────
            val s  = 1.0 + 20.4894e-6
            val rxR = Math.toRadians(-0.1502 / 3600.0)
            val ryR = Math.toRadians(-0.2470 / 3600.0)
            val rzR = Math.toRadians(-0.8421 / 3600.0)
            val x2 = -446.448 + s * (x1 - rzR*y1 + ryR*z1)
            val y2 =  125.157 + s * (rzR*x1 + y1 - rxR*z1)
            val z2 = -542.060 + s * (-ryR*x1 + rxR*y1 + z1)

            // ── Cartesian → lat/lon on Airy 1830 ───────────────────────
            val aO = 6377563.396; val bO = 6356256.910; val e2O = 1.0 - bO*bO/(aO*aO)
            val p = sqrt(x2*x2 + y2*y2)
            var φO = atan2(z2, p * (1.0 - e2O))
            repeat(10) { val ν = aO/sqrt(1.0-e2O*sin(φO)*sin(φO)); φO = atan2(z2+e2O*ν*sin(φO), p) }
            val λO = atan2(y2, x2)

            // ── British National Grid (OSGB TM) ────────────────────────
            val F0=0.9996012717; val φ0=Math.toRadians(49.0); val λ0=Math.toRadians(-2.0)
            val nn=(aO-bO)/(aO+bO); val nn2=nn*nn; val nn3=nn*nn*nn
            val sφ=sin(φO); val cφ=cos(φO); val tφ=tan(φO)
            val ν=aO*F0/sqrt(1.0-e2O*sφ*sφ)
            val ρ=aO*F0*(1.0-e2O)/(1.0-e2O*sφ*sφ).pow(1.5)
            val η2=ν/ρ-1.0
            val M=bO*F0*((1+nn+1.25*nn2+1.25*nn3)*(φO-φ0)-(3*nn+3*nn2+2.625*nn3)*sin(φO-φ0)*cos(φO+φ0)+(1.875*nn2+1.875*nn3)*sin(2*(φO-φ0))*cos(2*(φO+φ0))-(35.0/24.0)*nn3*sin(3*(φO-φ0))*cos(3*(φO+φ0)))
            val dλ=λO-λ0
            E = 400000.0 + ν*cφ*dλ + ν/6.0*cφ.pow(3)*(ν/ρ-tφ*tφ)*dλ.pow(3) + ν/120.0*cφ.pow(5)*(5.0-18.0*tφ*tφ+tφ.pow(4)+14.0*η2-58.0*tφ*tφ*η2)*dλ.pow(5)
            N = -100000.0 + M + ν/2.0*sφ*cφ*dλ*dλ + ν/24.0*sφ*cφ.pow(3)*(5.0-tφ*tφ+9.0*η2)*dλ.pow(4) + ν/720.0*sφ*cφ.pow(5)*(61.0-58.0*tφ*tφ+tφ.pow(4))*dλ.pow(6)

            // ── OSGB two-letter grid reference ─────────────────────────
            val eL = (E + 1000000.0).toLong()
            val nL = (N + 500000.0).toLong()
            val eBig = (eL / 500000).toInt().coerceIn(0, 4)
            val nBig = (nL / 500000).toInt().coerceIn(0, 4)
            val major = letters[4 - nBig][eBig]
            val e100 = ((eL % 500000) / 100000).toInt().coerceIn(0, 4)
            val n100 = ((nL % 500000) / 100000).toInt().coerceIn(0, 4)
            val minor = letters[4 - n100][e100]
            val e10 = (E.rem(100000.0) / 10000).toInt()
            val n10 = (N.rem(100000.0) / 10000).toInt()
            "$major$minor$e10$n10"
        }
    } catch (e: Exception) { "" }
}

@Composable
fun LandscapeMainContent(state: RotatorService.ServiceState?, onNavigateToCalibrate: () -> Unit, onNavigateToSetup: () -> Unit, onNavigateToWorldView: () -> Unit, onNavigateToGCM: () -> Unit) {
    val context = LocalContext.current
    val scale = LocalAppScale.current
    val prefs = context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE)
    val callsign    = prefs.getString("callsign",     "") ?: ""
    val firstName   = prefs.getString("first_name",   "") ?: ""
    val antennaName = prefs.getString("antenna_name", "") ?: ""
    val homeGrid    = prefs.getString("grid_square",  "") ?: ""
    val setupComplete = callsign.isNotBlank() && antennaName.isNotBlank() && homeGrid.isNotBlank()

    val statusText = when {
        state?.targetHeading != null -> "Antenna moving"
        state?.isRotating == true -> if (state.isRotatingCCW) "Rotating CCW..." else "Rotating CW..."
        else -> null
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .padding(bottom = 24.dp)
    ) {
        // Left: compass dial — centered in its space
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.45f),
            contentAlignment = Alignment.Center
        ) {
            HeadingIndicator(
                heading = (state?.currentHeading ?: 0).toFloat(),
                targetHeading = state?.targetHeading ?: state?.remoteTargetHeading,
                onTargetSelected = { heading ->
                    context.sendServiceAction(RotatorService.ACTION_SET_TARGET, heading)
                },
                sizeDp = (458 * scale).toInt().coerceAtLeast(200),
                showCardinals = false,
                isRotating = state?.isRotating == true || state?.targetHeading != null
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Right: header → version/LED → scrubber | (spacer) | status/network/log+buttons
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Top
        ) {
            // Version + heartbeat LED
            Row(
                modifier = Modifier.width((280 * scale).dp).align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Rotator Server  v.1a.${BuildConfig.TEST_BUILD_NUMBER}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100)
                )
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (state?.heartbeatToggle == true) Color.Green else Color(0xFF004400),
                            shape = CircleShape
                        )
                )
            }

            // First name (left) / Antenna name (centre) / Callsign (right)
            Row(
                modifier = Modifier.width((280 * scale).dp).align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = firstName,   fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8100))
                Text(text = antennaName, fontSize = (if (antennaName.length > 14) 20 * scale else 30 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                Text(text = callsign.uppercase(),    fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8100), textAlign = TextAlign.End)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Scrubber just below version number
            RotatorSlider(
                onCCW = { context.sendServiceAction(RotatorService.ACTION_CCW) },
                onCW = { context.sendServiceAction(RotatorService.ACTION_CW) },
                onStop = { context.sendServiceAction(RotatorService.ACTION_STOP) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            if (statusText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF8100),
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }

            // Push status/network/log+buttons to the bottom
            Spacer(modifier = Modifier.weight(1f))

            // Info boxes with +8sp text boost for landscape
            CompositionLocalProvider(LocalCardTextOffset provides 6) {
            // Info boxes constrained to scrubber width, centred
            Column(modifier = Modifier
                .width((280 * scale).dp)
                .align(Alignment.CenterHorizontally)
            ) {
                StationInfoCard()
                Spacer(modifier = Modifier.height(4.dp))
                StatusCard(state)
                Spacer(modifier = Modifier.height(4.dp))
                NetworkCard(state)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        LogCard(state?.lastMessage)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onNavigateToWorldView,
                        shape = MaterialTheme.shapes.extraSmall,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
                    ) {
                        Text("World view", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onNavigateToGCM,
                        shape = MaterialTheme.shapes.extraSmall,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
                    ) {
                        Text("GCM", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onNavigateToSetup,
                        shape = MaterialTheme.shapes.extraSmall,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (setupComplete) Color(0xFFFF8100) else Color(0xFFD32F2F))
                    ) {
                        Text("Setup", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onNavigateToCalibrate,
                        shape = MaterialTheme.shapes.extraSmall,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (state?.isCalibrated == true) Color(0xFFFF8100) else Color(0xFFD32F2F))
                    ) {
                        Text("Calibrate", color = Color.Black, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            } // CompositionLocalProvider
        }
    }
}

@Composable
fun CalibrateScreen(state: RotatorService.ServiceState?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Left: what calibration does and how to do it
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            CalibrateHeader()
            Spacer(modifier = Modifier.height(16.dp))
            CalibrationInstructions()
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right: the interactive controls
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            CalibrationControls(state)
        }
    }
}

@Composable
fun CalibrateHeader() {
    Text(
        text = "Antenna Calibration",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = Color(0xFFFF8100)
    )
}

@Composable
fun CalibrationInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Sets the physical CW and CCW end stops so the displayed heading " +
                    "matches the antenna's real position. Make sure it's safe to move the " +
                    "rotator before starting.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            CalibrationStep(number = "1", text = "Tap Start Calibration.")
            CalibrationStep(number = "2", text = "Jog the rotator fully counter-clockwise, then tap Set CCW Limit.")
            CalibrationStep(number = "3", text = "Jog the rotator fully clockwise, then tap Set CW Limit.")
            CalibrationStep(number = "4", text = "Tap Save to store the new limits, or Cancel to discard and keep the previous calibration.")
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFF8100).copy(alpha = 0.1f), MaterialTheme.shapes.small)
            .padding(12.dp)
    ) {
        Row {
            Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFFF8100))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Captured limits are written to the Arduino's EEPROM only when you tap " +
                    "Save, so they persist after power loss. Cancel discards any captures made " +
                    "this session and restores the previous calibration.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun CalibrationStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$number.",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF8100),
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.9f)
        )
    }
}

/**
 * Interactive calibration flow: start calibration, jog the rotator to each
 * physical end stop, capture each one, then save (or cancel). Talks to the
 * Arduino via CAL_START / CAL_CW / CAL_CCW / CAL_SAVE / CAL_CANCEL — the
 * limits are written to the Arduino's EEPROM on save, so they survive power
 * loss.
 */
@Composable
fun CalibrationControls(state: RotatorService.ServiceState?) {
    val context = LocalContext.current
    val isCalibrating = state?.isCalibrating == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isCalibrating) {
                Button(
                    onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_START) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100)),
                    enabled = state?.isArduinoConnected == true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Calibration", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (state?.isArduinoConnected != true) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Arduino not connected",
                        fontSize = 12.sp,
                        color = Color(0xFFFF5252)
                    )
                }
            } else {
                Text(
                    text = "Raw sensor position",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = state?.calRawPosition?.toString() ?: "—",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100)
                )

                Spacer(modifier = Modifier.height(12.dp))

                RotatorSlider(
                    onCCW = { context.sendServiceAction(RotatorService.ACTION_CCW) },
                    onCW = { context.sendServiceAction(RotatorService.ACTION_CW) },
                    onStop = { context.sendServiceAction(RotatorService.ACTION_STOP) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CalLimitButton(
                        label = "CCW Limit",
                        capturedValue = state?.calMinRaw,
                        onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_SET_CCW_LIMIT) },
                        modifier = Modifier.weight(1f)
                    )
                    CalLimitButton(
                        label = "CW Limit",
                        capturedValue = state?.calMaxRaw,
                        onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_SET_CW_LIMIT) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_RESET) }
                ) {
                    Text(
                        "Reset Limits to 0 / 359",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                if (!state?.calMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = state?.calMessage.orEmpty(),
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                val canSave = state?.calMinRaw != null && state?.calMaxRaw != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_CANCEL) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { context.sendServiceAction(RotatorService.ACTION_CAL_SAVE) },
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CalLimitButton(label: String, capturedValue: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (capturedValue != null) Color(0xFF2E7D32) else Color(0xFF333333)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (capturedValue != null) "$label ✓" else "Set $label",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
        if (capturedValue != null) {
            Text(
                text = "raw: $capturedValue",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun HeadingIndicator(
    heading: Float,
    targetHeading: Int? = null,
    onTargetSelected: ((Int) -> Unit)? = null,
    sizeDp: Int = 280,
    showCardinals: Boolean = true,
    isRotating: Boolean = false
) {
    val animatedHeading by animateFloatAsState(
        targetValue = heading,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "heading"
    )

    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isRotating) Color(0xFF3B0905) else Color.Black,
        animationSpec = tween(durationMillis = 400),
        label = "bg"
    )

    val dialSize: Dp = sizeDp.dp
    val labelScale = sizeDp / 500f
    val cardinalOffset = 210f + 30f
    val degreesScale = labelScale * (150f / 200f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dialSize)
            .background(bgColor, shape = CircleShape)
            .then(
                if (onTargetSelected != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val angleRad = atan2(offset.y - centerY, offset.x - centerX)
                            var angleDeg = Math.toDegrees(angleRad.toDouble()).toInt() + 90
                            if (angleDeg < 0) angleDeg += 360
                            onTargetSelected(angleDeg % 360)
                        }
                    }
                } else Modifier
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val scale = size.width / 500f
            val center = Offset(size.width / 2, size.height / 2)

            drawCircle(
                color = Color.White,
                radius = 200f * scale,
                center = center,
                style = Stroke(
                    width = 2f * scale,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * scale, 5f * scale), 0f)
                )
            )

            drawLine(
                color = Color.White,
                start = Offset(center.x, center.y - 40f * scale),
                end = Offset(center.x, center.y + 40f * scale),
                strokeWidth = 4f * scale
            )
            drawLine(
                color = Color.White,
                start = Offset(center.x - 40f * scale, center.y),
                end = Offset(center.x + 40f * scale, center.y),
                strokeWidth = 4f * scale
            )

            for (angle in 0 until 360 step 10) {
                val isMajor = angle % 30 == 0
                val tickColor = if (isMajor) Color(0xFFFFA500) else Color.White
                val tickWidth = if (isMajor) 4f * scale else 2f * scale

                rotate(angle.toFloat()) {
                    drawLine(
                        color = tickColor,
                        start = Offset(center.x, center.y - 190f * scale),
                        end = Offset(center.x, center.y - 170f * scale),
                        strokeWidth = tickWidth
                    )
                }
            }

            drawCircle(
                color = Color.Gray,
                radius = 12f * scale,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 12f * scale,
                center = center,
                style = Stroke(width = 2f * scale)
            )
        }

        if (showCardinals) {
            Text("N", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-cardinalOffset * labelScale).dp))
            Text("S", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (cardinalOffset * labelScale).dp))
            Text("E", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (cardinalOffset * labelScale).dp))
            Text("W", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (-cardinalOffset * labelScale).dp))
        }

        val orange = Color(0xFFFFA500)
        val degPoints = listOf(
            "0" to Offset(0f, -195f),
            "30" to Offset(100f, -170f), "60" to Offset(170f, -100f), "90" to Offset(195f, 0f),
            "120" to Offset(170f, 100f), "150" to Offset(100f, 170f), "180" to Offset(0f, 195f),
            "210" to Offset(-100f, 170f), "240" to Offset(-170f, 100f), "270" to Offset(-195f, 0f),
            "300" to Offset(-170f, -100f), "330" to Offset(-100f, -170f)
        )
        val appScale = LocalAppScale.current
        degPoints.forEach { (txt, off) ->
            Text(txt, color = orange, fontSize = (14 * appScale).sp, modifier = Modifier.offset(x = (off.x * degreesScale).dp, y = (off.y * degreesScale).dp))
        }

        // Current heading pointer (red)
        Box(
            modifier = Modifier.fillMaxSize().rotate(animatedHeading),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val s = size.width / 500f
                val c = Offset(size.width / 2, size.height / 2)
                val path = Path().apply {
                    moveTo(c.x, c.y - 170f * s)
                    lineTo(c.x - 12f * s, c.y)
                    lineTo(c.x + 12f * s, c.y)
                    close()
                }
                drawPath(path, Color.Red)
                drawCircle(color = Color.Red, radius = 12f * s, center = c)
            }
        }

        // Target heading pointer (green)
        targetHeading?.let { target ->
            Box(
                modifier = Modifier.fillMaxSize().rotate(target.toFloat()),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val s = size.width / 500f
                    val c = Offset(size.width / 2, size.height / 2)
                    val path = Path().apply {
                        moveTo(c.x, c.y - 170f * s)
                        lineTo(c.x - 12f * s, c.y)
                        lineTo(c.x + 12f * s, c.y)
                        close()
                    }
                    drawPath(path, Color.Green)
                    drawCircle(color = Color.Green, radius = 12f * s, center = c)
                }
            }
        }

        // Vertically centred between compass centre and top degree-number ring
        val readoutOffsetDp = -(195f * degreesScale / 2f)
        Text(
            text = "${heading.toInt()}°",
            color = Color.White,
            fontSize = (36 * appScale).sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center).offset(y = readoutOffsetDp.dp)
        )
    }
}

@Composable
fun RotatorSlider(
    onCCW: () -> Unit,
    onCW: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scale = LocalAppScale.current
    val trackWidth = (280 * scale).dp
    val knobWidth = (100 * scale).dp
    val maxOffsetPx = with(density) { (trackWidth - knobWidth).toPx() / 2f }

    var offsetX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val animatedOffsetX by animateFloatAsState(
        targetValue = if (isDragging) offsetX else 0f,
        animationSpec = if (isDragging) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "knobOffset"
    )

    LaunchedEffect(isDragging) {
        if (!isDragging) {
            offsetX = 0f
        }
    }

    var currentAction by remember { mutableStateOf<String?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Row(
            modifier = Modifier.width(trackWidth).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("CCW", color = if (currentAction == "CCW") Color(0xFFFF8100) else Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("CW", color = if (currentAction == "CW") Color(0xFFFF8100) else Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .width(trackWidth)
                .height(70.dp)
                .background(Color(0xFF333333), RoundedCornerShape(12.dp))
                .border(2.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF444444), RoundedCornerShape(8.dp))
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffsetX.toInt(), 0) }
                    .size(knobWidth, 56.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(8.dp),
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
                    .background(Color(0xFFFF8100), RoundedCornerShape(8.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = {
                                isDragging = false
                                if (currentAction != null) {
                                    onStop()
                                    currentAction = null
                                }
                            },
                            onDragCancel = {
                                isDragging = false
                                if (currentAction != null) {
                                    onStop()
                                    currentAction = null
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(-maxOffsetPx, maxOffsetPx)

                                val threshold = maxOffsetPx * 0.5f
                                if (offsetX < -threshold) {
                                    if (currentAction != "CCW") {
                                        onCCW()
                                        currentAction = "CCW"
                                    }
                                } else if (offsetX > threshold) {
                                    if (currentAction != "CW") {
                                        onCW()
                                        currentAction = "CW"
                                    }
                                } else {
                                    if (currentAction != null) {
                                        onStop()
                                        currentAction = null
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(5) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.7f)
                                .width(3.dp)
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(1.dp))
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                )
            }
        }
    }
}

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler(onBack = onBack)

    if (isLandscape) {
        LandscapeSetupScreen(onBack)
    } else {
        PortraitSetupScreen(onBack)
    }
}

@Composable
fun SetupFields(
    callsign: String, onCallsignChange: (String) -> Unit,
    firstName: String, onFirstNameChange: (String) -> Unit,
    familyName: String, onFamilyNameChange: (String) -> Unit,
    qthName: String, onQthNameChange: (String) -> Unit,
    gridSquare: String, onGridSquareChange: (String) -> Unit,
    antennaName: String, onAntennaNameChange: (String) -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFFF8100),
        focusedLabelColor = Color(0xFFFF8100),
        cursorColor = Color(0xFFFF8100),
        unfocusedBorderColor = Color(0xFF555555),
        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )
    val readOnlyColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFF555555),
        focusedLabelColor = Color.White.copy(alpha = 0.6f),
        unfocusedBorderColor = Color(0xFF333333),
        unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
        focusedTextColor = Color.White.copy(alpha = 0.7f),
        unfocusedTextColor = Color.White.copy(alpha = 0.7f)
    )

    // Derived values from grid square
    val latLon: String = if (gridSquare.length >= 4) {
        try { val (lat, lon) = maidenheadToLatLon(gridSquare); "%.4f, %.4f".format(lat, lon) }
        catch (e: Exception) { "" }
    } else ""
    val wabCalc: String = if (gridSquare.length >= 4) {
        try { val (lat, lon) = maidenheadToLatLon(gridSquare); latLonToWAB(lat, lon) }
        catch (e: Exception) { "" }
    } else ""

    // Row 1: Callsign | First Name | Family Name
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = callsign,   onValueChange = { if (it.length <= 12) onCallsignChange(it.uppercase()) },   label = { Text("Callsign")    }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
        OutlinedTextField(value = firstName,  onValueChange = { if (it.length <= 12) onFirstNameChange(it.replaceFirstChar { c -> c.uppercaseChar() }) },  label = { Text("First Name")  }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
        OutlinedTextField(value = familyName, onValueChange = { if (it.length <= 12) onFamilyNameChange(it.replaceFirstChar { c -> c.uppercaseChar() }) }, label = { Text("Family Name") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Row 2: QTH Name | Antenna Name
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = qthName,    onValueChange = { if (it.length <= 12) onQthNameChange(it.replaceFirstChar { c -> c.uppercaseChar() }) },    label = { Text("QTH Name")    }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
        OutlinedTextField(value = antennaName, onValueChange = { if (it.length <= 24) onAntennaNameChange(it.replaceFirstChar { c -> c.uppercaseChar() }) }, label = { Text("Antenna Name") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Row 3: Grid Square (editable) | WAB Square (calculated, read-only)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = gridSquare, onValueChange = { if (it.length <= 12) onGridSquareChange(it.uppercase()) }, label = { Text("Grid Square") }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors)
        OutlinedTextField(value = wabCalc, onValueChange = {}, label = { Text("WAB Square") }, singleLine = true, readOnly = true, modifier = Modifier.weight(1f), colors = readOnlyColors)
    }
    Spacer(modifier = Modifier.height(8.dp))

    // Row 4: Lat/Lon (read-only, calculated from grid square, max 18 chars)
    OutlinedTextField(value = latLon.take(18), onValueChange = {}, label = { Text("Lat_Lon") }, singleLine = true, readOnly = true, modifier = Modifier.fillMaxWidth(), colors = readOnlyColors)
    Spacer(modifier = Modifier.height(12.dp))

    // WAB grid reference notes
    Text(
        text = "The WAB square uses the Irish National Grid for Ireland/N.Ireland (lon ≤ −5°) with a full " +
               "geodetic conversion: WGS84 → Ireland 1965 datum (Helmert) → Irish Transverse Mercator " +
               "projection → letter + 10km digits. For IO74ep it will show J57. For Great Britain it " +
               "falls back to the standard OSGB National Grid two-letter system.",
        fontSize = 12.sp,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun PortraitSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE) }

    var callsign by remember { mutableStateOf(prefs.getString("callsign", "") ?: "") }
    var firstName by remember { mutableStateOf(prefs.getString("first_name", "") ?: "") }
    var familyName by remember { mutableStateOf(prefs.getString("family_name", "") ?: "") }
    var qthName by remember { mutableStateOf(prefs.getString("qth_name", "") ?: "") }
    var gridSquare by remember { mutableStateOf(prefs.getString("grid_square", "") ?: "") }
    var antennaName by remember { mutableStateOf(prefs.getString("antenna_name", "") ?: "") }

    fun save() {
        prefs.edit().apply {
            putString("callsign", callsign)
            putString("first_name", firstName)
            putString("family_name", familyName)
            putString("qth_name", qthName)
            putString("grid_square", gridSquare)
            putString("antenna_name", antennaName)
            apply()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF8100)
        )
        Spacer(modifier = Modifier.height(16.dp))

        SetupFields(
            callsign = callsign, onCallsignChange = { callsign = it; save() },
            firstName = firstName, onFirstNameChange = { firstName = it; save() },
            familyName = familyName, onFamilyNameChange = { familyName = it; save() },
            qthName = qthName, onQthNameChange = { qthName = it; save() },
            gridSquare = gridSquare, onGridSquareChange = { gridSquare = it; save() },
            antennaName = antennaName, onAntennaNameChange = { antennaName = it; save() }
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
        ) {
            Text("Back to Main", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LandscapeSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE) }

    var callsign by remember { mutableStateOf(prefs.getString("callsign", "") ?: "") }
    var firstName by remember { mutableStateOf(prefs.getString("first_name", "") ?: "") }
    var familyName by remember { mutableStateOf(prefs.getString("family_name", "") ?: "") }
    var qthName by remember { mutableStateOf(prefs.getString("qth_name", "") ?: "") }
    var gridSquare by remember { mutableStateOf(prefs.getString("grid_square", "") ?: "") }
    var antennaName by remember { mutableStateOf(prefs.getString("antenna_name", "") ?: "") }

    fun save() {
        prefs.edit().apply {
            putString("callsign", callsign)
            putString("first_name", firstName)
            putString("family_name", familyName)
            putString("qth_name", qthName)
            putString("grid_square", gridSquare)
            putString("antenna_name", antennaName)
            apply()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 24.dp)
    ) {
        // Left: title + back button
        Column(
            modifier = Modifier
                .weight(0.35f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Setup",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF8100)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
            ) {
                Text("Back to Main", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right: fields
        Column(
            modifier = Modifier
                .weight(0.65f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            SetupFields(
                callsign = callsign, onCallsignChange = { callsign = it; save() },
                firstName = firstName, onFirstNameChange = { firstName = it; save() },
                familyName = familyName, onFamilyNameChange = { familyName = it; save() },
                qthName = qthName, onQthNameChange = { qthName = it; save() },
                gridSquare = gridSquare, onGridSquareChange = { gridSquare = it; save() },
                antennaName = antennaName, onAntennaNameChange = { antennaName = it; save() }
            )
        }
    }
}

@Composable
fun StationInfoCard() {
    val context = LocalContext.current
    val offset = LocalCardTextOffset.current
    val prefs = context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE)
    val gridSquare = prefs.getString("grid_square", "") ?: ""
    val wab = if (gridSquare.length >= 4) {
        try { val (lat, lon) = maidenheadToLatLon(gridSquare); latLonToWAB(lat, lon) } catch (e: Exception) { "" }
    } else ""
    val latLon = if (gridSquare.length >= 4) {
        try { val (lat, lon) = maidenheadToLatLon(gridSquare); "%.4f, %.4f".format(lat, lon) } catch (e: Exception) { "" }
    } else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Station Info",
                fontSize = (14 + offset).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            InfoRow(label = "Grid Square", value = gridSquare.ifBlank { "—" }, icon = Icons.Default.Info)
            InfoRow(label = "WAB Square",  value = wab.ifBlank  { "—" },        icon = Icons.Default.Info)
            InfoRow(label = "Lat / Lon",   value = latLon.ifBlank { "—" },      icon = Icons.Default.Info)
        }
    }
}

@Composable
fun StatusCard(state: RotatorService.ServiceState?) {
    val offset = LocalCardTextOffset.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Real-time Status",
                fontSize = (14 + offset).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            InfoRow(
                label = "Arduino interface",
                value = if (state?.isArduinoConnected == true) "CONNECTED" else "DISCONNECTED",
                icon = if (state?.isArduinoConnected == true) Icons.Default.CheckCircle else Icons.Default.Warning,
                valueColor = if (state?.isArduinoConnected == true) Color(0xFF81C784) else MaterialTheme.colorScheme.error,
                valueLarge = true
            )

            val clientCount = state?.clientCount ?: 0
            val clientVersion = state?.clientVersion ?: ""
            InfoRow(
                label = "Remote Client",
                value = if (clientCount > 0 && clientVersion.isNotEmpty()) "v.$clientVersion"
                        else if (clientCount > 0) "connected"
                        else "Listening...",
                valueColor = if (clientCount > 0) MaterialTheme.colorScheme.onSurface else Color(0xFF888888),
                icon = Icons.Default.Info
            )
        }
    }
}

@Composable
fun NetworkCard(state: RotatorService.ServiceState?) {
    val offset = LocalCardTextOffset.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2C2C2E),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Network Information",
                fontSize = (14 + offset).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text("IP: ${state?.localIp ?: "Detecting..."} | TCP: ${RotatorService.TCP_SERVER_PORT} | UDP: ${RotatorService.UDP_BROADCAST_PORT}", fontSize = (11 + offset).sp)
        }
    }
}

@Composable
fun LogCard(message: String?) {
    val offset = LocalCardTextOffset.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size((12 + offset).dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = message?.takeIf { it.isNotEmpty() } ?: "No messages",
                fontSize = (11 + offset).sp,
                maxLines = 1
            )
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    icon: ImageVector,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    valueLarge: Boolean = false
) {
    val offset = LocalCardTextOffset.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size((14 + offset).dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = label, fontSize = (12 + offset).sp)
        }
        Text(
            text = value,
            fontSize = if (valueLarge) (14 + offset).sp else (14 + offset).sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

@Composable
fun WorldViewScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE)
    val gridSquare = (prefs.getString("grid_square", "") ?: "").uppercase()

    val (homeLat, homeLon) = if (gridSquare.length >= 4) {
        try { maidenheadToLatLon(gridSquare) } catch (e: Exception) { Pair(51.5, -0.1) }
    } else Pair(20.0, 0.0)

    val worldMap = ImageBitmap.imageResource(R.drawable.world_map)

    var viewLon by remember { mutableStateOf(homeLon.toFloat()) }
    var viewLat by remember { mutableStateOf(homeLat.toFloat()) }
    var scale   by remember { mutableStateOf(1f) }
    var ready   by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A2E))
                .onSizeChanged { s ->
                    if (!ready && s.width > 0 && s.height > 0) {
                        // Fit the whole world (360° x 180°) on screen, centred
                        scale = minOf(s.width / 360f, s.height / 180f)
                        viewLon = 0f
                        viewLat = 0f
                        ready = true
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.4f, 80f)
                        viewLon -= pan.x / scale
                        viewLat += pan.y / scale
                    }
                }
        ) {
            val W = size.width
            val H = size.height
            if (W == 0f || H == 0f) return@Canvas

            fun lonToX(lon: Double) = (W / 2f + (lon - viewLon) * scale).toFloat()
            fun latToY(lat: Double) = (H / 2f - (lat - viewLat) * scale).toFloat()

            val vW = (viewLon - W / (2f * scale)).toDouble().coerceAtLeast(-180.0)
            val vE = (viewLon + W / (2f * scale)).toDouble().coerceAtMost(180.0)
            val vS = (viewLat - H / (2f * scale)).toDouble().coerceAtLeast(-90.0)
            val vN = (viewLat + H / (2f * scale)).toDouble().coerceAtMost(90.0)

            // ── Grid lines (background layer) ──────────────────────────────

            // Square grid lines (visible only when zoomed in enough to be readable)
            if (scale >= 20f) {
                val sW = (floor(vW / 2) * 2).toInt()
                val sE = (ceil (vE / 2) * 2).toInt()
                val sS = floor(vS).toInt()
                val sN = ceil (vN).toInt()
                var ln = sW
                while (ln <= sE) {
                    if (ln % 20 != 0) drawLine(Color(0x2EFF8100),
                        Offset(lonToX(ln.toDouble()), latToY(vS)),
                        Offset(lonToX(ln.toDouble()), latToY(vN)), strokeWidth = 0.8f)
                    ln += 2
                }
                for (lt in sS..sN) {
                    if (lt % 10 != 0) drawLine(Color(0x2EFF8100),
                        Offset(lonToX(vW), latToY(lt.toDouble())),
                        Offset(lonToX(vE), latToY(lt.toDouble())), strokeWidth = 0.8f)
                }
            }

            // Field grid lines (every 20° lon / 10° lat)
            val fw = if (scale >= 4f) 0.8f else 1.0f
            for (ln in -180..180 step 20) {
                val x = lonToX(ln.toDouble()); if (x < -4 || x > W + 4) continue
                drawLine(Color(0x55FF8100), Offset(x, latToY(vS)), Offset(x, latToY(vN)), strokeWidth = fw)
            }
            for (lt in -90..90 step 10) {
                val y = latToY(lt.toDouble()); if (y < -4 || y > H + 4) continue
                drawLine(Color(0x55FF8100), Offset(lonToX(vW), y), Offset(lonToX(vE), y), strokeWidth = fw)
            }

            // ── World map (foreground, 70% transparent so grid shows through) ──
            val mapLeft   = lonToX(-180.0)
            val mapTop    = latToY(90.0)
            val mapRight  = lonToX(180.0)
            val mapBottom = latToY(-90.0)
            val mapW = (mapRight - mapLeft).roundToInt()
            val mapH = (mapBottom - mapTop).roundToInt()
            if (mapW > 0 && mapH > 0) {
                drawImage(
                    image = worldMap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(worldMap.width, worldMap.height),
                    dstOffset = IntOffset(mapLeft.roundToInt(), mapTop.roundToInt()),
                    dstSize = IntSize(mapW, mapH),
                    alpha = 0.3f
                )
            }

            // Highlight home square
            if (gridSquare.length >= 4) {
                val hfi = gridSquare[0] - 'A'
                val hfj = gridSquare[1] - 'A'
                val hsi = gridSquare[2].digitToInt()
                val hsj = gridSquare[3].digitToInt()
                val hL = -180.0 + hfi * 20 + hsi * 2
                val hB = -90.0  + hfj * 10 + hsj
                val rx = lonToX(hL);   val ry = latToY(hB + 1)
                val rw = lonToX(hL + 2) - rx
                val rh = latToY(hB) - ry
                if (rw > 0 && rh > 0) {
                    drawRect(Color(0x40FF8100), topLeft = Offset(rx, ry), size = Size(rw, rh))
                    drawRect(Color(0xFFFF8100), topLeft = Offset(rx, ry), size = Size(rw, rh),
                        style = Stroke(width = 2.5f))
                }
            }

            // Text via native canvas
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas

                // Field labels (AA … RR) — sized in screen px (∝ scale), not density,
                // so they never outgrow their cells on high-density displays
                val fPaint = android.graphics.Paint().apply {
                    color = 0x44FF8100.toInt()
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = (scale * 1.4f).coerceIn(8f, 22f)
                    isFakeBoldText = false; isAntiAlias = true
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val fW = (floor((vW + 180) / 20) * 20 - 180).toInt()
                val fE = (ceil ((vE + 180) / 20) * 20 - 180).toInt()
                val fS = (floor((vS +  90) / 10) * 10 -  90).toInt()
                val fN = (ceil ((vN +  90) / 10) * 10 -  90).toInt()
                var fl = fW
                while (fl < fE) {
                    var fb = fS
                    while (fb < fN) {
                        if (fl in -180..159 && fb in -90..79) {
                            val fi = (fl + 180) / 20; val fj = (fb + 90) / 10
                            nc.drawText("${'A' + fi}${'A' + fj}",
                                lonToX(fl + 10.0), latToY(fb + 5.0) + fPaint.textSize / 3f, fPaint)
                        }
                        fb += 10
                    }
                    fl += 20
                }

                // Square labels (only at high zoom where a 2°×1° cell has room)
                if (scale >= 26f) {
                    val sPaint = android.graphics.Paint().apply {
                        color = 0x88FFAA44.toInt()
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = (scale * 0.45f).coerceIn(9f, 20f)
                        isFakeBoldText = false; isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    var sl = (floor(vW / 2) * 2).toInt()
                    val se = (ceil (vE / 2) * 2).toInt()
                    while (sl < se) {
                        for (sb in floor(vS).toInt() until ceil(vN).toInt()) {
                            val fi2 = (sl + 180) / 20; val fj2 = (sb + 90) / 10
                            val si  = ((sl + 180) % 20) / 2; val sj = (sb + 90) % 10
                            nc.drawText("${'A' + fi2}${'A' + fj2}$si$sj",
                                lonToX(sl + 1.0), latToY(sb + 0.5) + sPaint.textSize / 3f, sPaint)
                        }
                        sl += 2
                    }
                }

                // Home marker label
                if (gridSquare.isNotBlank()) {
                    val mPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 12f * density; isFakeBoldText = true; isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    nc.drawText(gridSquare, lonToX(homeLon), latToY(homeLat) - 14f * density, mPaint)
                }
            }

            // Home marker dot
            val hx = lonToX(homeLon); val hy = latToY(homeLat)
            drawCircle(Color(0xFFFF8100), radius = 8f, center = Offset(hx, hy))
            drawCircle(Color.White, radius = 8f, center = Offset(hx, hy), style = Stroke(2f))
        }

        // Title overlay (over the map)
        Text(
            "World View",
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF8100)
        )

        // Zoom controls + Back button
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { scale = (scale * 1.6f).coerceIn(0.4f, 80f) },
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
            ) {
                Text("+", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            Button(
                onClick = { scale = (scale / 1.6f).coerceIn(0.4f, 80f) },
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
            ) {
                Text("−", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            Button(
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
            ) {
                Text("Back", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Reproject the equirectangular world map into an azimuthal-equidistant bitmap
 * centred on (lat0,lon0). North is up; the disc edge (radius) is the antipode (180°).
 * In this projection the straight-line direction from centre to any point is the
 * initial great-circle bearing, and radius is proportional to great-circle distance.
 */
fun buildAzimuthalMap(context: Context, lat0d: Double, lon0d: Double, n: Int): ImageBitmap {
    val src = BitmapFactory.decodeResource(context.resources, R.drawable.world_map)
    val sw = src.width; val sh = src.height
    val srcPixels = IntArray(sw * sh)
    src.getPixels(srcPixels, 0, sw, 0, 0, sw, sh)

    val out = IntArray(n * n)
    val lat0 = Math.toRadians(lat0d)
    val lon0 = Math.toRadians(lon0d)
    val sinLat0 = sin(lat0); val cosLat0 = cos(lat0)
    val centre = (n - 1) / 2.0
    val rPx = n / 2.0
    val bg = 0xFF0A0A14.toInt()

    for (y in 0 until n) {
        for (x in 0 until n) {
            val dx = x - centre
            val dy = y - centre
            val dist = sqrt(dx * dx + dy * dy)
            val idx = y * n + x
            if (dist > rPx) { out[idx] = bg; continue }
            val c = (dist / rPx) * Math.PI          // angular distance 0..π
            val az = atan2(dx, -dy)                  // clockwise from north (screen y is down)
            val sinC = sin(c); val cosC = cos(c)
            val lat = asin(sinLat0 * cosC + cosLat0 * sinC * cos(az))
            val lon = lon0 + atan2(sin(az) * sinC * cosLat0, cosC - sinLat0 * sin(lat))
            var lonDeg = Math.toDegrees(lon)
            lonDeg = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            val latDeg = Math.toDegrees(lat)
            val sx = (((lonDeg + 180.0) / 360.0) * (sw - 1)).toInt().coerceIn(0, sw - 1)
            val sy = (((90.0 - latDeg) / 180.0) * (sh - 1)).toInt().coerceIn(0, sh - 1)
            out[idx] = srcPixels[sy * sw + sx]
        }
    }
    return android.graphics.Bitmap
        .createBitmap(out, n, n, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

@Composable
fun GreatCircleMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("rotator_setup", Context.MODE_PRIVATE)
    val gridSquare = (prefs.getString("grid_square", "") ?: "").uppercase()

    val (homeLat, homeLon) = if (gridSquare.length >= 4) {
        try { maidenheadToLatLon(gridSquare) } catch (e: Exception) { Pair(51.5, -0.1) }
    } else Pair(20.0, 0.0)

    val state by MainActivity.serviceState.collectAsState()
    val heading = state?.currentHeading
    val targetHeading = state?.targetHeading ?: state?.remoteTargetHeading

    BackHandler { onBack() }

    // Heavy reprojection runs once off the main thread
    val azMap by produceState<ImageBitmap?>(initialValue = null, gridSquare) {
        value = withContext(Dispatchers.Default) {
            buildAzimuthalMap(context, homeLat, homeLon, 640)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A14))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // Bearing from centre (N up, clockwise) → set rotator target
                        val dx = offset.x - size.width / 2f
                        val dy = offset.y - size.height / 2f
                        var deg = Math.toDegrees(atan2(dx, -dy).toDouble()).toInt()
                        if (deg < 0) deg += 360
                        context.sendServiceAction(RotatorService.ACTION_SET_TARGET, deg % 360)
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = (minOf(size.width, size.height) / 2f) * 0.90f

            // Reprojected world map, fit to the disc
            azMap?.let { img ->
                val d = (r * 2f).roundToInt()
                drawImage(
                    image = img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()),
                    dstSize = IntSize(d, d)
                )
            }

            // Range rings — disc edge (r) = 180° (antipode); each ring = 30° of arc ≈ 3336 km
            for (deg in intArrayOf(30, 60, 90, 120, 150)) {
                drawCircle(Color(0x44FFFFFF), radius = r * (deg / 180f),
                    center = Offset(cx, cy), style = Stroke(1f))
            }
            drawCircle(Color(0xFFFF8100), radius = r, center = Offset(cx, cy), style = Stroke(2.5f))

            // Bearing radials every 30°
            for (az in 0 until 360 step 30) {
                val a = Math.toRadians(az.toDouble())
                drawLine(Color(0x44FFFFFF), Offset(cx, cy),
                    Offset(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat()),
                    strokeWidth = 1f)
            }

            // Target heading pointer (green) — inverted: point at centre, base at rim
            targetHeading?.let { t ->
                val a = Math.toRadians(t.toDouble())
                val w = 12f
                val rimX = cx + (r * sin(a)).toFloat()
                val rimY = cy - (r * cos(a)).toFloat()
                val ptr = Path().apply {
                    moveTo(cx, cy)                                                   // point at centre
                    lineTo(rimX + (w * cos(a)).toFloat(), rimY + (w * sin(a)).toFloat())
                    lineTo(rimX - (w * cos(a)).toFloat(), rimY - (w * sin(a)).toFloat())
                    close()
                }
                drawPath(ptr, Color.Green)
            }

            // Current rotator heading — red transparent wedge (3° spread)
            heading?.let { h ->
                val half = 1.5
                val a0 = Math.toRadians(h - half)
                val a1 = Math.toRadians(h + half)
                val beam = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + (r * sin(a0)).toFloat(), cy - (r * cos(a0)).toFloat())
                    lineTo(cx + (r * sin(a1)).toFloat(), cy - (r * cos(a1)).toFloat())
                    close()
                }
                drawPath(beam, Color(0x66FF0000))
                // Centre-line of the beam for precision
                val ac = Math.toRadians(h.toDouble())
                drawLine(Color(0xCCFF0000), Offset(cx, cy),
                    Offset(cx + (r * sin(ac)).toFloat(), cy - (r * cos(ac)).toFloat()),
                    strokeWidth = 1.5f)
            }

            // Centre marker (QTH)
            drawCircle(Color(0xFFFF8100), radius = 7f, center = Offset(cx, cy))
            drawCircle(Color.White, radius = 7f, center = Offset(cx, cy), style = Stroke(2f))

            // Bearing labels around the rim
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                val p = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 13f * density; isAntiAlias = true; isFakeBoldText = true
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                for (az in 0 until 360 step 30) {
                    val a = Math.toRadians(az.toDouble())
                    val lr = r + 16f * density
                    val lx = cx + (lr * sin(a)).toFloat()
                    val ly = cy - (lr * cos(a)).toFloat() + p.textSize / 3f
                    val lbl = when (az) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> az.toString() }
                    nc.drawText(lbl, lx, ly, p)
                }

                // Digital heading readout — centred between the "E" rim label and the right edge
                heading?.let { h ->
                    val midX = (cx + r + 30f * density + size.width) / 2f
                    val capPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 12f * density; isAntiAlias = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    val valPaint = android.graphics.Paint().apply {
                        color = 0xFFFF8100.toInt()
                        textAlign = android.graphics.Paint.Align.CENTER
                        textSize = 30f * density; isAntiAlias = true; isFakeBoldText = true
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    nc.drawText("HEADING", midX, cy - 16f * density, capPaint)
                    nc.drawText("%03d°".format(h), midX, cy + valPaint.textSize / 3f, valPaint)
                }
            }
        }

        // Title + QTH overlay
        Column(modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Text("Great Circle Map", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = Color(0xFFFF8100))
            Text(if (gridSquare.isNotBlank()) gridSquare else "Grid square not set",
                color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }

        if (azMap == null) {
            Text("Generating map…", modifier = Modifier.align(Alignment.Center),
                color = Color.White, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onBack,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
        ) {
            Text("Back", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
