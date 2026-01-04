// ECharts 按需引入配置
import * as echarts from 'echarts/core'

// 引入需要的图表类型
import { PieChart } from 'echarts/charts'

// 引入需要的组件
import { TooltipComponent, LegendComponent } from 'echarts/components'

// 引入渲染器
import { CanvasRenderer } from 'echarts/renderers'

// 注册组件
echarts.use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

export default echarts
