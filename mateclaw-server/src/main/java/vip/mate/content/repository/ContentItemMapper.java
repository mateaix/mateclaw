package vip.mate.content.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.content.model.ContentItemEntity;

/**
 * Mapper for {@link ContentItemEntity}. Must live under a {@code repository}
 * package so {@code @MapperScan("vip.mate.**.repository")} registers it.
 */
@Mapper
public interface ContentItemMapper extends BaseMapper<ContentItemEntity> {
}
