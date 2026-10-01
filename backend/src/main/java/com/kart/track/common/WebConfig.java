package com.kart.track.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册操作人身份拦截器，覆盖全部 /api 请求。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final OperatorInterceptor operatorInterceptor;

    public WebConfig(OperatorInterceptor operatorInterceptor) {
        this.operatorInterceptor = operatorInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(operatorInterceptor).addPathPatterns("/api/**");
    }
}
