package studio.polaroid.polaroiddragon.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins what the legacy §-string round trip costs.
 *
 * <p>These are not tests of our code so much as executable documentation of why
 * user-facing text must travel as a {@link Component}: every menu item and chat
 * message used to be parsed to a Component, serialized down to legacy, and then
 * re-parsed. These cases show exactly what that path destroys, so nobody
 * reintroduces it believing it is lossless.
 */
class LegacyRoundTripTest {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * Mirrors the plugin's own path: legacy §-codes are normalized back to
     * MiniMessage by {@link ColorFormats} before being deserialized. Feeding
     * §-codes straight to MiniMessage throws, which is precisely why
     * {@code ColorFormats.parse} exists.
     */
    private static Component roundTrip(Component source) {
        return ColorFormats.parse(LEGACY.serialize(source));
    }

    @Test
    void hoverTextIsDestroyedByTheLegacyRoundTrip() {
        Component source = Component.text("Click me")
                .hoverEvent(HoverEvent.showText(Component.text("Extra detail")));

        assertNotNull(source.hoverEvent(), "precondition: the source carries a hover");
        assertNull(roundTrip(source).hoverEvent(),
                "legacy §-strings cannot carry a hover event");
    }

    @Test
    void clickActionsAreDestroyedByTheLegacyRoundTrip() {
        Component source = Component.text("Open the top")
                .clickEvent(ClickEvent.runCommand("/polaroiddragon top"));

        assertNotNull(source.clickEvent(), "precondition: the source carries a click");
        assertNull(roundTrip(source).clickEvent(),
                "legacy §-strings cannot carry a click event");
    }

    /**
     * A gradient resolves to one colored child per character. The legacy
     * serializer keeps only what its own format can express, so the per
     * character colors are lost and the run collapses to a single color.
     */
    @Test
    void aGradientCollapsesToASingleColor() {
        String text = "ANCESTRAL DRAGON";
        Component source = MM.deserialize(
                "<gradient:#eb4b30:#f3842c:#f2c42f:#95d027>" + text + "</gradient>");

        long distinctBefore = distinctColors(source);
        long distinctAfter = distinctColors(roundTrip(source));

        assertTrue(distinctBefore > 1,
                "precondition: a gradient carries several colors, found " + distinctBefore);
        assertTrue(distinctAfter < distinctBefore,
                "expected color detail to be lost, before=" + distinctBefore
                        + " after=" + distinctAfter);
        assertEquals(text, plainText(roundTrip(source)), "the text itself survives");
    }

    /** Distinct colors across a component and all of its children. */
    private static long distinctColors(Component component) {
        java.util.Set<net.kyori.adventure.text.format.TextColor> colors = new java.util.HashSet<>();
        collectColors(component, colors);
        return colors.size();
    }

    private static void collectColors(Component component,
                                      java.util.Set<net.kyori.adventure.text.format.TextColor> into) {
        if (component.color() != null) into.add(component.color());
        for (Component child : component.children()) collectColors(child, into);
    }

    /**
     * The safety net for the migration: a Component that only carries color and
     * style does survive, which is why the legacy path went unnoticed for so
     * long — plain messages looked fine and only the rich ones silently lost
     * their behavior.
     */
    @Test
    void plainColorAndStyleDoSurvive() {
        Component source = MM.deserialize("<red><bold>Danger");
        Component result = roundTrip(source);

        assertEquals("Danger", plainText(result));
        assertEquals(source.color(), result.color());
    }

    private static String plainText(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }
}
