/**
 * 存储统计相关类型定义
 */

/**
 * 分类统计项
 */
export interface CategoryStats {
  /** 唯一标识 */
  key: string
  /** 显示名称 */
  name: string
  /** 图标标识符 */
  icon: string
  /** 文件数量 */
  count: number
  /** 存储大小（字节） */
  size: number
  /** 百分比（0-100） */
  percent: number
}

/**
 * 总体统计
 */
export interface TotalStats {
  /** 附件总数 */
  attachmentCount: number
  /** 存储总大小（字节） */
  totalSize: number
  /** 存储策略数量 */
  policyCount: number
  /** 分组数量 */
  groupCount: number
}

/**
 * 存储统计数据
 */
export interface StatisticsData {
  /** 总体统计 */
  total: TotalStats
  /** 按文件类型统计 */
  byType: CategoryStats[]
  /** 按存储策略统计 */
  byPolicy: CategoryStats[]
  /** 按分组统计 */
  byGroup: CategoryStats[]
}
