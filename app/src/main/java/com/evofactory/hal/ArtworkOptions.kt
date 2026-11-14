package com.evofactory.hal

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun ArtworkOptions(
    settings: ArtworkSettings,
    onEnabled: (Boolean) -> Unit,
    onSourceChanged: (Boolean) -> Unit,
    onSave: (String, Set<String>) -> Unit,
    rowModifier: (String) -> Modifier,
) {
    var country by remember { mutableStateOf(settings.region) }
    var services by remember { mutableStateOf(settings.services) }
    var feedback by remember { mutableStateOf("") }

    fun save() {
        val region = artworkRegion(country)
        if (region == null && (country.isNotBlank() || services.isNotEmpty())) {
            feedback = "Enter a two-letter streaming country code, such as DK."
            return
        }
        country = region.orEmpty()
        onSave(country, services)
        feedback = "Saved"
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HomeOption(
            title = "Artwork slideshow",
            description = "Show rotating backgrounds on the dashboard.",
            checked = settings.enabled,
            onClick = { onEnabled(!settings.enabled) },
            modifier = rowModifier("artwork"),
        )
        HomeOption(
            title = "Use local artwork",
            description = if (settings.useLocal) "Source: images uploaded in the web editor."
                else "Source: recent streaming releases and Watch Next.",
            checked = settings.useLocal,
            onClick = { onSourceChanged(!settings.useLocal) },
            modifier = rowModifier("local-artwork"),
        )
        if (settings.useLocal) return@Column
        Text(
            "Set your streaming country and select your subscriptions. JustWatch refreshes daily without an API key.",
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
            Button(onClick = ::save, modifier = rowModifier("save-catalog")) { Text("Save") }
            if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        }
        for (service in streamingServices) {
            HomeOption(
                title = service.name,
                description = "Include recent releases in this subscription.",
                checked = service.name in services,
                onClick = {
                    services = if (service.name in services) services - service.name else services + service.name
                    feedback = ""
                },
                modifier = rowModifier(service.name),
            )
        }
        Text("Streaming availability and artwork from JustWatch.", style = MaterialTheme.typography.bodySmall)
    }
}
