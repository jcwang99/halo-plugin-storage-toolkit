package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.extension.ProcessingLog;
import com.timxs.storagetoolkit.model.ProcessingLogQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 处理日志服务接口
 * 提供处理日志的 CRUD 操作
 */
public interface ProcessingLogService {

    /**
     * 保存处理日志
     *
     * @param log 日志对象
     * @return 保存后的日志对象
     */
    Mono<ProcessingLog> save(ProcessingLog log);

    /**
     * 查询处理日志列表
     *
     * @param query 查询参数
     * @return 日志列表流
     */
    Flux<ProcessingLog> list(ProcessingLogQuery query);

    /**
     * 统计日志数量
     *
     * @param query 查询参数
     * @return 日志数量
     */
    Mono<Long> count(ProcessingLogQuery query);

    /**
     * 删除过期日志
     *
     * @param retentionDays 保留天数
     * @return 完成信号
     */
    Mono<Void> deleteExpired(int retentionDays);

    /**
     * 清空所有日志
     *
     * @return 删除的日志数量
     */
    Mono<Long> deleteAll();

    /**
     * 根据名称获取日志
     *
     * @param name 日志名称（UUID）
     * @return 日志对象
     */
    Mono<ProcessingLog> getByName(String name);
}
