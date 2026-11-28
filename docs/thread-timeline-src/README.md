# 线程时序图 · 源码

`docs/thread-timeline.html` 是生成物，不要直接改。改这里的源文件后重新合并：

```bash
{ cat head.html
  printf '<body>\n'
  printf '<script>\n'
  cat data-lanes.js renderer.js data-1.js data-2.js data-3.js data-4.js
  printf '\n</script>\n'
  cat body.html
  printf '\n</body>\n</html>\n'
} > ../thread-timeline.html

node validate2.js    ../thread-timeline.html   # 结构校验
node validate-svg.js  ../thread-timeline.html   # SVG 校验
```

产物是完整的独立 HTML（`doctype` + `charset` + `<head>` / `<body>`），双击即可打开，
不依赖任何宿主壳；只有字体从 Google Fonts 加载，断网时回落到系统字体。

| 文件 | 内容 |
|---|---|
| `head.html` | `<!doctype>` / `<head>` / 样式与设计 token（明暗双主题） |
| `data-lanes.js` | 线程字典：`VT-REQ` / `VT-GEN` / `PT-PUBSUB` … 的名称、类别、副标题 |
| `renderer.js` | SVG 时序图渲染器：泳道列 + 行内步骤盒 + 跨列箭头 |
| `data-1..4.js` | 各接口的图定义（lanes / steps / switches / refs / notes） |
| `body.html` | 页面骨架 + 索引 + 交互 + 页脚（不含 `<body>` 标签） |
| `validate2.js` | 结构校验：步骤号连续、lane 索引、hop 说明、switch 步骤存在性 |
| `validate-svg.js` | SVG 校验：标签配平、坐标越界、NaN |

## 数据格式

```js
{
  id, group, method, path, title, desc, alt,
  lanes: ['REQ', 'MYSQL'],        // 列 = 线程，键取自 data-lanes.js
  steps: [{
    n: 1,                          // 步骤号，必须从 1 连续
    lane: 0,                       // 所在列
    text: '...',                   // 盒内文字，\n 强制换行
    to: 1,                         // 可选：跨列指向第 1 列
    hop: 'SELECT',                 // 跨列时横线上的说明
  }],
  switches: [{ n, lane, how, why }],  // 线程切换点表
  refs: [['方法名', 'file:line']],
  notes: ['...'],
}
```

`to` 只影响画的箭头指向哪一列，箭头走本行水平线，因此不会因行高差异画歪。

## 覆盖范围

49 个接口 = `agent-service` 全部 22 个 + `dorm-service` 的 `/internal/**` 全部 27 个。
`dorm-service` 对外的 58 个 `/api/**` 是标准 CRUD，线程模型相同，未逐个画。

## 实测事实（作图依据）

这些结论来自对运行中 JVM 的 `jcmd <pid> Thread.dump_to_file -format=json`，不是从配置推断的：

- 两服务均无 `http-nio-*-exec-*` 工作线程 → Tomcat 请求执行器已被换成虚拟线程
  （`spring.threads.virtual.enabled=true`，两服务默认开）。
- dorm-service 的 `scheduling-1` 是**虚拟线程**（容器 `SimpleAsyncTaskScheduler$2`，
  堆栈含 `VirtualThread.parkNanos`），且**只有一条**——7 个 `@Scheduled` 任务串行排队。
- agent-service 无 `scheduling-*` 线程，`@Scheduled` 落在 `dorm-sse-1..4`
  （`ThreadPoolTaskScheduler`，4 条平台线程）——因为 `agentStreamScheduler`
  这个 Bean 让 Boot 的调度自动配置退让。
- 另有 `mybatis-plus-jsqlParser-*` 线程池（分页插件解析 SQL 用，纯 CPU，不碰 I/O）。
