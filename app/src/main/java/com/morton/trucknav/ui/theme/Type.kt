package com.morton.trucknav.ui.theme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
)
val Typography = Typography(
    displayLarge = type(56,64), displayMedium = type(44,52), displaySmall = type(36,44),
    headlineLarge = type(32,40), headlineMedium = type(28,36), headlineSmall = type(24,32),
    titleLarge = type(24,30,FontWeight.Medium), titleMedium = type(20,26,FontWeight.Medium),
    titleSmall = type(18,24,FontWeight.Medium),
    bodyLarge = type(16,22), bodyMedium = type(16,22), bodySmall = type(16,22),
    labelLarge = type(16,20,FontWeight.Medium), labelMedium = type(14,18,FontWeight.Medium),
    labelSmall = type(14,18),
)
