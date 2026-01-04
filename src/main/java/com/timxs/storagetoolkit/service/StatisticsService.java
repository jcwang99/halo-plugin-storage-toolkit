package com.timxs.storagetoolkit.service;

import com.timxs.storagetoolkit.model.StatisticsData;
import reactor.core.publisher.Mono;

/**
 * 存储统计服务接口
 */
public interface StatisticsService {
    
    /**
     * 获取存储统计数据
     * 包括按类型、策略、分组的聚合统计
     *
     * @return 统计数据
     */
    Mono<StatisticsData> getStatistics();
}
