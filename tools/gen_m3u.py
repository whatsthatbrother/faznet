#!/usr/bin/env python3
"""Synthetic M3U lists shaped like a big Xtream `m3u_plus` export: no real channels, addresses or accounts.

Usage: gen_m3u.py 50000 b50k.m3u
"""
import random
import sys

COUNTRIES = ["AL", "EU", "DE", "IT", "UK", "FR", "TR", "US", "AR", "ES", "CH", "AT", "NL", "SE", "PL", "RO", "GR", "RS", "HR", "BG"]
KINDS = ["GENERAL", "SPORTS", "MOVIES", "MUSIC", "KIDS", "NEWS", "DOCUMENTARY", "ISLAM", "LOCAL", "ENTERTAINMENT", "SERIES", "24/7"]
WORDS = ["Klan", "Top", "Super", "Sport", "Cinema", "Family", "News", "Music", "Kids", "Star", "Prime", "Max", "One", "Plus", "Nova",
         "Vizion", "Tring", "Digitalb", "Euro", "Arena", "Film", "Action", "Comedy", "Drama", "Live", "World", "Gold", "Premium"]
TAGS = ["HD", "FHD", "SD", "HEVC", "4K", "[BK]", "[BK1]", "+1", "RAW"]


def main(count: int, path: str) -> None:
    rnd = random.Random(count)  # same list every run
    group_count = max(30, count // 80)
    groups = [f"{rnd.choice(COUNTRIES)} | {rnd.choice(KINDS)} {i}" for i in range(group_count)]
    # A few big groups and many small ones, as in real exports.
    weights = [1 / (i + 1) ** 0.7 for i in range(group_count)]
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("#EXTM3U\n")
        for i in range(1, count + 1):
            g = rnd.choices(groups, weights)[0]
            cc = g[:2]
            name = f"{cc}| {rnd.choice(WORDS)} {rnd.choice(WORDS)} {rnd.randint(1, 40)} {rnd.choice(TAGS)}"
            if i % 40 == 0:
                name = "✦●✦ " + name + " ✦●✦"
            sid = 100000 + i
            # Port 9 refuses at once: tiles show the "no logo" picture and the network plays no part in the timings.
            logo = f"http://127.0.0.1:9/picons/{cc.lower()}/{sid % 4000:04d}_logo.png"
            f.write(f'#EXTINF:-1 tvg-id="{sid}.{cc.lower()}" tvg-name="{name}" tvg-logo="{logo}" group-title="{g}",{name}\n')
            f.write(f"http://line.example.test:8080/live/user{count}/pass{count}/{sid}.ts\n")


if __name__ == "__main__":
    main(int(sys.argv[1]), sys.argv[2])
