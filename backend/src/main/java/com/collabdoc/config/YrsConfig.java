package com.collabdoc.config;

import com.collabdoc.yrs.YrsBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class YrsConfig {
    @Bean(destroyMethod = "close")
    public YrsBridge yrsBridge(@Value("${collabdoc.yrs-bridge.library-path}") String libraryPath) {
        return new YrsBridge(libraryPath);
    }
}
