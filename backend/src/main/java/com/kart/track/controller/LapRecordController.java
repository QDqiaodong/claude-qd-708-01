package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.LapRecordDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.LapRecordService;

import jakarta.validation.Valid;

/**
 * 模块四：圈速记录。
 *
 * <p>新增走 {@code @SQLInsert}、改判走 {@code @SQLUpdate}、删除走 {@code @SQLDelete}，
 * 三条语句全部写在 {@code LapRecord} 实体上。
 */
@RestController
@RequestMapping("/api/lap-records")
public class LapRecordController {

    private final LapRecordService lapRecordService;

    public LapRecordController(LapRecordService lapRecordService) {
        this.lapRecordService = lapRecordService;
    }

    @GetMapping
    public ApiResponse<List<Views.LapRecordView>> list(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        return ApiResponse.success(lapRecordService.list(sessionId));
    }

    /** 登记一个圈速（insert 语句来自实体上的 @SQLInsert） */
    @PostMapping
    public ApiResponse<Views.LapRecordView> create(@Valid @RequestBody LapRecordDtos.LapSaveReq req) {
        return ApiResponse.success("圈速已记录", lapRecordService.create(req));
    }

    /** 改判有效 / 无效（走 @SQLUpdate） */
    @PostMapping("/{id}/valid")
    public ApiResponse<Views.LapRecordView> setValid(@PathVariable("id") Long id,
                                                     @Valid @RequestBody LapRecordDtos.LapValidReq req) {
        return ApiResponse.success("判定已更新", lapRecordService.setValid(id, req));
    }

    /** 删除误录（走 @SQLDelete） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        lapRecordService.delete(id);
        return ApiResponse.success("已删除", null);
    }
}
