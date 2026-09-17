package studio.polaroid.polaroiddragon.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The store's whole value is that it does <em>not</em> forget. An event without
 * a killer, or without participants, must leave the displayed hunter alone —
 * blanking an NPC or a scoreboard line because one event timed out is exactly
 * the bug this class exists to prevent. These tests pin that stickiness, the
 * independence of the two roles, and that the values really survive a restart.
 */
class HunterStoreTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB   = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static final Logger LOGGER = Logger.getLogger(HunterStoreTest.class.getName());

    @TempDir
    Path dataFolder;

    private HunterStore store;

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    /** A fresh instance over the same folder — the restart the plugin would do. */
    private HunterStore newStore() {
        return new HunterStore(dataFolder.toFile(), LOGGER);
    }

    @Test
    void startsEmpty() {
        assertNull(store.getLastKiller());
        assertNull(store.getTopDamager());
    }

    @Test
    void recordsAKiller() {
        store.recordKiller(ALICE, "Alice");

        HunterStore.Hunter killer = store.getLastKiller();
        assertNotNull(killer);
        assertEquals(ALICE, killer.uuid());
        assertEquals("Alice", killer.name());
        assertTrue(killer.timestamp() > 0L);
    }

    @Test
    void recordingANullKillerKeepsThePreviousOne() {
        store.recordKiller(ALICE, "Alice");

        // A timeout, or a dragon that fell in lava: there is no killer.
        store.recordKiller(null, null);

        assertNotNull(store.getLastKiller());
        assertEquals("Alice", store.getLastKiller().name());
    }

    @Test
    void recordingANullTopDamagerKeepsThePreviousOne() {
        store.recordTopDamager(ALICE, "Alice");

        // Nobody damaged the dragon, so the ranking was empty.
        store.recordTopDamager(null, null);

        assertNotNull(store.getTopDamager());
        assertEquals("Alice", store.getTopDamager().name());
    }

    @Test
    void aNewValidValueReplacesTheOldOne() {
        store.recordKiller(ALICE, "Alice");
        store.recordKiller(BOB, "Bob");

        assertEquals(BOB, store.getLastKiller().uuid());
        assertEquals("Bob", store.getLastKiller().name());
    }

    @Test
    void theTwoRolesAreIndependent() {
        store.recordKiller(ALICE, "Alice");
        store.recordTopDamager(BOB, "Bob");

        assertEquals("Alice", store.getLastKiller().name());
        assertEquals("Bob", store.getTopDamager().name());
    }

    @Test
    void recordingOneRoleDoesNotTouchTheOther() {
        store.recordKiller(ALICE, "Alice");
        store.recordTopDamager(BOB, "Bob");

        // The event had a killer but an empty ranking: only the killer moves.
        store.recordKiller(BOB, "Bob");
        store.recordTopDamager(null, null);

        assertEquals(BOB, store.getLastKiller().uuid());
        assertEquals(BOB, store.getTopDamager().uuid());
        assertEquals("Bob", store.getTopDamager().name());
    }

    @Test
    void survivesAReload() {
        store.recordKiller(ALICE, "Alice");
        store.recordTopDamager(BOB, "Bob");
        long killerTimestamp = store.getLastKiller().timestamp();

        HunterStore reloaded = newStore();

        assertEquals(ALICE, reloaded.getLastKiller().uuid());
        assertEquals("Alice", reloaded.getLastKiller().name());
        assertEquals(killerTimestamp, reloaded.getLastKiller().timestamp());
        assertEquals(BOB, reloaded.getTopDamager().uuid());
        assertEquals("Bob", reloaded.getTopDamager().name());
    }

    @Test
    void reloadsOnlyTheRoleThatWasEverRecorded() {
        store.recordTopDamager(BOB, "Bob");

        HunterStore reloaded = newStore();

        assertNull(reloaded.getLastKiller());
        assertEquals(BOB, reloaded.getTopDamager().uuid());
    }

    @Test
    void dropsAMalformedEntryWithoutLosingTheOther() throws Exception {
        store.recordKiller(ALICE, "Alice");
        store.recordTopDamager(BOB, "Bob");

        File file = dataFolder.resolve("last-hunters.yml").toFile();
        String corrupted = java.nio.file.Files.readString(file.toPath())
                .replace(ALICE.toString(), "not-a-uuid");
        java.nio.file.Files.writeString(file.toPath(), corrupted);

        HunterStore reloaded = newStore();

        assertNull(reloaded.getLastKiller());
        assertEquals(BOB, reloaded.getTopDamager().uuid());
    }
}
