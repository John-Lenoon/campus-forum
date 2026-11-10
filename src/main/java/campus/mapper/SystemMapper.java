package campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SystemMapper {
    String databaseVersion();

    long columnCount(@Param("table") String table, @Param("column") String column);

    void addPostDeletedAt();

    void addUserAvatar();
}
