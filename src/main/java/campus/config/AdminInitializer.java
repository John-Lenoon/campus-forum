package campus.config;

import campus.service.AccountService;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

@Component
@DependsOn("databaseEvolution")
public class AdminInitializer implements InitializingBean {
    private final AccountService accounts;

    public AdminInitializer(AccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public void afterPropertiesSet() {
        accounts.initializeAdmin();
    }
}
