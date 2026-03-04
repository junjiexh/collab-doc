package com.collabdoc.config;

import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final YrsDocumentManager docManager;

    public WebSocketConfig(YrsDocumentManager docManager) {
        this.docManager = docManager;
    }

    @Bean
    public YjsWebSocketHandler yjsWebSocketHandler() {
        return new YjsWebSocketHandler(docManager);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(yjsWebSocketHandler(), "/ws/*")
                .setAllowedOrigins("*");
    }
}
