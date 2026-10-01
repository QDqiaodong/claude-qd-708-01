package com.kart.track.common;

/**
 * 权限收口专用异常：值班人员越权（如裁定圈速、恢复/终止）时抛出，统一转 403。
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
