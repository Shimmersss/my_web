package help.shimmer.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LauncherActivityTest {
    private static final long FIVE_MINUTES_MS = 5L * 60L * 1000L;

    @Test
    public void refreshesAtFiveMinuteBoundary() {
        assertFalse(LauncherActivity.shouldRefreshAfterBackground(
                1_000L, 1_000L + FIVE_MINUTES_MS - 1L, false));
        assertTrue(LauncherActivity.shouldRefreshAfterBackground(
                1_000L, 1_000L + FIVE_MINUTES_MS, false));
    }

    @Test
    public void skipsRefreshForSystemPickerRoundTrip() {
        assertFalse(LauncherActivity.shouldRefreshAfterBackground(
                1_000L, 1_000L + FIVE_MINUTES_MS, true));
    }

    @Test
    public void ignoresMissingOrInvalidBackgroundTimestamp() {
        assertFalse(LauncherActivity.shouldRefreshAfterBackground(-1L, FIVE_MINUTES_MS, false));
        assertFalse(LauncherActivity.shouldRefreshAfterBackground(9_000L, 8_000L, false));
    }
}
