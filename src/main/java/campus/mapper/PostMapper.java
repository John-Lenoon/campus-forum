package campus.mapper;

import campus.entity.PostEntity;
import campus.entity.PostView;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PostMapper extends BaseMapper<PostEntity> {
    List<PostView> findView(@Param("id") String id);

    List<PostEntity> lockActive(@Param("id") String id);

    long countVisible(@Param("status") String status);

    List<PostView> pageViews(
            @Param("status") String status,
            @Param("admin") boolean admin,
            @Param("size") int size,
            @Param("offset") long offset);

    long countByAuthor(@Param("authorId") String authorId);

    List<PostView> pageByAuthor(
            @Param("authorId") String authorId,
            @Param("size") int size,
            @Param("offset") long offset);

    int review(
            @Param("status") String status,
            @Param("publishedAt") long publishedAt,
            @Param("reason") String reason,
            @Param("reviewerId") String reviewerId,
            @Param("reviewedAt") long reviewedAt,
            @Param("id") String id);

    int edit(
            @Param("title") String title,
            @Param("body") String body,
            @Param("submittedAt") long submittedAt,
            @Param("id") String id);

    int markDeleted(@Param("deletedAt") long deletedAt, @Param("id") String id);

    List<String> rankPublished();
}
