package moe.rukamori.archivetune.playback

import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import moe.rukamori.archivetune.audiodsp.DjFilterAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class SkipSilenceRegressionTest {

    @Test
    fun chain_hasNoDuplicatedProcessors() {
        val djFilter = DjFilterAudioProcessor()
        val processors = DefaultAudioSink.DefaultAudioProcessorChain(djFilter).audioProcessors

        assertEquals(3, processors.size)
        assertSame(djFilter, processors[0])
        assertTrue(processors[1] is SilenceSkippingAudioProcessor)
        assertTrue(processors[2] is SonicAudioProcessor)
        assertEquals(1, processors.count { it is SilenceSkippingAudioProcessor })
        assertEquals(1, processors.count { it is SonicAudioProcessor })
    }
}
