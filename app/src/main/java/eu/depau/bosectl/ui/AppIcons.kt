package eu.depau.bosectl.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import eu.depau.bosectl.R

/**
 * Material Symbols icons, vendored as vector drawables.
 *
 * The androidx material-icons-extended artifact is Google's frozen predecessor
 * set: it lacks noise_control_on and the per-side earbud glyphs, and it drags a
 * few thousand unused icons into the APK. Vectors are fetched by
 * `tools/fetch-material-symbols.py`.
 */
object AppIcons {
    val Add: ImageVector @Composable get() = res(R.drawable.ic_add)
    val ArrowBack: ImageVector @Composable get() = res(R.drawable.ic_arrow_back)
    val ArrowForward: ImageVector @Composable get() = res(R.drawable.ic_arrow_forward)
    val BatteryStd: ImageVector @Composable get() = res(R.drawable.ic_battery_std)
    val Bluetooth: ImageVector @Composable get() = res(R.drawable.ic_bluetooth)
    val BluetoothDisabled: ImageVector @Composable get() = res(R.drawable.ic_bluetooth_disabled)
    val EarbudCase: ImageVector @Composable get() = res(R.drawable.ic_earbud_case)
    val EarbudLeft: ImageVector @Composable get() = res(R.drawable.ic_earbud_left)
    val EarbudRight: ImageVector @Composable get() = res(R.drawable.ic_earbud_right)
    val Edit: ImageVector @Composable get() = res(R.drawable.ic_edit)
    val Headphones: ImageVector @Composable get() = res(R.drawable.ic_headphones)
    val Hearing: ImageVector @Composable get() = res(R.drawable.ic_hearing)
    val Lock: ImageVector @Composable get() = res(R.drawable.ic_lock)
    val NoiseControlOff: ImageVector @Composable get() = res(R.drawable.ic_noise_control_off)
    val NoiseControlOn: ImageVector @Composable get() = res(R.drawable.ic_noise_control_on)
    val Settings: ImageVector @Composable get() = res(R.drawable.ic_settings)
    val SpatialAudio: ImageVector @Composable get() = res(R.drawable.ic_spatial_audio)
    val SpatialAudioOff: ImageVector @Composable get() = res(R.drawable.ic_spatial_audio_off)
    val SpatialTracking: ImageVector @Composable get() = res(R.drawable.ic_spatial_tracking)
    val Star: ImageVector @Composable get() = res(R.drawable.ic_star)
    val StarFilled: ImageVector @Composable get() = res(R.drawable.ic_star_filled)
    val TouchApp: ImageVector @Composable get() = res(R.drawable.ic_touch_app)
    val Visibility: ImageVector @Composable get() = res(R.drawable.ic_visibility)
    val VolumeUp: ImageVector @Composable get() = res(R.drawable.ic_volume_up)
}

@Composable
private fun res(@DrawableRes id: Int): ImageVector = ImageVector.vectorResource(id)
