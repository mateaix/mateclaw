from pathlib import Path

OUT = Path("/Users/mate/Codes/mate/mateclaw/outputs/北京公交票务评估/svg设计图")
OUT.mkdir(parents=True, exist_ok=True)

COLORS = {
    "red": "#D71920",
    "wall_red": "#B63A2E",
    "gray": "#6F7378",
    "dark": "#1F2933",
    "light": "#F5F7FA",
    "line": "#D8DEE6",
    "blue": "#2E86C1",
    "green": "#3FA45B",
    "yellow": "#F2C94C",
    "ink": "#0B2545",
}


def write(name: str, body: str) -> None:
    (OUT / name).write_text(body, encoding="utf-8")


def header(title: str, subtitle: str, width=1440, height=960) -> str:
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <defs>
    <filter id="shadow" x="-20%" y="-20%" width="140%" height="140%">
      <feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#1F2933" flood-opacity="0.12"/>
    </filter>
    <marker id="arrow-red" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">
      <path d="M0,0 L10,5 L0,10 Z" fill="{COLORS['red']}"/>
    </marker>
    <marker id="arrow-gray" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">
      <path d="M0,0 L10,5 L0,10 Z" fill="{COLORS['gray']}"/>
    </marker>
    <style>
      .title {{ font: 700 34px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['ink']}; }}
      .subtitle {{ font: 400 17px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .h {{ font: 700 20px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['ink']}; }}
      .t {{ font: 400 15px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['dark']}; }}
      .s {{ font: 400 13px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .tiny {{ font: 400 12px "Microsoft YaHei", "PingFang SC", Arial, sans-serif; fill: {COLORS['gray']}; }}
      .card {{ fill: white; stroke: {COLORS['line']}; stroke-width: 1.2; filter: url(#shadow); }}
      .band {{ fill: {COLORS['light']}; stroke: {COLORS['line']}; stroke-width: 1; }}
      .redline {{ stroke: {COLORS['red']}; stroke-width: 3; fill: none; marker-end: url(#arrow-red); }}
      .grayline {{ stroke: {COLORS['gray']}; stroke-width: 2.2; fill: none; marker-end: url(#arrow-gray); }}
      .dash {{ stroke: {COLORS['gray']}; stroke-width: 2; stroke-dasharray: 8 8; fill: none; marker-end: url(#arrow-gray); }}
    </style>
  </defs>
  <rect width="100%" height="100%" fill="#FFFFFF"/>
  <path d="M0 0 H1440 V10 H0 Z" fill="{COLORS['red']}"/>
  <path d="M0 10 H1440 V15 H0 Z" fill="{COLORS['gray']}" opacity="0.55"/>
  <text x="64" y="72" class="title">{title}</text>
  <text x="64" y="102" class="subtitle">{subtitle}</text>
'''


def footer() -> str:
    return f'''
  <text x="64" y="925" class="tiny">北京公交集团票务综合管理平台系统升级 | Vibe Coding 实施方案配套设计图</text>
  <circle cx="1358" cy="904" r="18" fill="{COLORS['red']}" opacity="0.95"/>
  <circle cx="1395" cy="904" r="18" fill="{COLORS['gray']}" opacity="0.78"/>
</svg>
'''


def card(x, y, w, h, title, lines, color="#D71920"):
    parts = [f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="10" class="card"/>',
             f'<rect x="{x}" y="{y}" width="{w}" height="8" rx="4" fill="{color}"/>',
             f'<text x="{x+22}" y="{y+42}" class="h">{title}</text>']
    yy = y + 72
    for line in lines:
        parts.append(f'<text x="{x+22}" y="{yy}" class="t">{line}</text>')
        yy += 26
    return "\n".join(parts)


architecture = header("总体技术架构图", "从用户访问、应用服务、数据存储、外部集成到运维保障的分层架构")
architecture += f'''
  <rect x="58" y="138" width="1324" height="680" rx="18" class="band"/>
  <text x="92" y="178" class="h">访问与用户层</text>
  {card(88, 206, 248, 138, "集团用户", ["集团管理员 / 操作员", "全局管理、报表、审批"], COLORS['red'])}
  {card(376, 206, 248, 138, "分公司用户", ["分公司管理员 / 操作员", "本公司业务、审核、统计"], COLORS['blue'])}
  {card(664, 206, 248, 138, "票务室用户", ["票柜、票袋、票单", "配票、结算、退票"], COLORS['green'])}
  {card(952, 206, 248, 138, "移动/内网终端", ["浏览器访问", "打印、导入、导出"], COLORS['yellow'])}

  <path d="M212 356 V410" class="redline"/>
  <path d="M500 356 V410" class="redline"/>
  <path d="M788 356 V410" class="redline"/>
  <path d="M1076 356 V410" class="redline"/>

  <text x="92" y="438" class="h">应用与业务服务层</text>
  {card(88, 466, 248, 168, "前端应用", ["Vue3 / TypeScript", "菜单导航、表单、报表", "打印与导入导出"], COLORS['red'])}
  {card(376, 466, 248, 168, "网关与认证", ["统一登录认证", "菜单 / 按钮权限", "数据范围拦截"], COLORS['gray'])}
  {card(664, 466, 248, 168, "业务服务", ["票库、票柜、票单", "充值、无人售、退票", "审批与状态机"], COLORS['blue'])}
  {card(952, 466, 248, 168, "报表服务", ["日结、月结、趋势", "Excel 导出", "汇总表加速"], COLORS['green'])}

  <path d="M336 550 H370" class="grayline"/>
  <path d="M624 550 H658" class="grayline"/>
  <path d="M912 550 H946" class="grayline"/>
  <path d="M788 646 V698" class="redline"/>

  <text x="92" y="724" class="h">数据、集成与运维层</text>
  {card(88, 752, 220, 120, "达梦数据库", ["主业务库 / 主备", "库存、流水、报表"], COLORS['red'])}
  {card(342, 752, 220, 120, "Redis / 缓存", ["会话、字典、短期任务", "热点查询缓存"], COLORS['gray'])}
  {card(596, 752, 220, 120, "文件与对象存储", ["模板、导入文件", "导出报表、附件"], COLORS['yellow'])}
  {card(850, 752, 220, 120, "外部系统集成", ["IC卡 / 数据湖 / 胆款", "银行清分线下导入"], COLORS['blue'])}
  {card(1104, 752, 220, 120, "运维保障", ["日志、监控、备份", "灰度、回滚、巡检"], COLORS['green'])}
'''
architecture += footer()
write("01-总体技术架构图.svg", architecture)


flow = header("核心票务业务流转图", "围绕票库、票柜、票袋、票单、结算、退票和报表的主业务闭环")
flow += f'''
  <rect x="74" y="150" width="1290" height="700" rx="18" class="band"/>
  {card(104, 190, 220, 126, "1. 印制入库", ["分公司发起印制申请", "集团审核", "确认后进入票库"], COLORS['red'])}
  {card(384, 190, 220, 126, "2. 票库管理", ["票号段入库", "库存余额与流水", "调拨 / 核销 / 调账"], COLORS['gray'])}
  {card(664, 190, 220, 126, "3. 配票申请", ["票务室申请", "分公司确认", "票库到票柜"], COLORS['blue'])}
  {card(944, 190, 220, 126, "4. 票柜库存", ["票务室库存", "入库 / 出库查询", "票务室调票"], COLORS['green'])}

  <path d="M324 253 H378" class="redline"/>
  <path d="M604 253 H658" class="redline"/>
  <path d="M884 253 H938" class="redline"/>

  {card(104, 400, 220, 126, "5. 票袋管理", ["绑定线路、售票员", "更换线路", "票袋库存归属"], COLORS['green'])}
  {card(384, 400, 220, 126, "6. 票单配票", ["加载票袋数据", "录入起号止号", "票柜出库到票袋"], COLORS['red'])}
  {card(664, 400, 220, 126, "7. 票单结算", ["录入剩余票号", "自动计算张数金额", "生成结算记录"], COLORS['blue'])}
  {card(944, 400, 220, 126, "8. 票单查询", ["配票单 / 结算单", "打印、导出", "重新结算入口"], COLORS['gray'])}

  <path d="M1054 328 C1054 362 260 362 214 394" class="grayline"/>
  <path d="M324 463 H378" class="redline"/>
  <path d="M604 463 H658" class="redline"/>
  <path d="M884 463 H938" class="redline"/>

  {card(104, 610, 220, 126, "9. 退票处理", ["票袋退票", "票柜退票", "库存回写"], COLORS['wall_red'])}
  {card(384, 610, 220, 126, "10. 无人售/充值", ["无人售线路结算", "IC卡库存与收入", "导入与异常处理"], COLORS['yellow'])}
  {card(664, 610, 220, 126, "11. 报表汇总", ["日结、月结", "收入、清分、趋势", "三级数据权限"], COLORS['blue'])}
  {card(944, 610, 220, 126, "12. 审计追溯", ["库存流水", "操作日志", "票号查询"], COLORS['gray'])}

  <path d="M214 538 V604" class="grayline"/>
  <path d="M774 538 V604" class="redline"/>
  <path d="M884 673 H938" class="grayline"/>
  <path d="M1054 598 C1054 568 1054 560 1054 538" class="dash"/>
  <text x="1178" y="557" class="s">异常更正 / 重新结算</text>
'''
flow += footer()
write("02-核心票务业务流转图.svg", flow)


deployment = header("部署与可运维性架构图", "面向国产化环境的部署、监控、备份、回滚和运维交接设计")
deployment += f'''
  <rect x="70" y="148" width="1300" height="704" rx="18" class="band"/>
  <text x="102" y="188" class="h">网络与访问区</text>
  {card(104, 214, 230, 116, "用户浏览器", ["集团 / 分公司 / 票务室", "内网访问、统一认证"], COLORS['red'])}
  {card(394, 214, 230, 116, "Nginx / 网关", ["HTTPS 终止", "反向代理、限流"], COLORS['gray'])}
  {card(684, 214, 230, 116, "应用服务 A", ["业务 API", "前端静态资源"], COLORS['blue'])}
  {card(974, 214, 230, 116, "应用服务 B", ["热备 / 横向扩展", "版本灰度"], COLORS['blue'])}
  <path d="M334 272 H388" class="redline"/>
  <path d="M624 272 H678" class="redline"/>
  <path d="M914 272 H968" class="grayline"/>

  <text x="102" y="398" class="h">数据与文件区</text>
  {card(104, 426, 230, 128, "达梦主库", ["业务数据写入", "库存与单据主数据"], COLORS['red'])}
  {card(394, 426, 230, 128, "达梦备库", ["主备同步", "故障切换准备"], COLORS['gray'])}
  {card(684, 426, 230, 128, "Redis", ["会话、缓存", "任务状态、热点字典"], COLORS['green'])}
  {card(974, 426, 230, 128, "对象存储", ["模板、导入文件", "导出报表、附件"], COLORS['yellow'])}
  <path d="M334 490 H388" class="grayline"/>
  <path d="M799 342 V420" class="redline"/>
  <path d="M1089 342 V420" class="grayline"/>

  <text x="102" y="620" class="h">运维保障区</text>
  {card(104, 648, 216, 124, "日志中心", ["业务日志、接口日志", "登录与操作审计"], COLORS['gray'])}
  {card(360, 648, 216, 124, "监控告警", ["存活、慢SQL、磁盘", "异常率、任务失败"], COLORS['red'])}
  {card(616, 648, 216, 124, "备份恢复", ["数据库全量/增量", "文件备份、恢复演练"], COLORS['green'])}
  {card(872, 648, 216, 124, "CI/CD", ["构建、测试、制品", "部署、版本标记"], COLORS['blue'])}
  {card(1128, 648, 216, 124, "回滚预案", ["旧版本保留", "割接窗口、冒烟检查"], COLORS['wall_red'])}
  <path d="M684 592 C550 610 465 620 468 642" class="dash"/>
  <path d="M914 592 C790 610 724 622 724 642" class="dash"/>
  <path d="M624 272 C520 340 475 380 480 420" class="dash"/>
'''
deployment += footer()
write("03-部署与可运维性架构图.svg", deployment)


permissions = header("权限与数据范围设计图", "菜单权限、按钮权限、接口权限、数据权限和导出权限的统一控制")
permissions += f'''
  <rect x="70" y="148" width="1300" height="704" rx="18" class="band"/>
  {card(96, 198, 240, 134, "集团管理员", ["配置菜单、角色、用户", "分配分公司数据范围", "查看全集团数据"], COLORS['red'])}
  {card(396, 198, 240, 134, "集团操作员", ["按角色使用功能", "查看授权公司数据", "集团级报表"], COLORS['gray'])}
  {card(696, 198, 240, 134, "分公司管理员", ["维护本公司用户", "配置票务室权限", "本公司业务管理"], COLORS['blue'])}
  {card(996, 198, 240, 134, "票务室操作员", ["票柜、票袋、票单", "结算、退票、导出", "仅本票务室数据"], COLORS['green'])}

  <rect x="170" y="414" width="1100" height="100" rx="12" fill="#FFFFFF" stroke="{COLORS['line']}" filter="url(#shadow)"/>
  <text x="212" y="456" class="h">统一权限拦截层</text>
  <text x="212" y="486" class="t">菜单权限 → 按钮权限 → 接口权限 → 数据范围权限 → 导出/打印权限</text>
  <path d="M216 344 V408" class="redline"/>
  <path d="M516 344 V408" class="grayline"/>
  <path d="M816 344 V408" class="grayline"/>
  <path d="M1116 344 V408" class="grayline"/>

  {card(116, 604, 230, 126, "组织维度", ["集团", "分公司", "票务室 / 车队 / 线路"], COLORS['red'])}
  {card(386, 604, 230, 126, "业务维度", ["票库 / 票柜 / 票袋", "充值网点", "无人售线路"], COLORS['blue'])}
  {card(656, 604, 230, 126, "数据维度", ["单据、库存、流水", "报表汇总", "历史迁移数据"], COLORS['green'])}
  {card(926, 604, 230, 126, "审计维度", ["登录日志", "操作日志", "导入导出日志"], COLORS['gray'])}
  <path d="M720 524 V598" class="redline"/>
  <path d="M720 524 C530 548 500 570 500 598" class="grayline"/>
  <path d="M720 524 C902 548 1040 570 1040 598" class="grayline"/>
'''
permissions += footer()
write("04-权限与数据范围设计图.svg", permissions)


delivery = header("Vibe Coding 协同交付流程图", "把大系统拆成可验证的小任务，形成需求、编码、测试、评审、交付闭环")
delivery += f'''
  <rect x="74" y="148" width="1290" height="704" rx="18" class="band"/>
  {card(102, 202, 230, 128, "1. 需求卡片化", ["按页面/动作拆分", "角色、输入、输出", "验收标准"], COLORS['red'])}
  {card(382, 202, 230, 128, "2. 领域建模", ["状态机、票号段", "库存流水、报表口径", "人工确认"], COLORS['gray'])}
  {card(662, 202, 230, 128, "3. 任务生成", ["表结构、接口", "页面、测试", "小批量执行"], COLORS['blue'])}
  {card(942, 202, 230, 128, "4. 编码实现", ["前后端协同", "导入导出", "权限与日志"], COLORS['green'])}
  <path d="M332 266 H376" class="redline"/>
  <path d="M612 266 H656" class="redline"/>
  <path d="M892 266 H936" class="redline"/>

  {card(102, 470, 230, 128, "8. 模块验收", ["业务演示", "样例数据核对", "问题闭环"], COLORS['wall_red'])}
  {card(382, 470, 230, 128, "7. 人工评审", ["核心规则 review", "安全与权限检查", "SQL 与事务检查"], COLORS['gray'])}
  {card(662, 470, 230, 128, "6. 自动化测试", ["单元 / 接口", "场景 / 回归", "构建检查"], COLORS['blue'])}
  {card(942, 470, 230, 128, "5. 本地自测", ["页面联调", "异常分支", "导入导出校验"], COLORS['green'])}
  <path d="M1057 342 V464" class="redline"/>
  <path d="M942 534 H898" class="grayline"/>
  <path d="M662 534 H618" class="grayline"/>
  <path d="M382 534 H338" class="grayline"/>

  <rect x="208" y="690" width="944" height="74" rx="12" fill="#FFFFFF" stroke="{COLORS['line']}" filter="url(#shadow)"/>
  <text x="238" y="726" class="h">交付原则</text>
  <text x="362" y="724" class="t">小任务、强测试、人工把关、持续演示；常规功能提效，核心规则不省评审。</text>
  <path d="M217 608 C217 670 678 650 678 684" class="dash"/>
'''
delivery += footer()
write("05-VibeCoding协同交付流程图.svg", delivery)

index = "\n".join([
    "# SVG 设计图清单",
    "",
    "- 01-总体技术架构图.svg",
    "- 02-核心票务业务流转图.svg",
    "- 03-部署与可运维性架构图.svg",
    "- 04-权限与数据范围设计图.svg",
    "- 05-VibeCoding协同交付流程图.svg",
])
(OUT / "README.md").write_text(index, encoding="utf-8")
print(OUT)
