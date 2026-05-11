# SPDX-License-Identifier: AGPL-3.0-or-later
"""
搜狗微信搜索 engine for SearXNG
搜索接口: https://weixin.sogou.com/weixin?type=2&query=<keyword>&page=<n>
"""

from urllib.parse import urlencode
from lxml import html
from searx.utils import extract_text

about = {
    "website": "https://weixin.sogou.com",
    "wikidata_id": None,
    "official_api_documentation": None,
    "use_official_api": False,
    "require_api_key": False,
    "results": "HTML",
}

categories = ["social media"]
paging = True
language_support = False

base_url = "https://weixin.sogou.com"
search_url = base_url + "/weixin?type=2&{query}&page={page}"


def request(query, params):
    params["url"] = search_url.format(
        query=urlencode({"query": query}),
        page=params.get("pageno", 1),
    )
    params["headers"].update({
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://weixin.sogou.com/",
    })
    return params


def response(resp):
    results = []

    # 检测反爬跳转
    if resp.status_code != 200:
        return results
    if any(kw in str(resp.url) for kw in ("antispider", "verify", "sogou.com/index")):
        return results

    doc = html.fromstring(resp.text)

    # 检测验证页（搜狗有时返回 200 但内容是验证页）
    if doc.xpath('//div[@id="verify-box"]') or doc.xpath('//div[@class="verifyPage"]'):
        return results

    # 结果列表: <ul class="news-list"> > <li>
    items = doc.xpath('//ul[contains(@class,"news-list")]/li')

    for item in items:
        try:
            # 标题 + 链接
            title_els = item.xpath('.//div[contains(@class,"txt-box")]/h3/a')
            if not title_els:
                continue
            title = extract_text(title_els[0])
            url = title_els[0].get("href", "")
            if not url:
                continue
            if not url.startswith("http"):
                url = base_url + url

            # 摘要
            content_els = item.xpath('.//div[contains(@class,"txt-box")]//p[contains(@class,"txt-info")]')
            content = extract_text(content_els[0]) if content_els else ""

            # 公众号名称
            account_els = item.xpath('.//div[contains(@class,"account")]')
            if not account_els:
                account_els = item.xpath('.//*[contains(@class,"account")]')
            source = extract_text(account_els[0]) if account_els else ""

            # 缩略图
            img_els = item.xpath('.//div[contains(@class,"img-box")]//img/@src')
            thumbnail = img_els[0] if img_els else None

            result = {
                "title": title,
                "url": url,
                "content": f"【{source}】{content}" if source else content,
            }
            if thumbnail:
                result["thumbnail"] = thumbnail

            results.append(result)

        except Exception:
            continue

    return results