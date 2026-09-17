package studio.polaroid.polaroiddragon.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Slot range rule for menu items.
 *
 * <p>menus.yml slots are operator-controlled, so an out-of-range value must cost
 * one item rather than throwing out of the menu build. These cases pin both ends
 * of the range, including the off-by-one at {@code slot == size} that a
 * three-row menu hits with the common mistake of writing slot 27.
 */
class DragonMenuSlotTest {

    private static final int THREE_ROWS = 27;

    @Test
    void acceptsTheFirstAndLastSlotOfTheView() {
        assertTrue(DragonMenu.isPlaceable(0, THREE_ROWS));
        assertTrue(DragonMenu.isPlaceable(THREE_ROWS - 1, THREE_ROWS));
    }

    @Test
    void acceptsAnOrdinarySlotInTheMiddle() {
        assertTrue(DragonMenu.isPlaceable(13, THREE_ROWS));
    }

    @Test
    void rejectsTheSlotJustPastTheEnd() {
        // The classic off-by-one: a 27-slot menu addresses 0..26, not 27.
        assertFalse(DragonMenu.isPlaceable(THREE_ROWS, THREE_ROWS));
    }

    @Test
    void rejectsASlotWellBeyondTheView() {
        assertFalse(DragonMenu.isPlaceable(40, THREE_ROWS));
    }

    @Test
    void rejectsANegativeSlot() {
        assertFalse(DragonMenu.isPlaceable(-1, THREE_ROWS));
    }

    @Test
    void scalesWithLargerViews() {
        int sixRows = 54;
        assertTrue(DragonMenu.isPlaceable(40, sixRows));
        assertFalse(DragonMenu.isPlaceable(sixRows, sixRows));
    }
}
