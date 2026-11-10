package campus.mapper;

import campus.entity.UserEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    List<UserEntity> findByUsername(@Param("usernameKey") String usernameKey);

    UserEntity lockById(@Param("id") String id);

    int updateNickname(@Param("nickname") String nickname, @Param("id") String id);

    int updateAvatar(@Param("resourceId") String resourceId, @Param("id") String id);
}
