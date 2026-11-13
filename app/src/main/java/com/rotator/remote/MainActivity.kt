package com.rotator.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rotator.remote.ui.theme.RotatorRemoteTheme
import kotlinx.coroutines.delay
import kotlin.math.atan2

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var showSplash by remember { mutableStateOf(true) }
            val viewModel: RotatorViewModel = viewModel()

            LaunchedEffect(Unit) {
                delay(15000)
                showSplash = false
            }

            RotatorRemoteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
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
                            RotatorApp(viewModel)

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
    }
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
fun RotatorApp(viewModel: RotatorViewModel) {
    val connectionState = viewModel.connectionState

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
    ) {
        Text(
            text = "Rotator Control",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF8100),
            modifier = Modifier.padding(top = 0.dp, bottom = 0.dp)
        )
        Text(
            text = "Remote module v.1a.${BuildConfig.TEST_BUILD_NUMBER}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF8100),
            modifier = Modifier.padding(bottom = 4.dp)
        )

        if (viewModel.firstName.isNotBlank() || viewModel.callsign.isNotBlank() || viewModel.antennaName.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = viewModel.firstName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100)
                )
                Text(
                    text = viewModel.antennaName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
                Text(
                    text = viewModel.callsign,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF8100),
                    textAlign = TextAlign.End
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        ConnectionBar(viewModel)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        when (connectionState) {
            RotatorViewModel.ConnectionState.DISCONNECTED, RotatorViewModel.ConnectionState.ERROR -> {
                ConnectionScreen(viewModel)
            }
            RotatorViewModel.ConnectionState.CONNECTED -> {
                ControlScreen(viewModel)
            }
            else -> {
                ConnectingScreen(connectionState)
            }
        }
    }
    
    if (viewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Error") },
            text = { Text(viewModel.errorMessage!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")
                }
            }
        )
    }
    
    if (viewModel.showStallAlert) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStallAlert() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WarningIcon(modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stall Detected", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            text = { Text("The antenna has stopped moving. Check for mechanical issues.", fontWeight = FontWeight.Bold, color = Color.Black) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissStallAlert() }) {
                    Text("CONTINUE", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            containerColor = Color(0xFFFF8100)
        )
    }

    if (connectionState == RotatorViewModel.ConnectionState.CONNECTED && !viewModel.isArduinoConnected) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WarningIcon(modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Hardware Missing", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            text = { 
                Column {
                    Text("The Arduino is not connected to the server.", fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Server IP: ${viewModel.serverIp ?: "Unknown"}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Please check the USB connection at the server location.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.requestStatus() }) {
                    Text("RETRY", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.disconnect() }) {
                    Text("DISCONNECT", fontWeight = FontWeight.Bold, color = Color.Black)
                }
            },
            containerColor = Color(0xFFFF8100)
        )
    }
}

@Composable
fun ConnectionBar(viewModel: RotatorViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = when {
                                viewModel.connectionState != RotatorViewModel.ConnectionState.CONNECTED -> Color.Gray
                                viewModel.isArduinoConnected -> Color.Green
                                else -> Color.Red
                            },
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = when (viewModel.connectionState) {
                            RotatorViewModel.ConnectionState.CONNECTED -> "Connected"
                            RotatorViewModel.ConnectionState.CONNECTING -> "Connecting..."
                            RotatorViewModel.ConnectionState.DISCOVERING -> "Discovering..."
                            RotatorViewModel.ConnectionState.ERROR -> "Connection Error"
                            RotatorViewModel.ConnectionState.DISCONNECTED -> "Not Connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (viewModel.serverIp != null) {
                        Text(
                            text = viewModel.serverIp!!,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            Button(
                onClick = {
                    if (viewModel.connectionState == RotatorViewModel.ConnectionState.CONNECTED) {
                        viewModel.disconnect()
                    } else {
                        viewModel.startDiscovery()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF8100)
                )
            ) {
                Text(
                    text = if (viewModel.connectionState == RotatorViewModel.ConnectionState.CONNECTED) "Disconnect" else "Connect",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ConnectionScreen(viewModel: RotatorViewModel) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Rotator Remote",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { viewModel.startDiscovery() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8100))
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DISCOVER ON LAN", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "- OR -", style = MaterialTheme.typography.bodyMedium)
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = viewModel.manualIp,
            onValueChange = { viewModel.manualIp = it },
            label = { Text("Server IP or Domain") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { viewModel.connectManual(viewModel.manualIp) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp),
            enabled = viewModel.manualIp.isNotBlank()
        ) {
            Icon(Icons.Filled.Phone, contentDescription = null, tint = Color(0xFFFF8100))
            Spacer(modifier = Modifier.width(8.dp))
            Text("CONNECT MANUALLY", color = Color(0xFFFF8100), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun ConnectingScreen(state: RotatorViewModel.ConnectionState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp), color = Color(0xFFFF8100))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = when (state) {
                RotatorViewModel.ConnectionState.DISCOVERING -> "Searching for server..."
                RotatorViewModel.ConnectionState.CONNECTING -> "Connecting..."
                else -> "Please wait"
            },
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
fun ControlScreen(viewModel: RotatorViewModel) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        HeadingIndicator(
            heading = viewModel.currentHeading.toFloat(),
            targetHeading = viewModel.targetHeading?.toFloat(),
            onTargetSelected = { heading -> viewModel.setTargetHeading(heading) }
        )
        
        Spacer(modifier = Modifier.height(40.dp))
        
        RotatorSlider(
            onCCW = { viewModel.startRotateCCW() },
            onCW = { viewModel.startRotateCW() },
            onStop = { viewModel.stopRotation() },
            modifier = Modifier.padding(horizontal = 32.dp).offset(y = (-45).dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        if (viewModel.targetHeading != null) {
            Text(
                text = "Antenna moving",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFFF8100),
                fontWeight = FontWeight.Black
            )
        } else if (viewModel.isRotating) {
            Text(
                text = if (viewModel.isRotatingCCW) "Rotating CCW..." else "Rotating CW...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFF8100),
                fontWeight = FontWeight.Bold
            )
        }
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
    val trackWidth = 280.dp
    val knobWidth = 100.dp
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
fun HeadingIndicator(
    heading: Float,
    targetHeading: Float? = null,
    onTargetSelected: (Int) -> Unit,
    dialSize: Dp = 280.dp
) {
    val animatedHeading by animateFloatAsState(
        targetValue = heading, 
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "heading"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dialSize)
            .background(Color.Black, shape = CircleShape)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val centerX = size.width / 2f
                    val centerY = size.height / 2f
                    val angleRad = atan2(offset.y - centerY, offset.x - centerX)
                    var angleDeg = Math.toDegrees(angleRad.toDouble()).toInt() + 90
                    if (angleDeg < 0) angleDeg += 360
                    onTargetSelected(angleDeg % 360)
                }
            }
    ) {
        val labelScale = dialSize.value / 500f
        val cardinalOffset = 210f + 30f
        val degreesScale = labelScale * (150f / 200f)
        
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

        Text("N", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (-cardinalOffset * labelScale).dp))
        Text("S", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(y = (cardinalOffset * labelScale).dp))
        Text("E", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (cardinalOffset * labelScale).dp))
        Text("W", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.offset(x = (-cardinalOffset * labelScale).dp))

        val orange = Color(0xFFFFA500)
        val degPoints = listOf(
            "0" to Offset(0f, -195f),
            "30" to Offset(100f, -170f), "60" to Offset(170f, -100f), "90" to Offset(195f, 0f),
            "120" to Offset(170f, 100f), "150" to Offset(100f, 170f), "180" to Offset(0f, 195f),
            "210" to Offset(-100f, 170f), "240" to Offset(-170f, 100f), "270" to Offset(-195f, 0f),
            "300" to Offset(-170f, -100f), "330" to Offset(-100f, -170f)
        )
        degPoints.forEach { (txt, off) ->
            Text(txt, color = orange, fontSize = 10.sp, modifier = Modifier.offset(x = (off.x * degreesScale).dp, y = (off.y * degreesScale).dp))
        }

        // Current Heading Pointer (Red)
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

        // Target Heading Pointer (Bright Green)
        targetHeading?.let { target ->
            Box(
                modifier = Modifier.fillMaxSize().rotate(target),
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
        
        Text(
            text = "${heading.toInt()}°",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center).padding(top = 85.dp)
        )
    }
}

