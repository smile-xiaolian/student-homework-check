package com.edu.submission.identity.adapter.out;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edu.submission.identity.domain.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("""
            SELECT * FROM app.user_account
            WHERE login_name = #{login} OR user_no = #{login}
            LIMIT 1
            """)
    UserAccount findByLogin(@Param("login") String login);
}

