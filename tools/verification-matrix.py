#!/usr/bin/env python3
"""Select the minimum supported and current stable IDEA/CLion releases for CI."""
import json
import re
from urllib.request import Request, urlopen

targets = []
for product, api_code, name in [("IU", "IIU", "IntelliJ IDEA"), ("CL", "CL", "CLion")]:
    request = Request(
        f"https://data.services.jetbrains.com/products/releases?code={api_code}&latest=true&type=release",
        headers={"User-Agent": "FontViewer-compatibility-checks"},
    )
    with urlopen(request, timeout=60) as response:
        releases = json.load(response)[api_code]
    latest = releases[0]["version"]
    if not re.fullmatch(r"\d{4}\.\d+(?:\.\d+)*", latest):
        raise ValueError(f"Unexpected stable version for {name}: {latest!r}")
    for channel, version in [("minimum", "2025.3"), ("latest", latest)]:
        targets.append({"product": product, "version": version, "channel": channel, "name": name})

print(json.dumps({"include": targets}, separators=(",", ":")))
