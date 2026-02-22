package com.rotator.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RotatorApp()
                }
            }
        }
    }
}

@Composable
fun RotatorApp(viewModel: RotatorViewModel = viewModel()) {
    val connectionState = viewModel.connectionState
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top bar with connection status
        ConnectionBar(viewModel)
        
        Spacer(modifier = Modifier.height(24.dp))
        
        when (connectionState) {
            RotatorViewModel.ConnectionState.DISCONNECTED -> {
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
    
    // Error dialog
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
    
    // Stall alert
    if (viewModel.showStallAlert) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStallAlert() },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Stall Detected") },
            text = { Text("The antenna hasn't moved in 3 seconds. Check for mechanical issues.") },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.dismissStallAlert()
                    viewModel.stopRotation()
                }) {
                    Text("STOP")
                }
            }
        )
    }
}

@Composable
fun ConnectionBar(viewModel: RotatorViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (viewModel.connectionState) {
                RotatorViewModel.ConnectionState.CONNECTED -> 
                    MaterialTheme.colorScheme.primaryContainer
                RotatorViewModel.ConnectionState.ERROR -> 
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            
            if (viewModel.connectionState == RotatorViewModel.ConnectionState.CONNECTED) {
                IconButton(onClick = { viewModel.disconnect() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Disconnect")
                }
            }
        }
    }
}

@Composable
fun ConnectionScreen(viewModel: RotatorViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Rotator Controller",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = { viewModel.startDiscovery() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DISCOVER ON LAN")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "- OR -",
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = viewModel.manualIp,
            onValueChange = { viewModel.manualIp = it },
            label = { Text("Server IP or Domain") },
            placeholder = { Text("192.168.1.100 or yourshack.duckdns.org") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = { viewModel.connectManual(viewModel.manualIp) },
            modifier = Modifier.fillMaxWidth(),
            enabled = viewModel.manualIp.isNotBlank()
        ) {
            Icon(Icons.Filled.Phone, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("CONNECT MANUALLY (WAN)")
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
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
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
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Compass
        CompassView(
            heading = viewModel.currentHeading,
            isRotating = viewModel.isRotating
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Heading display
        Text(
            text = "${viewModel.currentHeading}°",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // CCW Button
            MomentaryButton(
                icon = Icons.Filled.KeyboardArrowLeft,
                label = "CCW",
                onPress = { viewModel.startRotateCCW() },
                onRelease = { viewModel.stopRotation() },
                isActive = viewModel.isRotating
            )
            
            // CW Button
            MomentaryButton(
                icon = Icons.Filled.KeyboardArrowRight,
                label = "CW",
                onPress = { viewModel.startRotateCW() },
                onRelease = { viewModel.stopRotation() },
                isActive = viewModel.isRotating
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (viewModel.isRotating) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Rotating... (Hold button to continue)")
                }
            }
        }
    }
}

@Composable
fun MomentaryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    isActive: Boolean
) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(
                color = if (isActive) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try {
                            tryAwaitRelease()
                        } catch (e: Exception) {}
                        onRelease()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(48.dp),
                tint = if (isActive) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isActive) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun CompassView(heading: Int, isRotating: Boolean) {
    // Animate the needle rotation
    val animatedHeading by animateFloatAsState(
        targetValue = heading.toFloat(),
        animationSpec = tween(
            durationMillis = 300,
            easing = FastOutSlowInEasing
        )
    )
    
    Box(
        modifier = Modifier
            .size(300.dp)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val center = Offset(canvasWidth / 2, canvasHeight / 2)
            val radius = size.minDimension / 2
            
            // Draw compass circle
            drawCircle(
                color = Color.Gray,
                radius = radius,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )
            
            // Draw cardinal directions
            val directions = listOf("N", "E", "S", "W")
            directions.forEachIndexed { index, direction ->
                val angle = (index * 90).toFloat()
                val radian = Math.toRadians(angle.toDouble())
                val x = center.x + (radius - 30.dp.toPx()) * sin(radian).toFloat()
                val y = center.y - (radius - 30.dp.toPx()) * cos(radian).toFloat()
                
                drawContext.canvas.nativeCanvas.drawText(
                    direction,
                    x,
                    y,
                    android.graphics.Paint().apply {
                        color = android.graphics.Color.BLACK
                        textSize = 40f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                )
            }
            
            // Draw degree markers
            for (degree in 0 until 360 step 10) {
                val radian = Math.toRadians(degree.toDouble())
                val startRadius = if (degree % 30 == 0) radius - 20.dp.toPx() else radius - 10.dp.toPx()
                val startX = center.x + startRadius * sin(radian).toFloat()
                val startY = center.y - startRadius * cos(radian).toFloat()
                val endX = center.x + radius * sin(radian).toFloat()
                val endY = center.y - radius * cos(radian).toFloat()
                
                drawLine(
                    color = Color.Gray,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = 2.dp.toPx()
                )
            }
            
            // Draw needle (pointing to current heading)
            rotate(animatedHeading, center) {
                // Needle shaft
                drawLine(
                    color = Color.Red,
                    start = center,
                    end = Offset(center.x, center.y - radius + 40.dp.toPx()),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
                
                // Needle tip (triangle)
                val tipSize = 20.dp.toPx()
                drawCircle(
                    color = Color.Red,
                    radius = tipSize / 2,
                    center = Offset(center.x, center.y - radius + 40.dp.toPx())
                )
            }
            
            // Center dot
            drawCircle(
                color = Color.Black,
                radius = 8.dp.toPx(),
                center = center
            )
        }
    }
}
