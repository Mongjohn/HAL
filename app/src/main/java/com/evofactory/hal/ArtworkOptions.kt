package com.evofactory.hal

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private val tmdbReadToken = Regex("[A-Za-z0-9._~-]{1,2048}")

@Composable
internal fun ArtworkOptions(
    settings: ArtworkSettings,
    savedToken: String,
    onEnabled: (Boolean) -> Unit,
    onSave: (String, String) -> Boolean,
    rowModifier: (String) -> Modifier,
) {
    var country by remember { mutableStateOf(settings.region) }
    var token by remember { mutableStateOf(savedToken) }
    var feedback by remember { mutableStateOf("") }

    fun save() {
        val region = artworkRegion(country).orEmpty()
        val credential = token.trim().removePrefix("Bearer ").trim()
        feedback = when {
            region.isEmpty() && (country.isNotBlank() || credential.isNotEmpty()) ->
                "Enter a two-letter streaming country code, such as DK."
            credential.isNotEmpty() && !tmdbReadToken.matches(credential) ->
                "Enter a TMDB API Read Access Token."
            !onSave(region, credential) -> "Couldn’t save the catalog settings."
            else -> {
                country = region
                token = credential
                "Saved"
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeOption(
            title = "Media artwork",
            description = "Rotate artwork from streaming apps shown in the dock.",
            checked = settings.enabled,
            onClick = { onEnabled(!settings.enabled) },
            modifier = rowModifier("artwork"),
        )
        Text(
            "Watch Next is used when available. Add a TMDB API Read Access Token for more artwork from your streaming country.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTextField(
                value = country,
                onValueChange = { country = it; feedback = "" },
                placeholder = "Country code",
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
                onImeAction = {},
                modifier = rowModifier("country").width(152.dp),
            )
            TvTextField(
                value = token,
                onValueChange = { token = it; feedback = "" },
                placeholder = "TMDB API Read Access Token",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
                onImeAction = {},
                modifier = rowModifier("tmdb-token").weight(1f),
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(onClick = ::save, modifier = rowModifier("save-catalog")) { Text("Save") }
        }
        if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        Text("Credits", style = MaterialTheme.typography.labelMedium)
        Image(painterResource(R.drawable.tmdb_logo), "TMDB", Modifier.width(96.dp))
        Text(
            "This product uses the TMDB API but is not endorsed or certified by TMDB. Streaming availability data by JustWatch.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
