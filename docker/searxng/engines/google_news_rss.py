# engines/google_news_rss.py

import re
import urllib.request
from html import unescape
import xml.etree.ElementTree as ET
from urllib.parse import quote_plus
from datetime import datetime

about = {
    "website": "https://news.google.com",
    "language": "zh-CN",
    "description": "Google News RSS",
}

categories = ["news"]
paging = False
time_range_support = True

# Maps SearXNG time_range → Google News when: query modifier
_time_range_map = {
    "day": "when:1d",
    "week": "when:7d",
    "month": "when:1m",
    "year": "when:1y",
}

base_url = "https://news.google.com/rss/search"
proxy_url = "http://mihomo.zeabur.internal:10808"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"


def _make_opener():
    proxy = urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url})
    return urllib.request.build_opener(proxy)


def request(query, params):
    time_range = params.get("time_range")
    if time_range and time_range in _time_range_map:
        query = f"{query} {_time_range_map[time_range]}"
    params["url"] = (
        f"{base_url}?q={quote_plus(query)}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
    )
    return params


def response(resp):
    results = []

    url = str(resp.url)
    opener = _make_opener()
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with opener.open(req, timeout=15) as r:
            xml_text = r.read().decode("utf-8")
    except Exception:
        return results

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return results

    channel = root.find("channel")
    if channel is None:
        return results

    for item in channel.findall("item")[:100]:
        title = item.findtext("title") or ""
        article_url = item.findtext("link") or ""
        description = item.findtext("description") or ""
        pub_date = item.findtext("pubDate") or ""

        source_el = item.find("source")
        source_name = ""
        source_url = ""
        if source_el is not None:
            source_name = source_el.text or ""
            source_url = source_el.get("url", "")

        content = unescape(re.sub(r"<[^>]+>", "", description)).strip()

        published_date = None
        if pub_date:
            try:
                published_date = datetime.strptime(pub_date, "%a, %d %b %Y %H:%M:%S %Z")
            except ValueError:
                pass

        results.append({
            "title": title,
            "url": article_url,
            "content": content,
            "publishedDate": published_date,
            "source": source_name,
            "source_url": source_url,
        })

    return results