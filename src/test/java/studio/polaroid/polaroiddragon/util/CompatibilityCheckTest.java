package studio.polaroid.polaroiddragon.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The startup gate is the one piece of code that can stop the plugin loading at
 * all, so its two failure modes are asymmetric: wrongly accepting an old server
 * costs a confusing error later, while wrongly rejecting a good one takes the
 * plugin off a working server. These cases pin the accepted versions, the
 * multi-digit ordering trap, and above all that anything unreadable fails open.
 */
class CompatibilityCheckTest {

    // --- Classic 1.x scheme ---

    @Test
    void rejectsMinecraftOlderThanTheMinimum() {
        CompatibilityCheck.Result result = CompatibilityCheck.checkMinecraft("1.20.4");

        assertFalse(result.supported());
        assertNotNull(result.reason());
        assertTrue(result.reason().contains("1.20.4"), result.reason());
        assertTrue(result.reason().contains("1.21"), result.reason());
    }

    @Test
    void acceptsTheMinimumItself() {
        assertTrue(CompatibilityCheck.checkMinecraft("1.21").supported());
    }

    @Test
    void acceptsAPatchOfTheMinimum() {
        assertTrue(CompatibilityCheck.checkMinecraft("1.21.1").supported());
    }

    @Test
    void rejectsAnOlderMajorLine() {
        assertFalse(CompatibilityCheck.checkMinecraft("1.16.5").supported());
        assertFalse(CompatibilityCheck.checkMinecraft("1.8.9").supported());
    }

    @Test
    void acceptsANewerMajorLine() {
        assertTrue(CompatibilityCheck.checkMinecraft("1.22").supported());
    }

    // --- The multi-digit ordering trap ---

    @Test
    void multiDigitPatchOrdersAboveASingleDigitOne() {
        // String ordering puts "1.21.11" before "1.21.4", and reading either as
        // a decimal collapses them. Both must be accepted, and 1.21.11 must
        // compare as the newer of the two.
        assertTrue(CompatibilityCheck.checkMinecraft("1.21.11").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("1.21.4").supported());

        assertTrue(CompatibilityCheck.compare(
                CompatibilityCheck.parseVersion("1.21.11"),
                CompatibilityCheck.parseVersion("1.21.4")) > 0);
    }

    @Test
    void multiDigitMinorOrdersAboveASingleDigitOne() {
        assertTrue(CompatibilityCheck.compare(
                CompatibilityCheck.parseVersion("1.21"),
                CompatibilityCheck.parseVersion("1.9")) > 0);
    }

    @Test
    void missingComponentsCountAsZero() {
        assertEquals(0, CompatibilityCheck.compare(
                CompatibilityCheck.parseVersion("1.21"),
                CompatibilityCheck.parseVersion("1.21.0")));
    }

    // --- The year.drop.patch scheme ---

    @Test
    void acceptsTheNewYearBasedScheme() {
        assertTrue(CompatibilityCheck.checkMinecraft("26.3").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("26.3.1").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("27.1").supported());
    }

    @Test
    void acceptsAFarFutureYear() {
        assertTrue(CompatibilityCheck.checkMinecraft("31.10.2").supported());
    }

    // --- Qualifiers ---

    @Test
    void acceptsPreReleaseAndReleaseCandidateForms() {
        assertTrue(CompatibilityCheck.checkMinecraft("1.21.1-pre1").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("1.21-rc1").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("26.3-rc1").supported());
    }

    @Test
    void stripsTheBukkitVersionQualifier() {
        // The getBukkitVersion() fallback returns this shape.
        assertTrue(CompatibilityCheck.checkMinecraft("1.21.1-R0.1-SNAPSHOT").supported());
        assertFalse(CompatibilityCheck.checkMinecraft("1.20.4-R0.1-SNAPSHOT").supported());
    }

    @Test
    void aQualifierDoesNotRescueAnOldVersion() {
        assertFalse(CompatibilityCheck.checkMinecraft("1.20-pre2").supported());
    }

    // --- Fail open ---

    @Test
    void unparseableVersionsAreAccepted() {
        assertTrue(CompatibilityCheck.checkMinecraft(null).supported());
        assertTrue(CompatibilityCheck.checkMinecraft("").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("   ").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("unknown").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("-pre1").supported());
        assertTrue(CompatibilityCheck.checkMinecraft("..").supported());
    }

    @Test
    void aSnapshotNameIsAcceptedRatherThanRejected() {
        // "24w03a" is not a version this parser understands, and guessing would
        // be worse than allowing startup.
        assertTrue(CompatibilityCheck.checkMinecraft("24w03a").supported());
    }

    @Test
    void parseVersionReportsUnknownShapesAsNull() {
        assertNull(CompatibilityCheck.parseVersion(null));
        assertNull(CompatibilityCheck.parseVersion(""));
        assertNull(CompatibilityCheck.parseVersion("beta"));
    }

    @Test
    void parseVersionStopsAtTheFirstNonNumericComponent() {
        assertArrayEquals(new int[]{1, 21, 1}, CompatibilityCheck.parseVersion("1.21.1"));
        assertArrayEquals(new int[]{1, 21}, CompatibilityCheck.parseVersion("1.21.x"));
        assertArrayEquals(new int[]{26, 3}, CompatibilityCheck.parseVersion("26.3"));
    }

    // --- Java ---

    @Test
    void rejectsJavaBelowTheMinimum() {
        CompatibilityCheck.Result result = CompatibilityCheck.checkJava(20);

        assertFalse(result.supported());
        assertTrue(result.reason().contains("20"), result.reason());
        assertTrue(result.reason().contains("21"), result.reason());
    }

    @Test
    void acceptsJavaAtAndAboveTheMinimum() {
        assertTrue(CompatibilityCheck.checkJava(21).supported());
        assertTrue(CompatibilityCheck.checkJava(25).supported());
    }

    @Test
    void rejectsLongObsoleteJavaReleases() {
        assertFalse(CompatibilityCheck.checkJava(8).supported());
        assertFalse(CompatibilityCheck.checkJava(17).supported());
    }

    @Test
    void theRunningJvmSatisfiesTheMinimum() {
        // The build itself targets 21, so this is a sanity check on the
        // Runtime-backed entry point rather than on the comparison.
        assertTrue(CompatibilityCheck.checkJava().supported());
    }
}
