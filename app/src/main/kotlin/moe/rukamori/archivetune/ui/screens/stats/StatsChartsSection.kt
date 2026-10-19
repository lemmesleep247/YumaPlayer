/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.stats

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.db.entities.Artist
import moe.rukamori.archivetune.db.entities.ListeningBySlot
import moe.rukamori.archivetune.db.entities.ListeningSummary
import java.time.LocalDateTime

internal fun LazyListScope.statsChartsSection(
    mostPlayedArtists: List<Artist>,
    listeningSummary: ListeningSummary,
    listeningByDayOfWeek: List<ListeningBySlot>,
    listeningByHour: List<ListeningBySlot>,
) {
    item(key = "artistDonutChart", contentType = "chart") {
        if (mostPlayedArtists.isNotEmpty()) {
            Spacer(modifier = Modifier.size(8.dp))
            SegmentedArtistChart(
                artists = mostPlayedArtists.take(5),
                totalTimeListened = listeningSummary.totalTimeListened,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .animateItem(),
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
    }

    item(key = "listeningByDayChart", contentType = "chart") {
        if (listeningByDayOfWeek.isNotEmpty()) {
            ListeningByDayChart(
                slots = listeningByDayOfWeek,
                currentDayOfWeek = remember { LocalDateTime.now().dayOfWeek.value % 7 },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .animateItem(),
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
    }

    item(key = "listeningByHourChart", contentType = "chart") {
        if (listeningByHour.isNotEmpty()) {
            ListeningByHourChart(
                slots = listeningByHour,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .animateItem(),
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}
