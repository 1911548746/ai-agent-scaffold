package com.hh.trigger.http.auth;

import com.hh.api.agent.response.Response;
import com.hh.api.auth.IAuthService;
import com.hh.api.auth.dto.AuthTokenResponseDTO;
import com.hh.api.auth.dto.LoginRequestDTO;
import com.hh.api.auth.dto.RegisterRequestDTO;
import com.hh.application.auth.model.AuthenticationResult;
import com.hh.application.auth.usecase.AuthUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户注册和登录入口；其路径由 MVC 认证拦截器显式排除。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController implements IAuthService {

    private final AuthUseCase authUseCase;

    public AuthController(AuthUseCase authUseCase) {
        this.authUseCase = authUseCase;
    }

    @Override
    @PostMapping("/register")
    public Response<AuthTokenResponseDTO> register(@Valid @RequestBody RegisterRequestDTO requestDTO) {
        return Response.success(toResponse(authUseCase.register(requestDTO.username(), requestDTO.password())));
    }

    @Override
    @PostMapping("/login")
    public Response<AuthTokenResponseDTO> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        return Response.success(toResponse(authUseCase.login(requestDTO.username(), requestDTO.password())));
    }

    private AuthTokenResponseDTO toResponse(AuthenticationResult result) {
        return new AuthTokenResponseDTO(result.accessToken(), "Bearer", result.expiresInSeconds());
    }
}
