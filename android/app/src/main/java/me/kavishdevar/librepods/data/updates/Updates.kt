package me.kavishdevar.librepods.data.updates

import androidx.compose.runtime.Composable
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.presentation.screens.AirPodsSettingsScreenPreviewMiuixConnected
import me.kavishdevar.librepods.presentation.screens.EqualizerScreenPreviewMiuix

val update1_0_0 = listOf(
    UpdateItem(
        titleRes = R.string.material3e,
        descriptionRes = R.string.update_m3e_description,
        demoComposeable = @Composable {
            AirPodsSettingsScreenPreviewMiuixConnected()
        }
    ),
    UpdateItem(
        titleRes = R.string.equalizer,
        descriptionRes = R.string.update_equalizer_description,
        demoComposeable = @Composable {
            EqualizerScreenPreviewMiuix()
        }
    ),
)

val updateMiuix = listOf(
    UpdateItem(
        titleRes = R.string.miuix,
        descriptionRes = R.string.update_miuix_description,
        demoComposeable = @Composable {
            AirPodsSettingsScreenPreviewMiuixConnected()
        }
    ),
)

val updates = updateMiuix + update1_0_0.drop(1)
