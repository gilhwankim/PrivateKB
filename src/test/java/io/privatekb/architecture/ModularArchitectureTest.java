package io.privatekb.architecture;

import io.privatekb.PrivateKbApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularArchitectureTest {

    @Test
    void moduleBoundariesAreValid() {
        ApplicationModules.of(PrivateKbApplication.class).verify();
    }
}
