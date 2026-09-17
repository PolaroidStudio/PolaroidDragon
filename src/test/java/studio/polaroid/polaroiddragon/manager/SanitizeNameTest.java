package studio.polaroid.polaroiddragon.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reward commands are run as console with the winner's name pasted in, so the
 * name must never be able to split or extend the command line. Vanilla names
 * are safe by construction, but Bedrock/Geyser names carry a prefix and
 * offline-mode servers accept more than vanilla does.
 */
class SanitizeNameTest {

    @Test
    void keepsAVanillaName() {
        assertEquals("Notch", DragonManager.sanitizeName("Notch"));
        assertEquals("some_player_99", DragonManager.sanitizeName("some_player_99"));
    }

    @Test
    void keepsTheBedrockPrefixCharacter() {
        // Geyser prefixes Bedrock players with a dot by default.
        assertEquals(".BedrockUser", DragonManager.sanitizeName(".BedrockUser"));
    }

    @Test
    void stripsWhitespaceThatWouldAddAnArgument() {
        assertEquals("evilop", DragonManager.sanitizeName("evil op"));
    }

    @Test
    void stripsCharactersUsedToChainCommands() {
        assertEquals("nameop", DragonManager.sanitizeName("name; op"));
        assertEquals("nameopme", DragonManager.sanitizeName("name && op me"));
        assertEquals("nameop", DragonManager.sanitizeName("name\nop"));
    }

    @Test
    void stripsMiniMessageTagDelimiters() {
        // A name reaching a MiniMessage parser must not be able to open a tag.
        assertEquals("clickrun_commandopme",
                DragonManager.sanitizeName("<click:run_command:/op me>"));
    }

    @Test
    void handlesNullAndEmptyNames() {
        assertEquals("", DragonManager.sanitizeName(null));
        assertEquals("", DragonManager.sanitizeName(""));
    }

    @Test
    void leavesNothingOutsideTheAllowedAlphabet() {
        String sanitized = DragonManager.sanitizeName("a!@#$%^&*()+=[]{}|\\:;\"'<>,?/~`b");
        assertTrue(sanitized.matches("[A-Za-z0-9_.]*"),
                "unexpected characters survived: " + sanitized);
    }
}
