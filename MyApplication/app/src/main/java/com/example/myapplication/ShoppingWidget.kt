package com.example.myapplication

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.color.ColorProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.File

class ShoppingWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val shopName = prefs[stringPreferencesKey("nearest_shop_enum")]
            val cardId = prefs[stringPreferencesKey("nearest_shop_id")]
            val lastUpdate = prefs[longPreferencesKey("last_update_time")] ?: 0L

            // Цвета: Светлая тема - белый/черный, Темная - темно-серый/белый
            val backColor = ColorProvider(day = Color.White, night = Color(0xFF1C1B1F))
            val textColor = ColorProvider(day = Color.Black, night = Color.White)

            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(backColor)
                    .clickable(actionRunCallback<StartShoppingAction>()),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (shopName != null && cardId != null) {
                    val shop = try { Shop.valueOf(shopName) } catch (e: Exception) { null }

                    Text(
                        text = shop?.displayName ?: shopName,
                        style = TextStyle(color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    )

                    Spacer(GlanceModifier.height(8.dp))

                    // ЛОГИКА: Только картинка для Бедронки
                    val bitmap = if (shop == Shop.BIEDRONKA) {
                        val savedFile = File(context.filesDir, "biedronka_$cardId.png")
                        if (savedFile.exists()) {
                            BitmapFactory.decodeFile(savedFile.absolutePath)
                        } else {
                            null // НЕ генерируем код, если картинки нет
                        }
                    } else {
                        // Для остальных (Lidl, Stokrotka и т.д.) генерируем стандартно
                        shop?.let { generateWidgetBarcode(cardId, it.barcodeFormat) }
                    }

                    if (bitmap != null) {
                        Image(
                            provider = ImageProvider(bitmap),
                            contentDescription = "Barcode_$lastUpdate",
                            modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                        )
                    } else {
                        // Если это Бедронка и картинки нет — выводим предупреждение
                        Text(
                            text = if (shop == Shop.BIEDRONKA) "Загрузите карту в приложении" else "Ошибка штрих-кода",
                            style = TextStyle(color = ColorProvider(Color.Red,Color.Red), fontSize = 12.sp)
                        )
                    }
                } else {
                    Text(
                        text = "Магазины не найдены",
                        style = TextStyle(color = ColorProvider(Color.Gray,Color.White))
                    )
                }
            }
        }
    }

    private fun generateWidgetBarcode(text: String, format: BarcodeFormat): android.graphics.Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1", EncodeHintType.MARGIN to 1)
            val bitMatrix = MultiFormatWriter().encode(text, format, 350, 150, hints)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    pixels[y * w + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.RGB_565)
        } catch (e: Exception) { null }
    }
}

class ShoppingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingWidget()
}

class StartShoppingAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("action", "START_SHOPPING")
        }
        context.startActivity(intent)
    }
}