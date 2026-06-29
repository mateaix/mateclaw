package vip.mate.kbopen.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.kbopen.auth.model.KbApiKeyEntity;

@Mapper
public interface KbApiKeyMapper extends BaseMapper<KbApiKeyEntity> {
}
