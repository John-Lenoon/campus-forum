package campus.config;

import campus.mapper.SystemMapper;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class DatabaseEvolution implements InitializingBean {
    private final SystemMapper schema;

    public DatabaseEvolution(SystemMapper schema) {
        this.schema = schema;
    }

    @Override
    public void afterPropertiesSet() {
        if (schema.columnCount("forum_posts", "deleted_at") == 0) schema.addPostDeletedAt();
        if (schema.columnCount("forum_users", "avatar_resource_id") == 0) schema.addUserAvatar();
    }
}
