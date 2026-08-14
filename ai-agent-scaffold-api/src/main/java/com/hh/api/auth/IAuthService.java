package com.hh.api.auth;

import com.hh.api.auth.dto.AuthTokenResponseDTO;
import com.hh.api.auth.dto.LoginRequestDTO;
import com.hh.api.auth.dto.RegisterRequestDTO;
import com.hh.api.agent.response.Response;

/** 用户注册和登录的公开 HTTP 契约。 */
public interface IAuthService {

    Response<AuthTokenResponseDTO> register(RegisterRequestDTO requestDTO);

    Response<AuthTokenResponseDTO> login(LoginRequestDTO requestDTO);
}
