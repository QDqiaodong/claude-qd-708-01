package com.kart.track.domain;

import java.time.LocalDateTime;

import org.hibernate.annotations.ResultCheckStyle;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLInsert;
import org.hibernate.annotations.SQLUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块四：圈速记录。
 *
 * <p>【本项目的数据访问特色】写链路完全走 Hibernate 自定义 SQL 映射：
 * 插入 / 更新 / 删除三句全部手写（{@code @SQLInsert} / {@code @SQLUpdate} / {@code @SQLDelete}），
 * Hibernate 不再自己生成这三句语句。
 *
 * <p>占位符顺序 = Hibernate 映射属性时的顺序（按属性名字母序：createdAt / driverId /
 * fastest / kartId / lapIndex / lapTimeMs / sessionId / valid），主键 id 是 IDENTITY，
 * 由 JDBC generated keys 回填，不出现在手写 insert 里。列名逐一写死，顺序一眼可核对。
 */
@Data
@Entity
@Table(name = "lap_record")
@SQLInsert(sql = "insert into lap_record "
        + "(created_at, driver_id, is_fastest, kart_id, lap_index, lap_time_ms, session_id, valid) "
        + "values (?,?,?,?,?,?,?,?)", check = ResultCheckStyle.NONE)
@SQLUpdate(sql = "update lap_record "
        + "set created_at=?, driver_id=?, is_fastest=?, kart_id=?, lap_index=?, lap_time_ms=?, session_id=?, valid=? "
        + "where id=?")
@SQLDelete(sql = "delete from lap_record where id=?")
public class LapRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    /** 圈序：本车手的第几圈 */
    @Column(name = "lap_index", nullable = false)
    private Integer lapIndex;

    /** 圈速（毫秒） */
    @Column(name = "lap_time_ms", nullable = false)
    private Integer lapTimeMs;

    /** 是否本场最快圈 */
    @Column(name = "is_fastest", nullable = false)
    private boolean fastest;

    /** VALID 有效 / OUT 无效-出界 / CRASH 无效-碰撞 */
    @Column(name = "valid", nullable = false, length = 16)
    private String valid;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
