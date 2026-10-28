package com.distributed.jobplatform.config;

import com.distributed.jobplatform.entity.User;
import com.distributed.jobplatform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the initial ADMIN account at startup.
 *
 * SECURITY: this is the ONLY mechanism that can create an ADMIN account.
 * Public registration always creates USER accounts (see AuthController).
 *
 * Credentials come from configuration/environment so deployments can override
 * them. No credential defaults are defined in application.properties; the
 * ADMIN_PASSWORD / DEMO_USER_PASSWORD variables are required. The inline
 * @Value fallbacks below (admin123 / user123) are code-level DEVELOPMENT fallbacks
 * that only apply if the configuration property is absent entirely, and must be
 * overridden via environment variables in any real deployment.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Value("${app.demo-user.enabled:true}")
    private boolean demoUserEnabled;

    @Value("${app.demo-user.username:user}")
    private String demoUsername;

    @Value("${app.demo-user.password:user123}")
    private String demoPassword;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminUsername != null && !adminUsername.isBlank()
                && !userRepository.existsByUsername(adminUsername)) {
            User admin = new User(adminUsername, passwordEncoder.encode(adminPassword), "ADMIN");
            userRepository.save(admin);
            log.info("Created initial admin user '{}' from configuration (credentials are DEVELOPMENT ONLY defaults unless overridden)", adminUsername);
        }

        if (demoUserEnabled && demoUsername != null && !demoUsername.isBlank()
                && !userRepository.existsByUsername(demoUsername)) {
            User user = new User(demoUsername, passwordEncoder.encode(demoPassword), "USER");
            userRepository.save(user);
            log.info("Created demo user '{}' (DEVELOPMENT ONLY)", demoUsername);
        }
    }
}
