package campus.mapper;

import campus.entity.InteractionEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InteractionMapper {
    long countRelation(
            @Param("userId") String userId,
            @Param("postId") String postId,
            @Param("kind") String kind);

    int insertRelation(@Param("interaction") InteractionEntity interaction);

    int deleteRelation(
            @Param("userId") String userId,
            @Param("postId") String postId,
            @Param("kind") String kind);

    long countPublished(@Param("userId") String userId, @Param("kind") String kind);

    List<String> pagePublishedIds(
            @Param("userId") String userId,
            @Param("kind") String kind,
            @Param("size") int size,
            @Param("offset") long offset);
}
