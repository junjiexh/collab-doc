package com.collabdoc.config;

import com.collabdoc.auth.JwtUtil;
import com.collabdoc.permission.PermissionService;
import com.collabdoc.collab.YrsDocumentManager;
import com.collabdoc.collab.YjsWebSocketHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final YrsDocumentManager docManager;
    private final JwtUtil jwtUtil;
    private final PermissionService permissionService;

    public WebSocketConfig(YrsDocumentManager docManager, JwtUtil jwtUtil, PermissionService permissionService) {
        this.docManager = docManager;
        this.jwtUtil = jwtUtil;
        this.permissionService = permissionService;
    }

    @Bean
    public YjsWebSocketHandler yjsWebSocketHandler() {
        return new YjsWebSocketHandler(docManager, permissionService);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(yjsWebSocketHandler(), "/ws/*")
                .addInterceptors(jwtHandshakeInterceptor())
                .setAllowedOrigins("http://localhost:3000");
    }

    private HandshakeInterceptor jwtHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    HttpServletRequest httpRequest = servletRequest.getServletRequest();
                    Cookie[] cookies = httpRequest.getCookies();
                    if (cookies != null) {
                        for (Cookie cookie : cookies) {
                            if ("token".equals(cookie.getName()) && jwtUtil.isValid(cookie.getValue())) {
                                UUID userId = jwtUtil.getUserId(cookie.getValue());
                                attributes.put("userId", userId);
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {}
        };
    }
}
