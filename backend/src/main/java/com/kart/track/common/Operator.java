package com.kart.track.common;

import com.kart.track.domain.TrackDict;

/**
 * 当前请求的操作人（由 {@code X-Operator-Id / X-Operator-Role} 请求头带入）。
 *
 * <p>角色：{@code STAFF} 值班人员 —— 只能发起红旗、填写车辆核查；
 * {@code SUPERVISOR} 主管 —— 还能裁定圈速、恢复比赛、强制终止。
 */
public record Operator(String id, String role) {

    public boolean isSupervisor() {
        return "SUPERVISOR".equals(role);
    }

    public String display() {
        return TrackDict.operatorRoleName(role) + "·" + id;
    }
}
