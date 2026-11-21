"""Conservative reader for IMD's public Kerala subdivision warning page.

This is a Kerala-wide advisory, not a district closure or a vessel cancellation.
Only the Alappuzha pilot box is enabled until administrative boundary matching is
available. An unreadable or stale page never means that there is no warning.
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta
from html.parser import HTMLParser
from zoneinfo import ZoneInfo

SOURCE_URL = "https://mausam.imd.gov.in/imd_latest/contents/subdivisionwise-warning_mc.php?id=4"
KERALA_TIME = ZoneInfo("Asia/Kolkata")


class _Rows(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self.row: list[str] | None = None
        self.cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            self.row = []
        elif tag in {"td", "th"} and self.row is not None:
            self.cell = []
        elif tag == "br" and self.cell is not None:
            self.cell.append(" ")

    def handle_data(self, data: str) -> None:
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag in {"td", "th"} and self.cell is not None and self.row is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None


def in_alappuzha_pilot(lat: float, lon: float) -> bool:
    return 9.2 <= lat <= 9.8 and 76.2 <= lon <= 76.6


def parse_kerala_warning(html: str, now: datetime) -> dict:
    """Return dated entries or an explicit unavailable status; never infer safety."""
    parser = _Rows()
    parser.feed(html)
    rows = parser.rows
    if not any(any("Warnings for Kerala" in cell for cell in row) for row in rows):
        return {"status": "unavailable", "reason": "Kerala warning table was not found"}
    issue = next((cell.removeprefix("Date of Issue:").strip()
                  for row in rows for cell in row if cell.startswith("Date of Issue:")), None)
    try:
        issue_date = datetime.strptime(issue or "", "%B %d, %Y").date()
    except ValueError:
        return {"status": "unavailable", "reason": "IMD issue date was not readable"}
    local_today = now.astimezone(KERALA_TIME).date()
    if issue_date < local_today - timedelta(days=1) or issue_date > local_today:
        return {"status": "unavailable", "reason": "IMD warning page is stale or future-dated",
                "issue_date": issue_date.isoformat()}
    entries: list[dict] = []
    for row in rows:
        if len(row) < 2:
            continue
        match = re.fullmatch(r"Day\s+([1-7])\s*:\s*([A-Za-z]+\s+\d{1,2},\s*\d{4})", row[0])
        if not match:
            continue
        try:
            day = datetime.strptime(match.group(2), "%B %d, %Y").date()
        except ValueError:
            continue
        if day != issue_date + timedelta(days=int(match.group(1)) - 1):
            continue
        description = row[1].strip()
        if not description:
            continue
        entries.append({"date": day.isoformat(), "description": description,
                        "warning": description.casefold() != "no warning"})
    if not entries:
        return {"status": "unavailable", "reason": "IMD warning rows were not readable",
                "issue_date": issue_date.isoformat()}
    return {"status": "available", "issue_date": issue_date.isoformat(),
            "source_url": SOURCE_URL, "scope": "Kerala subdivision", "entries": entries}


def relevant_warning(parsed: dict, start: datetime, end: datetime) -> dict | None:
    if parsed.get("status") != "available" or end <= start:
        return None
    # IMD page exposes dates but no interval for each row. Match local calendar
    # dates conservatively and tell the user to verify exact local timing.
    local_start = start.astimezone(KERALA_TIME).date()
    local_end = (end - timedelta(microseconds=1)).astimezone(KERALA_TIME).date()
    for entry in parsed["entries"]:
        day = date.fromisoformat(entry["date"])
        if entry["warning"] and local_start <= day <= local_end:
            return entry
    return None


def combine_with_forecast(forecast: dict, parsed: dict, lat: float, lon: float,
                          start: datetime, end: datetime) -> dict:
    result = dict(forecast)
    result["official_warning"] = None
    result["official_warning_status"] = "outside_alappuzha_pilot"
    result["official_warning_source_url"] = None
    result["alert_source_url"] = None
    if not in_alappuzha_pilot(lat, lon):
        return result
    result["official_warning_status"] = parsed.get("status", "unavailable")
    if parsed.get("status") != "available":
        return result
    result["official_warning_source_url"] = SOURCE_URL
    result["official_warning_issue_date"] = parsed["issue_date"]
    hit = relevant_warning(parsed, start, end)
    if hit is None:
        return result
    result["decision"] = "ACTION_RECOMMENDED"
    result["reason"] = (f"IMD lists {hit['description']} for the Kerala subdivision on "
                        f"{hit['date']}. The page gives a date, not the exact hours. "
                        "Confirm local operating status with the operator")
    result["official_warning"] = hit
    result["alert_source_url"] = SOURCE_URL
    result["service_cancelled"] = False
    return result
