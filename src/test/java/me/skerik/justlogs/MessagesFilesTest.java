package me.skerik.justlogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import me.skerik.justlogs.config.Messages;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Every bundled language must have exactly the keys of the English file (no missing translations, no leftovers). */
class MessagesFilesTest {

    private static Set<String> keys(String locale) throws Exception {
        try (InputStream in = MessagesFilesTest.class.getResourceAsStream("/messages_" + locale + ".yml")) {
            assertTrue(in != null, "messages_" + locale + ".yml missing");
            YamlConfiguration y = new YamlConfiguration();
            y.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Set<String> leaves = new TreeSet<>();
            for (String key : y.getKeys(true)) {
                if (!y.isConfigurationSection(key)) leaves.add(key);
            }
            return leaves;
        }
    }

    @Test
    void allLocalesHaveTheSameKeysAsEnglish() throws Exception {
        Set<String> english = keys("en");
        assertTrue(english.contains("prefix"));
        for (String locale : Messages.BUNDLED_LOCALES) {
            assertEquals(english, keys(locale), "keys of messages_" + locale + ".yml differ from messages_en.yml");
        }
    }
}
