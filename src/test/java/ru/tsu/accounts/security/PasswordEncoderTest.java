package ru.tsu.accounts.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PasswordEncoderTest {
    @Test
    void bcryptVerifiesOnlyTheRightPasswordAndUsesRandomSalts() {
        var encoder = new BCryptPasswordEncoder(12);
        String first = encoder.encode("correct-password-for-e1");
        String second = encoder.encode("correct-password-for-e1");
        assertThat(first).startsWith("$2a$12$").isNotEqualTo(second);
        assertThat(encoder.matches("correct-password-for-e1", first)).isTrue();
        assertThat(encoder.matches("wrong-password", first)).isFalse();
    }
}
