package ru.tsu.accounts.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.PostgreSQLContainer;

/** Keeps temporary integration-test databases accessible only from this computer. */
public final class LocalPostgres {
    private LocalPostgres() {}

    public static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>("postgres:17.11-bookworm")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(5432))));
    }
}
