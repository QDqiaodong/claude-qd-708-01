package com.kart.track.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.kart.track.domain.TrackDict;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 读取操作人身份请求头：X-Operator-Id（姓名/工号）、X-Operator-Role（STAFF/SUPERVISOR）。
 *
 * <p>Servlet 容器默认按 ISO-8859-1 解析请求头，直接放中文姓名会乱码，
 * 因此前端对 X-Operator-Id 做 encodeURIComponent，这里按 UTF-8 百分号解码
 *（纯 ASCII 姓名/工号不含 % 或非 ASCII 字节，解码结果不变）。
 *
 * <p>查询接口不强制身份；处置台写接口由 {@link OperatorContext#require()} /
 * {@link OperatorContext#requireSupervisor()} 在业务层收口，越权一律 403。
 */
@Component
public class OperatorInterceptor implements HandlerInterceptor {

    public static final String HEADER_ID = "X-Operator-Id";
    public static final String HEADER_ROLE = "X-Operator-Role";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String id = decode(trim(request.getHeader(HEADER_ID)));
        String role = trim(request.getHeader(HEADER_ROLE));
        if (id != null && role != null) {
            if (!TrackDict.isValidOperatorRole(role)) {
                throw new BizException("操作人角色不合法，应为 值班人员/主管");
            }
            if (id.length() > 60) {
                throw new BizException("操作人标识过长");
            }
            OperatorContext.set(new Operator(id, role));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        OperatorContext.clear();
    }

    private static String decode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
