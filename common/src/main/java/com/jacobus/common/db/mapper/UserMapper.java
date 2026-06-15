package com.jacobus.common.db.mapper;

import com.jacobus.common.model.User;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.Select;

// MyBatis マッパー：SQL は text block で管理し、XMLは使用しない
// record はセッターを持たないため @Arg でコンストラクタマッピングを行う
public interface UserMapper {

    @Select("""
            SELECT id, name, age, gender
            FROM users
            WHERE id = #{id}
            """)
    @Arg(column = "id",     javaType = long.class)
    @Arg(column = "name",   javaType = String.class)
    @Arg(column = "age",    javaType = int.class)
    @Arg(column = "gender", javaType = String.class)
    User findById(long id);
}
