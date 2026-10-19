/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.entities.ListeningBySlot

@Composable
internal fun ListeningByDayChart(
    slots: List<ListeningBySlot>,
    currentDayOfWeek: Int,
    modifier: Modifier = Modifier,
) {
    val dayLabels =
        listOf(
            R.string.day_sun,
            R.string.day_mon,
            R.string.day_tue,
            R.string.day_wed,
            R.string.day_thu,
            R.string.day_fri,
            R.string.day_sat,
        )
    val slotMap = remember(slots) { slots.associateBy { it.slot } }
    val maxTime = remember(slots) { slots.maxOfOrNull { it.timeListened } ?: 1L }
    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.secondaryContainer

    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.stats_listening_by_day),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                for (day in 0..6) {
                    val time = slotMap[day]?.timeListened ?: 0L
                    val fraction = time.toFloat() / maxTime
                    val barColor = if (day == currentDayOfWeek) primaryColor else containerColor
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(400),
                        label = "bar_$day",
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(24.dp)
                                    .height(80.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(24.dp)
                                        .height((80 * animatedFraction).dp.coerceAtLeast(2.dp))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(barColor),
                            )
                        }
                        Text(
                            text = stringResource(dayLabels[day]),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day == currentDayOfWeek) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (day == currentDayOfWeek) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ListeningByHourChart(
    slots: List<ListeningBySlot>,
    modifier: Modifier = Modifier,
) {
    val slotMap = remember(slots) { slots.associateBy { it.slot } }
    val maxTime = remember(slots) { slots.maxOfOrNull { it.timeListened } ?: 1L }
    val peakSlot = remember(slots) { slots.maxByOrNull { it.timeListened }?.slot }
    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.primaryContainer

    val peakLabel =
        remember(peakSlot) {
            peakSlot?.let {
                val hour = it % 12
                val adjusted = if (hour == 0) 12 else hour
                val amPm = if (it < 12) "AM" else "PM"
                "$adjusted$amPm"
            }
        }

    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_listening_by_hour),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (peakLabel != null) {
                    Text(
                        text = stringResource(R.string.stats_peak_hour, peakLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = primaryColor,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (hour in 0..23) {
                    val time = slotMap[hour]?.timeListened ?: 0L
                    val fraction = time.toFloat() / maxTime
                    val isPeak = hour == peakSlot
                    val barColor = if (isPeak) primaryColor else containerColor.copy(alpha = 0.6f + fraction * 0.4f)
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(400),
                        label = "hour_$hour",
                    )
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(48.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height((48 * animatedFraction).dp.coerceAtLeast(2.dp))
                                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                    .background(barColor),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = stringResource(R.string.stats_axis_12am), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = stringResource(R.string.stats_axis_6am), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = stringResource(R.string.stats_axis_12pm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = stringResource(R.string.stats_axis_6pm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = stringResource(R.string.stats_axis_12am), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
