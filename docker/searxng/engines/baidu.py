# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Baidu (百度) search engine for SearXNG
搜索接口: https://www.baidu.com/s?wd=<keyword>&pn=<offset>
"""

from urllib.parse import urlencode
from lxml import html
from searx.utils import extract_text

about = {
    "website": "https://www.baidu.com",
    "wikidata_id": None,
    "official_api_documentation": None,
    "use_official_api": False,
    "require_api_key": False,
    "results": "HTML",
}

categories = ["general"]
paging = True
language_support = False

base_url = "https://www.baidu.com"
search_url = base_url + "/s?{query}&pn={offset}&rn=10"


def request(query, params):
    offset = (params.get("pageno", 1) - 1) * 10
    params["url"] = search_url.format(
        query=urlencode({"wd": query}),
        offset=offset,
    )
    params["headers"].update({
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9",
        "Referer": "https://www.baidu.com/",
    })
    return params


def response(resp):
    results = []

    # 检测反爬 / 验证码跳转
    if resp.status_code != 200:
        return results
    if any(kw in str(resp.url) for kw in ("antispider", "verify", "captcha")):
        return results

    doc = html.fromstring(resp.text)

    # 检测验证码页面
    if doc.xpath('//form[@id="form"]//input[@name="verifyCode"]'):
        return results

    for item in doc.xpath(
        '//div[@id="content_left"]'
        '//div[contains(@class, "result") and contains(@class, "c-container")]'
    ):
        try:
            # 标题 + 链接
            title_els = item.xpath('.//h3//a')
            if not title_els:
                continue
            title = extract_text(title_els[0])
            url = title_els[0].get("href", "")
            if not title or not url:
                continue

            # 摘要: 优先新版结构 (c-color)，回退到旧版 (c-abstract / c-span-last)
            content = ""
            for sel in (
                './/*[contains(@class, "c-color")]',
                './/*[contains(@class, "c-abstract")]',
                './/*[contains(@class, "c-span-last")]',
            ):
                els = item.xpath(sel)
                if els:
                    content = extract_text(els[0])
                    if content:
                        break

            results.append({
                "title": title,
                "url": url,
                "content": content,
            })
        except Exception:
            continue

    return results
