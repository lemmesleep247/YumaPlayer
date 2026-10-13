/*
 * YumaPlayer (2026) | Modified work by MuwMix
 * ArchiveTune (2026) | Original work by © Rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.utils

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.ui.player.PlaybackErrorKind
import moe.rukamori.archivetune.ui.player.PlaybackRecoveryAction
import moe.rukamori.archivetune.ui.player.findCause
import moe.rukamori.archivetune.ui.player.toPlaybackErrorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaybackErrorMapperTest {

    @Test
    fun loginRequired_returnsTrueRegardlessOfReason() {
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("LOGIN_REQUIRED", null))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("LOGIN_REQUIRED", ""))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("LOGIN_REQUIRED", "This video is private"))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("LOGIN_REQUIRED", "Sign in to confirm your age"))
    }

    @Test
    fun contentCheckRequired_requiresExtraContext() {
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("CONTENT_CHECK_REQUIRED", null))
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("CONTENT_CHECK_REQUIRED", ""))
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("CONTENT_CHECK_REQUIRED", "Content check failed"))

        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("CONTENT_CHECK_REQUIRED", "Sign in to confirm your age"))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("CONTENT_CHECK_REQUIRED", "Sign in to confirm you are not a bot and confirm your age"))
    }

    @Test
    fun ageCheckRequired_returnsTrueForAgeRestrictions() {
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("AGE_CHECK_REQUIRED", null))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("AGE_CHECK_REQUIRED", "Sign in to confirm your age"))
        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("AGE_VERIFICATION_REQUIRED", "Age restricted"))
    }

    @Test
    fun unplayable_returnsFalseUnlessExplicitLoginReason() {
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("UNPLAYABLE", null))
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("UNPLAYABLE", "This video is unavailable"))
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("UNPLAYABLE", "Video unavailable in your country"))

        assertTrue(PlaybackErrorMapper.isLoginRecoveryError("UNPLAYABLE", "Sign in to confirm your age"))
    }

    @Test
    fun botReason_identifiesBotDetection() {
        assertTrue(PlaybackErrorMapper.isBotDetectionError("Sign in to confirm you're not a bot"))
        assertTrue(PlaybackErrorMapper.isBotDetectionError("Unusual traffic from your computer network"))
        assertTrue(PlaybackErrorMapper.isBotDetectionError("automated queries"))
        assertFalse(PlaybackErrorMapper.isBotDetectionError("Sign in to confirm your age"))
    }

    @Test
    fun nestedLoginRequiredForPlaybackException_unwrapsCorrectly() {
        val rootCause = YTPlayerUtils.LoginRequiredForPlaybackException(
            videoId = "7JGDWKJfgxQ",
            targetUrl = "https://music.youtube.com/watch?v=7JGDWKJfgxQ",
            reason = "Sign in to confirm your age",
        )
        val intermediateIoException = IOException("ResolvingDataSource failed to open stream", rootCause)
        val outer = IOException("ExoPlayer source error", intermediateIoException)

        val unwrapped = outer.findCause<YTPlayerUtils.LoginRequiredForPlaybackException>()
        assertNotNull(unwrapped)
        assertSame(rootCause, unwrapped)
    }

    @Test
    fun nestedInvalidPlaybackLoginContextException_unwrapsCorrectly() {
        val rootCause = YTPlayerUtils.InvalidPlaybackLoginContextException(
            videoId = "testVideo",
            targetUrl = "https://music.youtube.com",
            cause = IllegalStateException("Playback credentials expired"),
        )
        val intermediateIoException = IOException("Data source open error", rootCause)
        val outer = IOException("ExoPlayer remote error", intermediateIoException)

        val unwrapped = outer.findCause<YTPlayerUtils.InvalidPlaybackLoginContextException>()
        assertNotNull(unwrapped)
        assertSame(rootCause, unwrapped)
    }

    @Test
    fun cancellationException_propagatesWithoutMappingToLoginOrFallback() {
        val cancellation = CancellationException("Prefetch job cancelled")
        assertFalse(PlaybackErrorMapper.isLoginRecoveryError("CANCELLED", cancellation.message))
        assertFalse(PlaybackErrorMapper.isBotDetectionError(cancellation.message.orEmpty()))

        var rethrown: Throwable? = null
        try {
            val caught: Throwable = cancellation
            if (caught is CancellationException) {
                throw caught
            }
        } catch (e: Throwable) {
            rethrown = e
        }
        assertSame(cancellation, rethrown)
    }

    @Test
    fun loginRequiredWithoutUrl_fallsBackToRefreshLoginAction() {
        val rootCause = YTPlayerUtils.LoginRequiredForPlaybackException(
            videoId = "restrictedId",
            targetUrl = "",
            reason = "Sign in to confirm your age",
        )
        val playbackEx = TestPlaybackException(
            "Source error",
            IOException(rootCause),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )

        val errorInfo = playbackEx.toPlaybackErrorInfo()
        assertEquals(PlaybackErrorKind.ConfirmationRequired, errorInfo.kind)
        assertEquals(null, errorInfo.loginRecoveryUrl)
        assertEquals(PlaybackRecoveryAction.RefreshLogin, errorInfo.recoveryAction)
    }

    @Test
    fun plainNetworkError_hasNoAuthRecoveryAction() {
        val playbackEx = TestPlaybackException(
            "Network connection failed",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )

        val errorInfo = playbackEx.toPlaybackErrorInfo()
        assertEquals(PlaybackErrorKind.NoInternet, errorInfo.kind)
        assertEquals(null, errorInfo.recoveryAction)
        assertEquals(null, errorInfo.loginRecoveryUrl)
    }

    private class TestPlaybackException(
        message: String?,
        cause: Throwable?,
        errorCode: Int,
    ) : PlaybackException(message, cause, errorCode, io.mockk.mockk(relaxed = true), 0L)
}
