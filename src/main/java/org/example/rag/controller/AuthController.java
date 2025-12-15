package org.example.rag.controller;

import lombok.RequiredArgsConstructor;
import org.example.rag.common.Result;
import org.example.rag.entity.User;
import org.example.rag.entity.dto.LoginRequest;
import org.example.rag.entity.dto.RegisterRequest;
import org.example.rag.repository.UserRepository;
import org.example.rag.utils.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/register")
    public Result<String> register(@RequestBody RegisterRequest registerRequest) {
        if(userRepository.findByUsername(registerRequest.getUsername())!=null){
            return Result.failed("用户名已存在");
        }
        User user = new User();
        user.setUsername(registerRequest.getUsername());
        user.setPassword(passwordEncoder.encode(registerRequest.getPassword()));
        user.setRoles("[\"" + String.join("\",\"", registerRequest.getRoles()) + "\"]");
        userRepository.save(user);
        return Result.success("注册成功");
    }

    @PostMapping("/login")
    public Result<String> login(@RequestBody LoginRequest loginRequest) {
        User user = userRepository.findByUsername(loginRequest.getUsername());
        if (user == null || !passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            return Result.failed("用户名或密码错误");
        }
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRoleList());
        return Result.success(token);
    }
}
