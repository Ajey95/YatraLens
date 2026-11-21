"""Live cultural page search with visible source and free lead image when available."""

from __future__ import annotations

import asyncio
from difflib import SequenceMatcher
import re

import requests

API = "https://en.wikipedia.org/w/api.php"


def _title_match(name: str, title: str) -> bool:
    normalize = lambda value: re.sub(r"[^a-z0-9]+", " ", value.casefold()).strip()
    left, right = normalize(name), normalize(title)
    specific = set(left.split()) - {"temple", "shrine", "church", "mosque", "history", "kerala"}
    return bool(left and right and specific and
                (SequenceMatcher(None, left, right).ratio() >= 0.65 or
                 specific.issubset(set(right.split()))))


async def cultural_page(name: str) -> dict | None:
    params = {"action": "query", "generator": "search", "gsrsearch": name[:120],
              "gsrlimit": "3", "prop": "extracts|pageimages|info", "exintro": "1",
              "explaintext": "1", "pithumbsize": "600", "pilicense": "free",
              "inprop": "url", "format": "json"}
    def fetch() -> dict:
        response = requests.get(API, params=params, timeout=12, headers={
            "User-Agent": "YatraLensHackathon/0.1 (cultural source lookup)"})
        response.raise_for_status()
        return response.json()

    data = await asyncio.to_thread(fetch)
    pages = list(data.get("query", {}).get("pages", {}).values())
    page = next((item for item in pages if _title_match(name, item.get("title", ""))), None)
    if page is None:
        return None
    summary = re.sub(r"\s+", " ", page.get("extract", "")).strip()[:700]
    image = (page.get("thumbnail") or {}).get("source", "")
    if not summary and not image:
        return None
    return {"title": page.get("title", name), "summary": summary,
            "source_url": page.get("fullurl", ""),
            "source_owner": "Wikipedia", "image_url": image,
            "image_source_url": page.get("fullurl", "") if image else ""}
