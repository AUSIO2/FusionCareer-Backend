package com.fusioncareer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fusioncareer.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
