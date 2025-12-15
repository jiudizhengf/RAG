package org.example.rag.common;

import java.util.List;

/**
 * 全局保存用户的信息
 */
public class UserContext {
    private static final ThreadLocal<Long> userIdHolder = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> rolesHolder = new ThreadLocal<>();

    public static void set(Long userId,List<String> roles) {
        userIdHolder.set(userId);
        rolesHolder.set(roles);
    }
    public static Long getUserId() {
        return userIdHolder.get();
    }
    public static List<String> getRoles() {
        return rolesHolder.get();
    }
    public static  void clear() {
        userIdHolder.remove();
        rolesHolder.remove();
    }
}
