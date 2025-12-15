package org.example.rag.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.rag.common.UserContext;
import org.example.rag.utils.JwtUtil;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private  final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // 在这里可以添加对JWT的验证逻辑，例如使用JWT库解析和验证token
            // 如果验证失败，可以设置响应状态码为401 Unauthorized
            // response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            try {
                Claims claims = jwtUtil.parseToken(token);
                Long userId = (Long) claims.get("userId",Long.class);
                List<String> roles = claims.get("roles",List.class);
                //1. 将用户信息存入 UserContext
                UserContext.set(userId,roles);
                //2.设置到spring SecurityContext中
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                //构建认证信息并存入SecurityContext
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(userId,null,authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);

            }catch (Exception e){
                //由于JWT解析失败，说明token无效，直接返回401
            }

        }
        try{
            filterChain.doFilter(request,response);
        }finally {
            UserContext.clear();
        }
    }
}
