package eu.depau.bosectl.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalAirport
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.SpeakerGroup
import androidx.compose.material.icons.outlined.SurroundSound
import androidx.compose.material.icons.outlined.SpatialAudio
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.vector.ImageVector
import eu.depau.bosectl.bmap.Prompt

/**
 * Icon for each voice-prompt id — the same association the official app uses
 * (the prompt enum doubles as the mode icon selector).
 */
val Prompt.icon: ImageVector
    get() = when (this) {
        Prompt.NONE -> Icons.Outlined.Tune
        Prompt.QUIET -> Icons.AutoMirrored.Outlined.VolumeOff
        Prompt.AWARE -> Icons.Outlined.Hearing
        Prompt.TRANSPARENT, Prompt.TRANSPARENCY -> Icons.Outlined.Hearing
        Prompt.MASKING -> Icons.Outlined.BlurOn
        Prompt.COMFORT -> Icons.Outlined.Spa
        Prompt.COMMUTE -> Icons.Outlined.DirectionsBus
        Prompt.OUTDOOR -> Icons.Outlined.Landscape
        Prompt.WORKOUT -> Icons.Outlined.FitnessCenter
        Prompt.HOME -> Icons.Outlined.Home
        Prompt.WORK -> Icons.Outlined.Work
        Prompt.MUSIC -> Icons.Outlined.MusicNote
        Prompt.FOCUS -> Icons.Outlined.Lightbulb
        Prompt.RELAX -> Icons.Outlined.SelfImprovement
        Prompt.FLIGHT -> Icons.Outlined.Flight
        Prompt.AIRPORT -> Icons.Outlined.LocalAirport
        Prompt.DRIVING -> Icons.Outlined.DirectionsCar
        Prompt.TRAINING -> Icons.AutoMirrored.Outlined.DirectionsBike
        Prompt.GYM -> Icons.Outlined.FitnessCenter
        Prompt.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
        Prompt.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
        Prompt.HIKE -> Icons.Outlined.Hiking
        Prompt.TALK -> Icons.Outlined.Forum
        Prompt.CALL -> Icons.Outlined.Call
        Prompt.WHISPER -> Icons.AutoMirrored.Outlined.VolumeDown
        Prompt.HEARING -> Icons.Outlined.Hearing
        Prompt.LEARN -> Icons.Outlined.School
        Prompt.PODCAST -> Icons.Outlined.Podcasts
        Prompt.AUDIOBOOK -> Icons.AutoMirrored.Outlined.MenuBook
        Prompt.CALM -> Icons.Outlined.Spa
        Prompt.SLEEP -> Icons.Outlined.Bedtime
        Prompt.MEDITATE, Prompt.YOGA -> Icons.Outlined.SelfImprovement
        Prompt.IMMERSION -> Icons.Outlined.SpatialAudio
        Prompt.STEREO -> Icons.Outlined.SpeakerGroup
        Prompt.CINEMA -> Icons.Outlined.Movie
    }

fun promptIcon(promptId: Int): ImageVector =
    Prompt.fromId(promptId)?.icon ?: Icons.Outlined.SurroundSound

/** Human-readable label for the icon picker. */
val Prompt.label: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }
