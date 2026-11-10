package campus.mapper;

import campus.entity.CommentEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper extends BaseMapper<CommentEntity> {
    CommentEntity findReplyTarget(@Param("id") String id);

    long countReplies(@Param("id") String id);

    List<CommentEntity> pageThread(
            @Param("postId") String postId,
            @Param("rootId") String rootId,
            @Param("order") String order,
            @Param("size") int size,
            @Param("offset") long offset);

    long countThread(@Param("postId") String postId, @Param("rootId") String rootId);

    List<CommentEntity> pageHistory(
            @Param("userId") String userId, @Param("size") int size, @Param("offset") long offset);

    long countHistory(@Param("userId") String userId);

    int deleteThread(@Param("deletedAt") long deletedAt, @Param("id") String id);

    int deleteReply(@Param("deletedAt") long deletedAt, @Param("id") String id);

    int deleteForPost(@Param("deletedAt") long deletedAt, @Param("postId") String postId);
}
