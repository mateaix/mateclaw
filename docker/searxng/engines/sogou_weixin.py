# SPDX-License-Identifier: AGPL-3.0-or-later
"""
搜狗微信搜索 engine for SearXNG
用法: 将本文件放入 searx/engines/ 目录，并在 settings.yml 中添加配置。

搜索接口: https://weixin.sogou.com/weixin?type=2&query=<keyword>&page=<n>
type=1 -> 公众号搜索
type=2 -> 文章搜索（本 engine 使用）

注意:
- 搜狗有频率限制，高并发会触发 CAPTCHA / 302 跳转
- 建议在 settings.yml 中将 timeout 设为 6.0，request_timeout 不低于 6
- 仅在大陆 IP 可正常访问
"""

from urllib.parse import urlencode
from lxml import html
from searx.utils import extract_text

# SearXNG engine 元信息
about = {
    "website": "https://weixin.sogou.com",
    "wikidata_id": None,
    "official_api_documentation": None,
    "use_official_api": False,
    "require_api_key": False,
    "results": "HTML",
}

# 分类：social media，也可以加 general
categories = ["social media"]
paging = True
language_support = False

# 基础 URL
base_url = "https://weixin.sogou.com"
search_url = base_url + "/weixin?type=2&{query}&page={page}"


def request(query, params):
    """构造请求参数"""
    params["url"] = search_url.format(
        query=urlencode({"query": query}),
        page=params.get("pageno", 1),
    )
    params["headers"].update(
        {
            "User-Agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/124.0.0.0 Safari/537.36"
            ),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9",
            "Referer": "https://weixin.sogou.com/",
        }
    )
    return params


def response(resp):
    """解析搜索结果页"""
    results = []

    # 检测是否触发了 CAPTCHA 或反爬跳转
    if "antispider" in resp.url or "verify" in resp.url:
        return results
    if resp.status_code != 200:
        return results

    doc = html.fromstring(resp.text)

    # 结果列表容器: <ul class="news-list"> > <li>
    items = doc.cssselect("ul.news-list > li")

    for item in items:
        try:
            # 标题 + 链接
            title_el = item.cssselect(".txt-box > h3 > a")
            if not title_el:
                continue
            title = extract_text(title_el[0])
            # 搜狗微信文章链接是跳转链接，直接使用（可访问）
            url = title_el[0].get("href", "")
            if not url.startswith("http"):
                url = base_url + url

            # 摘要
            content_el = item.cssselect(".txt-box > p.txt-info")
            content = extract_text(content_el[0]) if content_el else ""

            # 来源公众号名称
            account_el = item.cssselect(".account")
            source = extract_text(account_el[0]) if account_el else ""

            # 发布时间（搜狗返回的是时间戳或相对时间字符串）
            date_el = item.cssselect(".s-p")  # 有时候是 .s-p，有时候是 span[name]
            publishedDate = extract_text(date_el[0]) if date_el else None

            # 缩略图
            img_el = item.cssselect(".img-box > a > img")
            thumbnail = img_el[0].get("src", "") if img_el else None

            result = {
                "title": title,
                "url": url,
                "content": content,
                "publishedDate": publishedDate,
            }
            if source:
                result["content"] = f"【{source}】{content}"
            if thumbnail:
                result["thumbnail"] = thumbnail

            results.append(result)

        except Exception:  # noqa: BLE001
            # 跳过解析失败的单条结果，不影响整体
            continue

    return results
