package campus.mapper;

import campus.entity.ResourceEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ResourceMapper extends BaseMapper<ResourceEntity> {
    List<ResourceEntity> lockById(@Param("id") String id);

    List<String> activePostIds(@Param("postId") String postId);

    int retire(@Param("retiredAt") long retiredAt, @Param("id") String id);

    int bindPost(@Param("postId") String postId, @Param("id") String id);

    List<ResourceEntity> findAttachments(@Param("postId") String postId);

    int retirePost(@Param("retiredAt") long retiredAt, @Param("postId") String postId);

    int bindAvatar(@Param("userId") String userId, @Param("id") String id);

    List<String> lockExpired(@Param("retiredBefore") long retiredBefore, @Param("now") long now);

    int markExpired(@Param("id") String id);

    int extendUpload(@Param("expiresAt") long expiresAt, @Param("id") String id);

    int readyImage(@Param("id") String id, @Param("mime") String mime, @Param("expiresAt") long expiresAt);

    int beginProcessing(@Param("id") String id);

    List<String> recoverableIds(@Param("now") long now);

    int claimProcessing(
            @Param("claim") String claim,
            @Param("claimUntil") long claimUntil,
            @Param("id") String id,
            @Param("now") long now);

    int finishProcessing(
            @Param("duration") double duration,
            @Param("expiresAt") long expiresAt,
            @Param("id") String id,
            @Param("claim") String claim);

    int failProcessing(
            @Param("code") String code,
            @Param("expiresAt") long expiresAt,
            @Param("id") String id,
            @Param("claim") String claim);
}
