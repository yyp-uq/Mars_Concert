package com.mars.concert.member.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.member.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 查用户拥有的权限点 code（关联5张表） */
    @Select("""
        SELECT DISTINCT p.code
        FROM uc_user_role ur
        JOIN uc_role_permission rp ON rp.role_id = ur.role_id
        JOIN uc_permission p ON p.id = rp.permission_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectPermissionCodes(@Param("userId") Long userId);

    /** 查用户角色 code */
    @Select("""
        SELECT r.code FROM uc_user_role ur
        JOIN uc_role r ON r.id = ur.role_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> selectRoleCodes(@Param("userId") Long userId);

    /** 给用户分配角色（默认会员 role_id=1） */
    @Insert("INSERT INTO uc_user_role(user_id, role_id) VALUES(#{userId},#{roleId})")
    void insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
