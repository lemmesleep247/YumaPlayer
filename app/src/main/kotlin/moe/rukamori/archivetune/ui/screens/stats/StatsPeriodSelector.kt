/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.ui.screens.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.StatPeriod
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.ui.component.ChoiceChipsRow
import moe.rukamori.archivetune.ui.screens.OptionStats
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun StatsPeriodSelector(
    selectedOption: OptionStats,
    indexChips: Int,
    firstEvent: EventWithSong?,
    onSelectionChange: (OptionStats) -> Unit,
    onValueUpdate: (Int) -> Unit,
    modifier: Modifier = Modifier,
    currentDate: LocalDateTime = remember { LocalDateTime.now() },
) {
    val weeklyDates =
        remember(currentDate, firstEvent) {
            val first = firstEvent ?: return@remember emptyList<Pair<Int, String>>()
            generateSequence(currentDate) { it.minusWeeks(1) }
                .takeWhile { it.isAfter(first.event.timestamp.minusWeeks(1)) }
                .mapIndexed { index, date ->
                    val endDate = date.plusWeeks(1).minusDays(1).coerceAtMost(currentDate)
                    val formatter = DateTimeFormatter.ofPattern("dd MMM")
                    val startDateFormatted = formatter.format(date)
                    val endDateFormatted = formatter.format(endDate)
                    val text =
                        when {
                            date.year != currentDate.year -> "$startDateFormatted, ${date.year} - $endDateFormatted, ${endDate.year}"
                            date.month != endDate.month -> "$startDateFormatted - $endDateFormatted"
                            else -> "${date.dayOfMonth} - $endDateFormatted"
                        }
                    Pair(index, text)
                }.toList()
        }

    val monthlyDates =
        remember(currentDate, firstEvent) {
            val first = firstEvent ?: return@remember emptyList<Pair<Int, String>>()
            generateSequence(currentDate.plusMonths(1).withDayOfMonth(1).minusDays(1)) { it.minusMonths(1) }
                .takeWhile { it.isAfter(first.event.timestamp.withDayOfMonth(1)) }
                .mapIndexed { index, date ->
                    val formatter = DateTimeFormatter.ofPattern("MMM")
                    val text = if (date.year != currentDate.year) "${formatter.format(date)} ${date.year}" else formatter.format(date)
                    Pair(index, text)
                }.toList()
        }

    val yearlyDates =
        remember(currentDate, firstEvent) {
            val first = firstEvent ?: return@remember emptyList<Pair<Int, String>>()
            generateSequence(currentDate.plusYears(1).withDayOfYear(1).minusDays(1)) { it.minusYears(1) }
                .takeWhile { it.isAfter(first.event.timestamp) }
                .mapIndexed { index, date -> Pair(index, "${date.year}") }
                .toList()
        }

    ChoiceChipsRow(
        chips =
            when (selectedOption) {
                OptionStats.WEEKS -> {
                    weeklyDates
                }

                OptionStats.MONTHS -> {
                    monthlyDates
                }

                OptionStats.YEARS -> {
                    yearlyDates
                }

                OptionStats.CONTINUOUS -> {
                    listOf(
                        StatPeriod.WEEK_1.ordinal to pluralStringResource(R.plurals.n_week, 1, 1),
                        StatPeriod.MONTH_1.ordinal to pluralStringResource(R.plurals.n_month, 1, 1),
                        StatPeriod.MONTH_3.ordinal to pluralStringResource(R.plurals.n_month, 3, 3),
                        StatPeriod.MONTH_6.ordinal to pluralStringResource(R.plurals.n_month, 6, 6),
                        StatPeriod.YEAR_1.ordinal to pluralStringResource(R.plurals.n_year, 1, 1),
                        StatPeriod.ALL.ordinal to stringResource(R.string.filter_all),
                    )
                }
            },
        options =
            listOf(
                OptionStats.CONTINUOUS to stringResource(id = R.string.continuous),
                OptionStats.WEEKS to stringResource(R.string.weeks),
                OptionStats.MONTHS to stringResource(R.string.months),
                OptionStats.YEARS to stringResource(R.string.years),
            ),
        selectedOption = selectedOption,
        onSelectionChange = onSelectionChange,
        currentValue = indexChips,
        onValueUpdate = onValueUpdate,
        modifier = modifier,
    )
}
