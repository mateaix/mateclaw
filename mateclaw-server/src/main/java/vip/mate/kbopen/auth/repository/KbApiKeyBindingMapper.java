package vip.mate.kbopen.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.kbopen.auth.model.KbApiKeyBindingEntity;

@Mapper
public interface KbApiKeyBindingMapper extends BaseMapper<KbApiKeyBindingEntity> {
}
