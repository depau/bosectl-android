package eu.depau.bosectl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import eu.depau.bosectl.R
import eu.depau.bosectl.bmap.Prompt

/**
 * Icon for each voice-prompt id — the same association the official app uses
 * (the prompt enum doubles as the mode icon selector).
 */
val Prompt.drawable: Int
    get() = when (this) {
        Prompt.NONE -> R.drawable.ic_tune
        Prompt.QUIET -> R.drawable.ic_noise_control_on
        Prompt.AWARE, Prompt.TRANSPARENT, Prompt.TRANSPARENCY, Prompt.HEARING ->
            R.drawable.ic_noise_aware
        Prompt.MASKING -> R.drawable.ic_blur_on
        Prompt.COMFORT, Prompt.CALM -> R.drawable.ic_spa
        Prompt.COMMUTE -> R.drawable.ic_directions_bus
        Prompt.OUTDOOR -> R.drawable.ic_landscape
        Prompt.WORKOUT, Prompt.GYM -> R.drawable.ic_fitness_center
        Prompt.HOME -> R.drawable.ic_home
        Prompt.WORK -> R.drawable.ic_work
        Prompt.MUSIC -> R.drawable.ic_music_note
        Prompt.FOCUS -> R.drawable.ic_lightbulb
        Prompt.RELAX, Prompt.MEDITATE, Prompt.YOGA -> R.drawable.ic_self_improvement
        Prompt.FLIGHT -> R.drawable.ic_flight
        Prompt.AIRPORT -> R.drawable.ic_local_airport
        Prompt.DRIVING -> R.drawable.ic_directions_car
        Prompt.TRAINING -> R.drawable.ic_directions_bike
        Prompt.RUN -> R.drawable.ic_directions_run
        Prompt.WALK -> R.drawable.ic_directions_walk
        Prompt.HIKE -> R.drawable.ic_hiking
        Prompt.TALK -> R.drawable.ic_forum
        Prompt.CALL -> R.drawable.ic_call
        Prompt.WHISPER -> R.drawable.ic_volume_down
        Prompt.LEARN -> R.drawable.ic_school
        Prompt.PODCAST -> R.drawable.ic_podcasts
        Prompt.AUDIOBOOK -> R.drawable.ic_menu_book
        Prompt.SLEEP -> R.drawable.ic_bedtime
        Prompt.IMMERSION -> R.drawable.ic_spatial_tracking
        Prompt.STEREO -> R.drawable.ic_speaker_group
        Prompt.CINEMA -> R.drawable.ic_movie
    }

/** Drawable id for a prompt, usable outside Compose (the Glance widget). */
fun promptDrawable(promptId: Int): Int =
    Prompt.fromId(promptId)?.drawable ?: R.drawable.ic_surround_sound

val Prompt.icon: ImageVector
    @Composable get() = ImageVector.vectorResource(drawable)

@Composable
fun promptIcon(promptId: Int): ImageVector = ImageVector.vectorResource(promptDrawable(promptId))

/** Human-readable label for the icon picker. */
val Prompt.label: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }
