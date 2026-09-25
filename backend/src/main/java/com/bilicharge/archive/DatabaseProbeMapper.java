package com.bilicharge.archive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
interface DatabaseProbeMapper {
    @Select("SELECT 1")
    int ping();
}
