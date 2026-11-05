package ru.tsu.accounts.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import ru.tsu.accounts.identity.AccessChangeForm;
import ru.tsu.accounts.identity.UserCommandException;

class ConfirmationGuardTest {
    @Test
    void tokenIsBoundToActorTargetAndSingleUse() {
        var guard = new ConfirmationGuard();
        var session = new MockHttpSession();
        UUID actor = UUID.randomUUID(), target = UUID.randomUUID();
        var form = new AccessChangeForm();
        form.setRoleCode("OPERATOR"); form.setStatus("BLOCKED");
        form.setVersion(3); form.setReason("Учебная проверка");
        var pending = guard.prepare(session, actor, target, form);
        assertThatThrownBy(() -> guard.consume(session, UUID.randomUUID(), target, pending.token()))
            .isInstanceOf(UserCommandException.class);
        assertThatThrownBy(() -> guard.consume(session, actor, target, "подмена"))
            .isInstanceOf(UserCommandException.class);
        assertThat(guard.consume(session, actor, target, pending.token()).roleCode()).isEqualTo("OPERATOR");
        assertThatThrownBy(() -> guard.consume(session, actor, target, pending.token()))
            .isInstanceOf(UserCommandException.class);
    }
}
