package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.pdf417.encoder.Dimensions
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// Alias to avoid conflict with Material Text
import com.google.mlkit.vision.text.Text as MLText

enum class Shop(val displayName: String, val color: Color, val logo: String, val barcodeFormat: BarcodeFormat) {
    BIEDRONKA("Biedronka", Color(0xFFE30613), "B", BarcodeFormat.PDF_417),
    LIDL("Lidl Plus", Color(0xFF0050AA), "L", BarcodeFormat.QR_CODE),
    ZABKA("Żappka", Color(0xFF00A650), "Ż", BarcodeFormat.QR_CODE),
    STOKROTKA("Stokrotka", Color(0xFF008C45), "S", BarcodeFormat.QR_CODE)
}

data class ShopCard(
    val id: String,
    val shop: Shop,
    val extraInfo: String = ""
)

data class Receipt(
    val id: String = UUID.randomUUID().toString(),
    val shop: Shop,
    val amount: Double,
    val date: Long = System.currentTimeMillis()
)

data class ShopLocation(
    val shop: Shop,
    val lat: Double,
    val lon: Double
)

class MainActivity : ComponentActivity() {
    fun checkGpsSettingsAndStartService(context: Context) {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)

        val client = LocationServices.getSettingsClient(context)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            // Если GPS включен — запускаем сервис
            startTrackingService(context)
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    // Это ВАЖНО: открывает системное диалоговое окно с кнопкой "ОК"
                    exception.startResolutionForResult(context as Activity, 1001)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Log.e("GPS", "Ошибка вызова окна: ${sendEx.message}")
                }
            }
        }
    }
    private var startShoppingRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        startShoppingRequested = intent?.getStringExtra("action") == "START_SHOPPING"

        setContent {
            VaultTheme {
                MainScreen(startShoppingRequested)
            }
        }
    }
}

@Composable
fun VaultTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color.White,
        background = Color.Black,
        surface = Color(0xFF1C1C1E),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@Composable
fun MainScreen(startShoppingRequested: Boolean) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var nearestShopCard by remember { mutableStateOf<ShopCard?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions: Map<String, Boolean> ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            findNearestShop(context) { shopCard ->
                nearestShopCard = shopCard
            }
        }
    }

    LaunchedEffect(startShoppingRequested) {
        if (startShoppingRequested) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                findNearestShop(context) { shopCard ->
                    nearestShopCard = shopCard
                }
            } else {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF1C1C1E)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Cards") },
                    label = { Text("Cards") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Menu, contentDescription = "History") },
                    label = { Text("History") }
                )
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                VaultScreen()
            } else {
                HistoryScreen()
            }
        }

        nearestShopCard?.let { card ->
            BarcodeDialog(card = card, onDismiss = { nearestShopCard = null })
        }
    }
}



fun findNearestShop(context: Context, onResult: (ShopCard?) -> Unit) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    try {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val shops = loadShopsFromGeoJson(context)
                var minDistance = Double.MAX_VALUE
                var nearestShop: Shop? = null
                
                for (shopLoc in shops) {
                    val dist = calculateDistance(location.latitude, location.longitude, shopLoc.lat, shopLoc.lon)
                    if (dist < minDistance) {
                        minDistance = dist
                        nearestShop = shopLoc.shop
                    }
                }
                
                if (nearestShop != null) {
                    val sharedPreferences = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
                    val cards = loadCards(sharedPreferences, Gson())
                    val card = cards.find { it.shop == nearestShop }
                    onResult(card)
                } else {
                    onResult(null)
                }
            } else {
                onResult(null)
            }
        }
    } catch (e: SecurityException) {
        onResult(null)
    }
}

fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
}

fun loadShopsFromGeoJson(context: Context): List<ShopLocation> {
    val shopLocations = mutableListOf<ShopLocation>()
    try {
        val inputStream = context.assets.open("shops.geojson")
        val size = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        val json = String(buffer, Charsets.UTF_8)
        val jsonObject = JSONObject(json)
        val features = jsonObject.getJSONArray("features")
        
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.getJSONObject("geometry")
            val type = geometry.getString("type")
            val properties = feature.getJSONObject("properties")
            
            val coords = if (type == "Point") {
                geometry.getJSONArray("coordinates")
            } else if (type == "Polygon" || type == "MultiPolygon") {
                // Simplified: take the first coordinate of the first ring
                geometry.getJSONArray("coordinates").getJSONArray(0).getJSONArray(0)
            } else continue

            val lon = coords.getDouble(0)
            val lat = coords.getDouble(1)
            
            val brand = properties.optString("brand", "").lowercase()
            val name = properties.optString("name", "").lowercase()
            
            val shop = when {
                brand.contains("biedronka") || name.contains("biedronka") -> Shop.BIEDRONKA
                brand.contains("lidl") || name.contains("lidl") -> Shop.LIDL
                brand.contains("żabka") || name.contains("zabka") -> Shop.ZABKA
                brand.contains("stokrotka") || name.contains("stokrotka") -> Shop.STOKROTKA
                else -> null
            }
            
            if (shop != null) {
                shopLocations.add(ShopLocation(shop, lat, lon))
            }
        }
    } catch (e: Exception) {
        Log.e("GeoJson", "Error loading shops.geojson", e)
    }
    return shopLocations
}

@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }
    
    var cards by remember { mutableStateOf(loadCards(sharedPreferences, gson)) }
    
    // Check if background service is running
    var isShoppingMode by remember { 
        mutableStateOf(isServiceRunning(context, LocationTrackingService::class.java)) 
    }

    LaunchedEffect(cards) {
        saveCards(cards, sharedPreferences, gson)
    }

    val locationPermissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        when {
            permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                // Разрешение получено, можно запускать
                startTrackingService(context)
            }
            else -> {
                Toast.makeText(context, "Без GPS поиск магазинов не работает", Toast.LENGTH_SHORT).show()
            }
        }
    }

// Вызывайте это при нажатии на кнопку:
//locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    var searchQuery by remember { mutableStateOf("") }
    val filteredCards = remember(cards, searchQuery) {
        if (searchQuery.isEmpty()) cards else cards.filter { it.shop.displayName.contains(searchQuery, ignoreCase = true) }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingCard by remember { mutableStateOf<ShopCard?>(null) }
    var selectedCard by remember { mutableStateOf<ShopCard?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            VaultHeader("Vault", onAddWidget = { pinWidget(context) })
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isShoppingMode) {
                        stopTrackingService(context)
                        isShoppingMode = false
                    } else {
                        // ПРОВЕРКА ПРАВ
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            // Если права есть, проверяем тумблер GPS и стартуем
                            (context as MainActivity).checkGpsSettingsAndStartService(context)
                            isShoppingMode = true
                        } else {
                            // Если прав нет — запрашиваем их
                            locationPermissionRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isShoppingMode) Color.Green.copy(alpha = 0.2f) else Color(0xFF1C1C1E),
                    contentColor = if (isShoppingMode) Color.Green else Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (isShoppingMode) Icons.Default.LocationOn else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isShoppingMode) "Shopping Mode: ACTIVE" else "Start Shopping Mode")
            }

            Spacer(modifier = Modifier.height(16.dp))
            SearchBar(query = searchQuery, onQueryChange = { searchQuery = it })
            Spacer(modifier = Modifier.height(24.dp))
            ActiveCardsInfo(filteredCards.size)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (filteredCards.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(text = if (searchQuery.isEmpty()) "No cards yet. Tap + to add one." else "No matching cards found.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    itemsIndexed(filteredCards) { _, card ->
                        ShopCardItem(card, onClick = { selectedCard = card }, onEdit = { editingCard = card }, onDelete = { cards = cards.filter { it != card } })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            containerColor = Color.White,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Card")
        }

        if (showAddDialog || editingCard != null) {
            AddCardDialog(cardToEdit = editingCard, onDismiss = { showAddDialog = false; editingCard = null }, onSave = { newCard ->
                cards = if (editingCard != null) cards.map { if (it == editingCard) newCard else it } else cards + newCard
                showAddDialog = false; editingCard = null
            })
        }

        if (selectedCard != null) {
            BarcodeDialog(card = selectedCard!!, onDismiss = { selectedCard = null })
        }
    }
}

private fun startTrackingService(context: Context) {
    val intent = Intent(context, LocationTrackingService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopTrackingService(context: Context) {
    context.stopService(Intent(context, LocationTrackingService::class.java))
}

private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

fun pinWidget(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
        val myProvider = ComponentName(context, ShoppingWidgetReceiver::class.java)

        if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
            appWidgetManager.requestPinAppWidget(myProvider, null, null)
        } else {
            Toast.makeText(context, "Direct pinning not supported. Please add widget from home screen settings.", Toast.LENGTH_LONG).show()
        }
    } else {
        Toast.makeText(context, "Direct pinning requires Android 8.0+. Please add widget manually.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }

    var receipts by remember { mutableStateOf(loadReceipts(sharedPreferences, gson)) }
    var selectedShopFilter by remember { mutableStateOf<Shop?>(null) }

    var showDialog by remember { mutableStateOf(false) }
    var receiptToEdit by remember { mutableStateOf<Receipt?>(null) }

    LaunchedEffect(receipts) {
        saveReceipts(receipts, sharedPreferences, gson)
    }

    val filteredReceipts = remember(receipts, selectedShopFilter) {
        val list = if (selectedShopFilter == null) receipts else receipts.filter { it.shop == selectedShopFilter }
        list.sortedByDescending { it.date }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            VaultHeader("History", onAddWidget = { pinWidget(context) })
            Spacer(modifier = Modifier.height(16.dp))

            ShopFilter(selectedShopFilter) { selectedShopFilter = it }

            Spacer(modifier = Modifier.height(16.dp))

            if (filteredReceipts.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("No receipts found.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredReceipts, key = { it.id }) { receipt ->
                        ReceiptItem(
                            receipt = receipt,
                            onDelete = {
                                receipts = receipts.filter { it.id != receipt.id }
                            },
                            onEdit = {
                                receiptToEdit = receipt
                                showDialog = true
                            }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                receiptToEdit = null
                showDialog = true
            },
            containerColor = Color.White,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Receipt")
        }

        if (showDialog) {
            ReceiptDialog(
                existingReceipt = receiptToEdit,
                onDismiss = { showDialog = false },
                onSave = { updatedReceipt ->
                    receipts = if (receiptToEdit == null) {
                        receipts + updatedReceipt
                    } else {
                        receipts.map { if (it.id == updatedReceipt.id) updatedReceipt else it }
                    }
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun ShopFilter(selected: Shop?, onSelect: (Shop?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.White, selectedLabelColor = Color.Black)
            )
        }
        items(Shop.entries.toList()) { shop ->
            FilterChip(
                selected = selected == shop,
                onClick = { onSelect(shop) },
                label = { Text(shop.displayName) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = shop.color, selectedLabelColor = Color.White)
            )
        }
    }
}

@Composable
fun ReceiptItem(receipt: Receipt, onDelete: () -> Unit, onEdit: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).background(receipt.shop.color, CircleShape), contentAlignment = Alignment.Center) {
                Text(receipt.shop.logo, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(receipt.shop.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(receipt.date)), color = Color.Gray, fontSize = 12.sp)
            }
            Text(String.format("%.2f zł", receipt.amount), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = Color.Gray)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { showMenu = false; onEdit() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color.Red) },
                        onClick = { showMenu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
fun ReceiptDialog(
    existingReceipt: Receipt? = null,
    onDismiss: () -> Unit,
    onSave: (Receipt) -> Unit
) {
    var selectedShop by remember { mutableStateOf(existingReceipt?.shop ?: Shop.BIEDRONKA) }
    var amountText by remember { mutableStateOf(existingReceipt?.amount?.let { String.format(Locale.US, "%.2f", it) } ?: "") }
    var isProcessing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            isProcessing = true
            processReceiptImage(context, it) { amount ->
                isProcessing = false
                if (amount != null) amountText = String.format(Locale.US, "%.2f", amount)
                else Toast.makeText(context, "Could not find total sum", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF1C1C1E)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = if (existingReceipt == null) "Add New Receipt" else "Edit Receipt",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(20.dp))

                ShopSelector(selectedShop) { selectedShop = it }

                Spacer(modifier = Modifier.height(20.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*[.,]?\\d{0,2}$"))) amountText = it },
                        label = { Text("Total Amount (PLN)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { launcher.launch("image/*") }) {
                        if (isProcessing) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                        else Icon(Icons.Default.Search, contentDescription = "Scan", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = {
                        val amount = amountText.replace(",", ".").toDoubleOrNull()
                        if (amount != null) {
                            onSave(Receipt(
                                id = existingReceipt?.id ?: UUID.randomUUID().toString(),
                                shop = selectedShop,
                                amount = amount,
                                date = existingReceipt?.date ?: System.currentTimeMillis()
                            ))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = amountText.isNotBlank() && !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    Text(if (existingReceipt == null) "Add Receipt" else "Save Changes", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun processReceiptImage(context: Context, uri: Uri, onResult: (Double?) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText: MLText ->
                var total: Double? = null
                val lines = visionText.text.split("\n")
                val markers = listOf("SUMA PLN", "SUMA", "RAZEM", "TOTAL", "DO ZAPŁATY")
                for (i in lines.indices) {
                    val line = lines[i].uppercase()
                    if (markers.any { line.contains(it) }) {
                        val searchArea = lines.subList(i, minOf(i + 3, lines.size)).joinToString(" ")
                        val regex = Regex("\\d+[.,]\\d{2}")
                        val matches = regex.findAll(searchArea)
                        val value = matches.lastOrNull()?.value?.replace(",", ".")?.toDoubleOrNull()
                        if (value != null) { total = value; break }
                    }
                }
                onResult(total)
            }
            .addOnFailureListener { onResult(null) }
    } catch (e: Exception) { onResult(null) }
}

fun loadCards(prefs: android.content.SharedPreferences, gson: Gson): List<ShopCard> {
    val json = prefs.getString("cards", null) ?: return emptyList()
    return gson.fromJson(json, object : TypeToken<List<ShopCard>>() {}.type)
}

fun saveCards(cards: List<ShopCard>, prefs: android.content.SharedPreferences, gson: Gson) = prefs.edit().putString("cards", gson.toJson(cards)).apply()

fun loadReceipts(prefs: android.content.SharedPreferences, gson: Gson): List<Receipt> {
    val json = prefs.getString("receipts", null) ?: return emptyList()
    return gson.fromJson(json, object : TypeToken<List<Receipt>>() {}.type)
}

fun saveReceipts(receipts: List<Receipt>, prefs: android.content.SharedPreferences, gson: Gson) = prefs.edit().putString("receipts", gson.toJson(receipts)).apply()

@Composable
fun VaultHeader(title: String, onAddWidget: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Row {
            IconButton(onClick = onAddWidget) {
                Icon(Icons.Default.AddCircle, contentDescription = "Add Widget", tint = Color.White)
            }
            IconButton(onClick = { /* Settings logic */ }) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Settings", tint = Color.Gray)
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(56.dp), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(28.dp)) {
        Row(modifier = Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray)
            Spacer(modifier = Modifier.width(12.dp))
            BasicTextField(value = query, onValueChange = onQueryChange, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(Color.White), decorationBox = { innerTextField -> if (query.isEmpty()) Text("Search cards...", color = Color.Gray, fontSize = 16.sp); innerTextField() })
        }
    }
}

@Composable
fun ActiveCardsInfo(count: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("$count Active Cards", color = Color.Gray, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).background(Color.Green, CircleShape)); Spacer(modifier = Modifier.width(6.dp)); Text("Location Active", color = Color.Gray, fontSize = 14.sp)
        }
    }
}

@Composable
fun ShopCardItem(card: ShopCard, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().height(100.dp).clickable { onClick() }, shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = card.shop.color)) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(44.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text(card.shop.logo, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = card.shop.color)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(card.shop.displayName, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White) }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, modifier = Modifier.background(Color(0xFF2C2C2E))) {
                    DropdownMenuItem(text = { Text("Edit", color = Color.White) }, onClick = { showMenu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete", color = Color.Red) }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun AddCardDialog(cardToEdit: ShopCard? = null, onDismiss: () -> Unit, onSave: (ShopCard) -> Unit) {
    var selectedShop by remember { mutableStateOf(cardToEdit?.shop ?: Shop.BIEDRONKA) }
    var cardId by remember { mutableStateOf(cardToEdit?.id ?: "") }
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> 
        uri?.let {
            if (selectedShop == Shop.BIEDRONKA) {
                scanAndCropBiedronka(context, it) { id ->
                    if (id != null) {
                        cardId = id
                        Toast.makeText(context, "Biedronka card detected!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No Biedronka card found in image", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val result = scanFromUri(context, it)
                if (result != null) cardId = result
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF1C1C1E) ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(if (cardToEdit != null) "Edit Card" else "Add New Card", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                Text("Select Shop", fontSize = 14.sp, color = Color.Gray); Spacer(modifier = Modifier.height(12.dp)); ShopSelector(selectedShop) { selectedShop = it }
                Spacer(modifier = Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = cardId, onValueChange = { cardId = it }, label = { Text("Card ID") }, modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(modifier = Modifier.width(8.dp)); IconButton(onClick = { launcher.launch("image/*") }) { Icon(Icons.Default.Search, contentDescription = "Scan Image", tint = Color.White) }
                }
                Spacer(modifier = Modifier.height(28.dp)); Button(onClick = { if (cardId.isNotBlank()) onSave(ShopCard(cardId, selectedShop)) }, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = cardId.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black) ) { Text(if (cardToEdit != null) "Save Changes" else "Add to Vault", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun scanAndCropBiedronka(context: Context, uri: Uri, onResult: (String?) -> Unit) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val image = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val biedronkaCode = barcodes.find { 
                    it.format == com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417 
                }

                if (biedronkaCode != null && biedronkaCode.boundingBox != null) {
                    val bounds = biedronkaCode.boundingBox!!
                    val padding = 30
                    val cropped = Bitmap.createBitmap(
                        bitmap,
                        (bounds.left - padding).coerceAtLeast(0),
                        (bounds.top - padding).coerceAtLeast(0),
                        (bounds.width() + padding * 2).coerceAtMost(bitmap.width - (bounds.left - padding).coerceAtLeast(0)),
                        (bounds.height() + padding * 2).coerceAtMost(bitmap.height - (bounds.top - padding).coerceAtLeast(0))
                    )
                    val cardId = biedronkaCode.rawValue ?: UUID.randomUUID().toString()
                    val file = File(context.filesDir, "biedronka_$cardId.png")
                    FileOutputStream(file).use { out ->
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    onResult(cardId)
                } else {
                    onResult(null)
                }
            }
            .addOnFailureListener { onResult(null) }
    } catch (e: Exception) {
        onResult(null)
    }
}

fun scanFromUri(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        result.text
    } catch (e: Exception) { null }
}

@Composable
fun ShopSelector(selected: Shop, onSelect: (Shop) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Shop.entries.forEach { shop ->
            Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(if (selected == shop) shop.color else Color(0xFF2C2C2E)).clickable { onSelect(shop) }, contentAlignment = Alignment.Center) {
                Text(shop.logo, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (selected == shop) Color.White else Color.Gray)
            }
        }
    }
}

@Composable
fun BarcodeDialog(card: ShopCard, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window; val params = window?.attributes
        val originalBrightness = params?.screenBrightness ?: -1f
        params?.screenBrightness = 1f; window?.attributes = params
        onDispose { params?.screenBrightness = originalBrightness; window?.attributes = params }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(32.dp), color = Color.White) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(card.shop.displayName, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                Spacer(modifier = Modifier.height(32.dp))
                
                val bitmap = remember(card) {
                    val savedFile = File(context.filesDir, "biedronka_${card.id}.png")
                    if (card.shop == Shop.BIEDRONKA && savedFile.exists()) {
                        BitmapFactory.decodeFile(savedFile.absolutePath)
                    } else {
                        generateBarcode(card.id, card.shop.barcodeFormat)
                    }
                }
                
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(), 
                        contentDescription = "Barcode", 
                        modifier = Modifier.fillMaxWidth().height(if (card.shop.barcodeFormat == BarcodeFormat.QR_CODE || card.shop.barcodeFormat == BarcodeFormat.PDF_417) 300.dp else 180.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None
                    )
                }
                
                Spacer(modifier = Modifier.height(32.dp)); Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.Black)) { Text("Close", color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

fun generateBarcode(text: String, format: BarcodeFormat): Bitmap? {
    if (text.isBlank()) return null
    return try {
        val cleanText = if (format == BarcodeFormat.PDF_417) {
            if (!text.contains(",")) "1,$text,,,0,0" else text
        } else {
            text.trim()
        }
        val writer = MultiFormatWriter()
        val hints = mutableMapOf<EncodeHintType, Any>().apply {
            put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            put(EncodeHintType.MARGIN, 0)
            if (format == BarcodeFormat.PDF_417) {
                put(EncodeHintType.ERROR_CORRECTION, 2)
                put(EncodeHintType.PDF417_COMPACT, false)
                put(EncodeHintType.PDF417_DIMENSIONS, Dimensions(2, 2, 12, 12))
            }
        }
        val bitMatrix = writer.encode(cleanText, format, 0, 0, hints)
        val scale = 10
        val w = bitMatrix.width * scale; val h = bitMatrix.height * scale
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val offset = y * w
            for (x in 0 until w) {
                pixels[offset + x] = if (bitMatrix.get(x / scale, y / scale)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        bitmap
    } catch (e: Exception) { null }
}
