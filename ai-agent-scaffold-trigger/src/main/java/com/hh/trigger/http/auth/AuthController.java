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

    /**
     * 注册
     *
     * <p>HTTP：POST /api/v1/auth/register</p>
     *
     * <p>curl：</p>
     * <pre>
     * curl -X POST 'http://localhost:8080/api/v1/auth/register' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "username": "alice",
     *     "password": "password123"
     *   }'
     * </pre>
     *
     * <p>成功响应示例：</p>
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "accessToken": "eyJ...",
     *     "tokenType": "Bearer",
     *     "expiresInSeconds": 7200
     *   }
     * }
     * </pre>
     *
     * <p>注册接口无需携带令牌；后续受保护接口应在 {@code Authorization: Bearer <token>} 请求头中携带该令牌。</p>
     */
    @Override
    @PostMapping("/register")
    public Response<AuthTokenResponseDTO> register(@Valid @RequestBody RegisterRequestDTO requestDTO) {
        return Response.success(toResponse(authUseCase.register(requestDTO.username(), requestDTO.password())));
    }

    /**
     * 登录
     *
     * <p>HTTP：POST /api/v1/auth/login</p>
     *
     * <p>curl：</p>
     * <pre>
     * curl -X POST 'http://localhost:8080/api/v1/auth/login' \
     *   -H 'Content-Type: application/json' \
     *   -d '{
     *     "username": "alice",
     *     "password": "password123"
     *   }'
     * </pre>
     *
     * <p>成功响应示例：</p>
     * <pre>
     * {
     *   "code": "0000",
     *   "info": "成功",
     *   "data": {
     *     "accessToken": "eyJ...",
     *     "tokenType": "Bearer",
     *     "expiresInSeconds": 7200
     *   }
     * }
     * </pre>
     *
     * <p>登录失败时仅返回稳定的业务错误信息，不回显密码或令牌解析细节。</p>
     */
    @Override
    @PostMapping("/login")
    public Response<AuthTokenResponseDTO> login(@Valid @RequestBody LoginRequestDTO requestDTO) {
        return Response.success(toResponse(authUseCase.login(requestDTO.username(), requestDTO.password())));
    }

    private AuthTokenResponseDTO toResponse(AuthenticationResult result) {
        return new AuthTokenResponseDTO(result.accessToken(), "Bearer", result.expiresInSeconds());
    }
}
