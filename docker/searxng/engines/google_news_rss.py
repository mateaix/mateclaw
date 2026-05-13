# engines/google_news_rss.py

import re
from html import unescape
import xml.etree.ElementTree as ET
from urllib.parse import quote_plus
from datetime import datetime

# SearXNG 引擎元数据
about = {
    "website": "https://news.google.com",
    "language": "zh-CN",
    "description": "Google News RSS",
}

categories = ["news"]
paging = False
time_range_support = False

base_url = "https://news.google.com/rss/search"


def request(query, params):
    params["url"] = (
        f"{base_url}?q={quote_plus(query)}&hl=zh-CN&gl=US&ceid=US:en"
    )
    params["headers"]["User-Agent"] = (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
    )
    return params


def response(resp):
    results = []

    root = ET.fromstring(resp.text)
    channel = root.find("channel")
    if channel is None:
        return results

    for item in channel.findall("item"):
        title = item.findtext("title") or ""
        url = item.findtext("link") or item.findtext("guid") or ""
        description = item.findtext("description") or ""
        pub_date = item.findtext("pubDate") or ""

        # 清理 HTML 标签
        content = unescape(re.sub(r"<[^>]+>", "", description)).strip()

        # 解析发布时间
        published_date = None
        if pub_date:
            try:
                published_date = datetime.strptime(
                    pub_date, "%a, %d %b %Y %H:%M:%S %Z"
                )
            except ValueError:
                pass

        results.append(
            {
                "title": title,
                "url": url,
                "content": content,
                "publishedDate": published_date,
            }
        )

    return results