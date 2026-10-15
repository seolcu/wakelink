package app.wakelink.android.sounds

import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import app.wakelink.android.R

/** A built-in sound: its [SoundRef.Preset] name, audio resource and display label. */
data class PresetSound(val name: String, @RawRes val raw: Int, @StringRes val label: Int)

/** In [PRESET_NAMES] order; see scripts/gen-ringtones.py for how each one is made. */
val PRESET_SOUNDS = listOf(
    PresetSound("beep", R.raw.ringtone_beep, R.string.sound_preset_beep),
    PresetSound("digital", R.raw.ringtone_digital, R.string.sound_preset_digital),
    PresetSound("chime", R.raw.ringtone_chime, R.string.sound_preset_chime),
    PresetSound("rising", R.raw.ringtone_rising, R.string.sound_preset_rising),
    PresetSound("bell", R.raw.ringtone_bell, R.string.sound_preset_bell),
    PresetSound("marimba", R.raw.ringtone_marimba, R.string.sound_preset_marimba),
)

fun presetSound(name: String): PresetSound? = PRESET_SOUNDS.firstOrNull { it.name == name }

fun presetUri(context: Context, preset: PresetSound): Uri =
    Uri.parse("android.resource://${context.packageName}/${preset.raw}")
