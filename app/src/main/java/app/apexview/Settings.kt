package app.apexview

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Choices that live one level down, in Settings, and survive restarts. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    /** Which F1 TV player the app asks streams for. The web one is the tested default. */
    var platform by mutableStateOf(prefs.getString("platform", "WEB_DASH") ?: "WEB_DASH")
        private set
    /** Software DRM: what desktop Chrome gets, and what lets four feeds play at once. */
    var softwareDrm by mutableStateOf(prefs.getBoolean("softwareDrm", true))
        private set
    var subtitles by mutableStateOf(prefs.getBoolean("subtitles", false))
        private set
    var commentary by mutableStateOf(prefs.getString("commentary", "en") ?: "en")
        private set
    var showStats by mutableStateOf(prefs.getBoolean("showStats", false))
        private set
    /** Live races: a note near the end offering to vote for Driver of the Day. */
    var votePrompt by mutableStateOf(prefs.getBoolean("votePrompt", true))
        private set

    fun updatePlatform(value: String) { platform = value; prefs.edit().putString("platform", value).apply() }
    fun updateSoftwareDrm(value: Boolean) { softwareDrm = value; prefs.edit().putBoolean("softwareDrm", value).apply() }
    fun updateSubtitles(value: Boolean) { subtitles = value; prefs.edit().putBoolean("subtitles", value).apply() }
    fun updateCommentary(value: String) { commentary = value; prefs.edit().putString("commentary", value).apply() }
    fun updateShowStats(value: Boolean) { showStats = value; prefs.edit().putBoolean("showStats", value).apply() }
    fun updateVotePrompt(value: Boolean) { votePrompt = value; prefs.edit().putBoolean("votePrompt", value).apply() }

    companion object {
        /** F1 TV's commentary languages, by ISO 639-1 code. */
        val COMMENTARY = listOf(
            "en" to "English",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "nl" to "Nederlands",
            "pt" to "Português",
        )
    }
}
