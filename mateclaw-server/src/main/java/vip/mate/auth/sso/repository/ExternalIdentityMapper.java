package vip.mate.auth.sso.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.auth.sso.model.ExternalIdentityEntity;

/**
 * 用户外部身份关联 Mapper。
 *
 * @author MateClaw Team
 */
@Mapper
public interface ExternalIdentityMapper extends BaseMapper<ExternalIdentityEntity> {
}
