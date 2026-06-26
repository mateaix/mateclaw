package vip.mate.auth.sso.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.auth.sso.model.SsoStateEntity;

/**
 * OAuth2 state / bind-token 存储 Mapper。
 *
 * @author MateClaw Team
 */
@Mapper
public interface SsoStateMapper extends BaseMapper<SsoStateEntity> {
}
