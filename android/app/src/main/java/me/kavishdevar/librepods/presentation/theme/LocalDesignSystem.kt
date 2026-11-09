package me.kavishdevar.librepods.presentation.theme

import androidx.compose.runtime.compositionLocalOf

enum class DesignSystem {
    Apple,
    Miuix
}

val LocalDesignSystem = compositionLocalOf {
    DesignSystem.Miuix
}
