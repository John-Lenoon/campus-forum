package campus.mapper;

import campus.entity.ChunkEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChunkMapper {
    List<Integer> uploadedIndices(@Param("resourceId") String resourceId);

    List<ChunkEntity> findPart(@Param("resourceId") String resourceId, @Param("index") int index);

    int deletePart(@Param("resourceId") String resourceId, @Param("index") int index);

    int insertPart(@Param("chunk") ChunkEntity chunk);

    List<ChunkEntity> allParts(@Param("resourceId") String resourceId);
}
