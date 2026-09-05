package io.privatekb.platform.localai;

import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcChatModelProfileStore implements ChatModelProfileStore {

    private static final String SETTING_KEY = "chat.profile";

    private final JdbcClient jdbc;

    JdbcChatModelProfileStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ChatModelProfile> load() {
        return jdbc.sql("""
                        SELECT setting_value
                          FROM application_setting
                         WHERE setting_key = :settingKey
                        """)
                .param("settingKey", SETTING_KEY)
                .query(String.class)
                .optional()
                .flatMap(this::parse);
    }

    @Override
    public void save(ChatModelProfile profile) {
        jdbc.sql("""
                        INSERT INTO application_setting (setting_key, setting_value, updated_at)
                        VALUES (:settingKey, :settingValue, now())
                        ON CONFLICT (setting_key) DO UPDATE
                            SET setting_value = EXCLUDED.setting_value,
                                updated_at = EXCLUDED.updated_at
                        """)
                .param("settingKey", SETTING_KEY)
                .param("settingValue", profile.name())
                .update();
    }

    private Optional<ChatModelProfile> parse(String value) {
        try {
            return Optional.of(ChatModelProfile.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
