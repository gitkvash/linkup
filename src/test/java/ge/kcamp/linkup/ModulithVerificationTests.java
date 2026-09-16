package ge.kcamp.linkup;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithVerificationTests {

    @Test
    void verifyModulithStructure() {
        // Scans the application structure starting from LinkupApplication.class
        ApplicationModules modules = ApplicationModules.of(LinkupApplication.class);
        
        // Prints the module structure to console
        System.out.println(modules);
        
        // Verifies the module structure (detects cyclic dependencies, invalid accesses, etc.)
        modules.verify();
    }
}
