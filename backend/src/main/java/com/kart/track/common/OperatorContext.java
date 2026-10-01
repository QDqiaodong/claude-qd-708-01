package com.kart.track.common;

/**
 * 操作人上下文：请求进入时由 {@code OperatorInterceptor} 写入，业务层取用，请求结束清理。
 */
public final class OperatorContext {

    private static final ThreadLocal<Operator> HOLDER = new ThreadLocal<>();

    private OperatorContext() {
    }

    public static void set(Operator operator) {
        HOLDER.set(operator);
    }

    public static Operator get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 写操作必须能识别操作人，否则拒绝执行（不留匿名处置记录） */
    public static Operator require() {
        Operator operator = HOLDER.get();
        if (operator == null) {
            throw new BizException("未识别操作人身份，请在处置台右上角选择身份后再操作");
        }
        return operator;
    }

    /** 只有主管可以执行：裁定圈速、恢复比赛、强制终止 */
    public static Operator requireSupervisor() {
        Operator operator = require();
        if (!operator.isSupervisor()) {
            throw new ForbiddenException("该操作仅主管可执行（当前身份：值班人员）");
        }
        return operator;
    }
}
