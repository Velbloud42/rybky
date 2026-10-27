#!/usr/bin/env python3
"""Rybky – backend (standardní knihovna Pythonu + SQLite; admin navíc potřebuje balík webauthn).

Spuštění:  python3 server.py [port]        (výchozí port 8000)
Telefon přes USB:  adb reverse tcp:8000 tcp:8000
"""
import base64
import hashlib
import html
import json
import math
import os
import random
import re
import secrets
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import admin

DB_PATH = Path(os.environ.get("RYBKY_DB") or Path(__file__).with_name("rybky.db"))
# Fotky samolepek a úlovků (JPEG) vedle databáze.
IMAGES_DIR = DB_PATH.parent / "images"
# Úvodní stránka /intro a její obrázky.
WEB_DIR = Path(__file__).with_name("web")
# Odkud se stahuje aplikace: nejnovější vydání na GitHubu (zjišťuje se nejvýš jednou za RELEASE_TTL vteřin).
GITHUB_REPO = os.environ.get("RYBKY_GITHUB", "Velbloud42/rybky")
RELEASES_URL = f"https://github.com/{GITHUB_REPO}/releases"
RELEASE_TTL = 5
MAX_BODY = 3_000_000
MAX_IMAGE = 1_500_000

# Nález se uzná do této vzdálenosti od místa aktivace (+ nepřesnost GPS nálezce, max. 50 m).
MAX_DISTANCE_M = 50
# Na mapě se ukazuje jen kruh, ve kterém samolepka je.
MAP_CIRCLE_M = 120
# Do této vzdálenosti dostane telefon otisky samolepek, aby je mohl rozpoznávat v kameře.
NEARBY_M = 300
# Minimální kosinová podobnost fotky nálezce s naučenými fotkami samolepky.
MIN_SIMILARITY = 0.75
# Tetování a předměty se fotí pokaždé jinde, v jiném světle a úhlu – stačí menší shoda
# (ale fotka musí projít kontrolou „je to rybka“).
MIN_SIMILARITY_PORTABLE = 0.65
# Kolik fotek si samolepka pamatuje; každý úlovek přidá jednu, aby se poznávala z víc úhlů.
MAX_STICKER_SHOTS = 20
# Minimální podobnost s galerií vzorů, aby fotka prošla jako „rybka z Rybek“ (ne živá ryba, zeď…).
RYBKA_MIN_SIMILARITY = 0.6
# Rybek je víc druhů obrázků: prvních tolik samolepek/tetování se nekontroluje, jen se z nich galerie učí.
LEARNING_STICKERS = 10
# Stylů samolepek je jen pár, takže samotný obrázek ryby se shoduje i se samolepkou na úplně jiném místě.
# Kterou rybku člověk fotí, se proto pozná podle okolí: telefon posílá otisky výřezů 0,45 / 0,65 / 0,9
# snímku a okolí jsou ty dva širší (0,65 je zhruba to, co je vidět na displeji). Nejužší výřez je skoro
# jen samotná rybka – podle něj se jen kontroluje, že na fotce rybka je.
CONTEXT_CROPS = slice(1, 3)
CONTEXT_MIN_SIMILARITY = 0.55
# Ulovit (a nalepit) jde jen rybku, která je jasně podobnější než všechny ostatní v dosahu.
MATCH_MARGIN = 0.1

RARITY_WEIGHT = {"běžná": 60, "nevšední": 25, "vzácná": 12, "legendární": 3}
RARITY_POINTS = {"běžná": 10, "nevšední": 20, "vzácná": 40, "legendární": 100}
RARITIES = list(RARITY_POINTS)
AUTHOR_POINTS = 5
# Rybka na hrnku nebo tričku je drobnost: za úlovek jen půl bodu, autor nedostane nic.
OBJECT_POINTS = 0.5

ENVIRONMENTS = {
    "voda": "U vody",
    "park": "Park a příroda",
    "mesto": "Město",
    "uvnitr": "Vnitřní prostory",
    "vrchol": "Vrchol a výška",
    "tetovani": "Tetování",
    "predmet": "Předmět s rybkou",
}
# Tetování a předměty (hrnek, tričko, taška…) nemají polohu – nosí se s sebou.
# Ulovit jdou kdekoli, jen podle obrázku, a nejsou na mapě.
TATTOO = "tetovani"
OBJECT = "predmet"
PORTABLE = {TATTOO, OBJECT}
FISH = {
    "voda": [("Kapr", "běžná"), ("Pstruh", "nevšední"), ("Štika", "vzácná"), ("Vyza", "legendární")],
    "park": [("Karas", "běžná"), ("Lín", "nevšední"), ("Úhoř", "vzácná")],
    "mesto": [("Městská sardinka", "běžná"), ("Tramvajová plotice", "nevšední"), ("Kanálový sumec", "vzácná")],
    "uvnitr": [("Gupka", "běžná"), ("Neonka", "nevšední"), ("Skalára", "vzácná")],
    "vrchol": [("Horská slunka", "nevšední"), ("Létající ryba", "legendární")],
    "tetovani": [("Kérková rybka", "běžná"), ("Inkoustová rybka", "nevšední"), ("Tetovaný kapr", "vzácná"),
                 ("Živá legenda", "legendární")],
    "predmet": [("Hrnková rybka", "běžná"), ("Tričková ryba", "běžná"), ("Plechovková sardinka", "nevšední"),
                ("Klíčenková rybka", "vzácná")],
}

# Každá rybka má navíc vlastní jméno, které žádná jiná nemá: přívlastek + jméno („Ospalý Ferda“, „Mlsná Jarmila“).
FISH_NAMES = {
    "ý": ["Ferda", "Bohouš", "Pepík", "Venca", "Bořek", "Ludvík", "Matěj", "Kuba", "Jarda", "Mirek", "Otík",
          "Vašek", "Čenda", "Bedřich", "Hubert", "Kazimír", "Rudla", "Štěpán", "Toník", "Vilém", "Zdeněk",
          "Šimon", "Prokop", "Radovan", "Leoš", "Emil", "Oskar", "Hugo", "Maxík", "Ignác"],
    "á": ["Jarmila", "Máňa", "Božena", "Žofka", "Ludmila", "Bára", "Dáša", "Eliška", "Hedvika", "Irena",
          "Kamila", "Liduška", "Magda", "Nela", "Olga", "Pavla", "Růžena", "Stáňa", "Vlasta", "Zdena", "Agáta",
          "Berta", "Cecílie", "Dorota", "Ema", "Fanynka", "Hela", "Klotylda", "Matylda", "Žaneta"],
}
# Kmeny přívlastků, koncovka (ý/á) podle rodu jména.
FISH_ADJECTIVES = ["Ospal", "Mlsn", "Rychl", "Moudr", "Tich", "Blázniv", "Zlatoploutv", "Šupinat", "Kulat",
                   "Bublinkov", "Stříbrn", "Toulav", "Zvědav", "Vesel", "Mrzut", "Odvážn", "Lín", "Hladov",
                   "Nebojácn", "Tajemn", "Nafoukl", "Leskl", "Pruhovan", "Puntíkat", "Ukecan", "Skromn", "Slavn",
                   "Neposedn", "Duhov", "Kouzeln"]

ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

# Úroveň L začíná na LEVEL_STEP * (L-1) * L bodech: 2 → 100, 3 → 300, 4 → 600, 5 → 1000 …
LEVEL_STEP = 50
LEVEL_TITLES = ["Jikra", "Plůdek", "Rybička", "Rybář", "Zkušený rybář", "Mistr rybář", "Kapitán", "Vodník"]

# Úlovky se do dnů a hodin (noční rybář, ranní ptáče…) počítají podle českého času.
try:
    LOCAL_TZ = ZoneInfo("Europe/Prague")
except ZoneInfoNotFoundError:
    LOCAL_TZ = None  # místní čas serveru
FAR_KM = 100

# (id, název, popis, metrika ze user_stats, cíl, vzácnost)
ACHIEVEMENTS = [
    ("prvni_ulovek", "První úlovek", "Ulov svou první rybu", "catches", 1, "běžná"),
    ("rybar", "Rybář", "Ulov 10 ryb", "catches", 10, "běžná"),
    ("mistr_rybar", "Mistr rybář", "Ulov 50 ryb", "catches", 50, "vzácná"),
    ("stovka", "Stovka", "Ulov 100 ryb", "catches", 100, "legendární"),
    ("sberatel", "Sběratel", "Ulov 5 různých druhů", "species", 5, "běžná"),
    ("znalec", "Znalec", "Ulov 15 různých druhů", "species", 15, "vzácná"),
    ("atlas", "Kompletní atlas", "Ulov všechny druhy ryb", "species", sum(len(v) for v in FISH.values()), "legendární"),
    ("vzacny_kus", "Vzácný kus", "Ulov vzácnou nebo legendární rybu", "rare", 1, "nevšední"),
    ("legenda", "Legenda", "Ulov legendární rybu", "legendary", 1, "vzácná"),
    ("lovec_legend", "Lovec legend", "Ulov 3 legendární ryby", "legendary", 3, "legendární"),
    ("cestovatel", "Cestovatel", "Ulov rybu ve všech prostředích", "envs", len(ENVIRONMENTS), "vzácná"),
    ("svetobeznik", "Světoběžník", f"Ulov dvě ryby aspoň {FAR_KM} km od sebe", "far", FAR_KM, "vzácná"),
    ("inkoust", "Lovec inkoustu", "Ulov rybu z tetování", "tattoos", 1, "nevšední"),
    ("galerie", "Galerie na kůži", "Ulov 5 ryb z tetování", "tattoos", 5, "vzácná"),
    ("drobnosti", "Sběratel drobností", "Ulov 10 rybek na předmětech", "objects", 10, "nevšední"),
    ("pruzkopnik", "Průkopník", "Ulov rybu jako první ze všech", "first_finds", 1, "nevšední"),
    ("objevitel", "Objevitel", "Ulov 10 ryb jako první ze všech", "first_finds", 10, "vzácná"),
    ("spolecensky", "Společenská ryba", "Ulov rybky od 5 různých autorů", "authors", 5, "nevšední"),
    ("nocni_rybar", "Noční rybář", "Ulov rybu mezi 22. a 4. hodinou", "night", 1, "nevšední"),
    ("ranni_ptace", "Ranní ptáče", "Ulov rybu mezi 5. a 8. hodinou ráno", "morning", 1, "nevšední"),
    ("rybarsky_den", "Rybářský den", "Ulov 5 ryb za jeden den", "best_day", 5, "vzácná"),
    ("vytrvalec", "Vytrvalec", "Ulov rybu v 7 různých dnech", "days", 7, "nevšední"),
    ("tisicovka", "Tisícovka", "Nasbírej 1000 bodů", "points", 1000, "legendární"),
    ("lepic", "Lepič", "Nalep první samolepku", "stickers", 1, "běžná"),
    ("pilny_lepic", "Pilný lepič", "Nalep 10 samolepek", "stickers", 10, "nevšední"),
    ("mistr_lepic", "Mistr lepič", "Nalep 25 samolepek", "stickers", 25, "vzácná"),
    ("oblibene_misto", "Oblíbené místo", "Tvoji samolepku ulovilo 5 lidí", "best_sticker", 5, "nevšední"),
    ("atrakce", "Turistická atrakce", "Tvoji samolepku ulovilo 10 lidí", "best_sticker", 10, "vzácná"),
    ("oblibeny_autor", "Oblíbený autor", "Tvoje rybky někdo ulovil celkem 25×", "fans", 25, "vzácná"),
    ("nova_tvar", "Nová tvář", "Nastav si profilovou fotku", "avatar", 1, "běžná"),
]
ACHIEVEMENT_IDS = {a[0] for a in ACHIEVEMENTS}
# Odznak = úspěch z posledních tří vzácností. Vedle jména se vystavují nejvýš tři.
BADGE_RARITIES = RARITIES[1:]
SHOWCASE_SIZE = 3
LEADERBOARD_SIZE = 10
MOTTO_MAX = 60


def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db():
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY,
                nick TEXT NOT NULL UNIQUE COLLATE NOCASE,
                token TEXT NOT NULL UNIQUE,
                points INTEGER NOT NULL DEFAULT 0,
                created REAL NOT NULL
            );
            CREATE TABLE IF NOT EXISTS stickers (
                id TEXT PRIMARY KEY,
                author_id INTEGER NOT NULL REFERENCES users(id),
                status TEXT NOT NULL DEFAULT 'active',   -- active | lost
                name TEXT, fish TEXT, rarity TEXT, env TEXT, hint TEXT,
                lat REAL, lon REAL, accuracy REAL,
                embeddings TEXT NOT NULL,                -- JSON: seznam otisků naučených fotek
                created REAL NOT NULL, activated REAL
            );
            CREATE TABLE IF NOT EXISTS catches (
                id INTEGER PRIMARY KEY,
                sticker_id TEXT NOT NULL REFERENCES stickers(id),
                finder_id INTEGER NOT NULL REFERENCES users(id),
                lat REAL, lon REAL, accuracy REAL, distance REAL,
                accepted INTEGER NOT NULL,
                time REAL NOT NULL
            );
            -- Galerie vzorů „takhle vypadá rybka“: výchozí obrázky + každá nalepená samolepka a úlovek.
            CREATE TABLE IF NOT EXISTS refs (
                id INTEGER PRIMARY KEY,
                embedding TEXT NOT NULL,
                source TEXT NOT NULL,          -- seed | sticker | catch
                created REAL NOT NULL
            );
            -- Hardwarové klíče (WebAuthn) pro admin dashboard.
            CREATE TABLE IF NOT EXISTS admin_keys (
                id TEXT PRIMARY KEY,           -- credential id (base64url)
                public_key TEXT NOT NULL,      -- base64url COSE
                sign_count INTEGER NOT NULL,
                name TEXT NOT NULL,
                created REAL NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS one_catch
                ON catches(sticker_id, finder_id) WHERE accepted = 1;
            -- Kdy hráč který úspěch poprvé splnil (vedle jména se vystavují nejnovější odznaky).
            CREATE TABLE IF NOT EXISTS unlocks (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                achievement TEXT NOT NULL,
                time REAL NOT NULL,
                PRIMARY KEY (user_id, achievement)
            );
            -- Ruční úpravy bodů v adminu: kdo, kolik, kdy a proč.
            CREATE TABLE IF NOT EXISTS point_adjustments (
                id INTEGER PRIMARY KEY,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                delta REAL NOT NULL,
                reason TEXT,
                time REAL NOT NULL
            );
            """
        )
        add_column(conn, "stickers", "name", "TEXT")
        add_column(conn, "stickers", "image", "TEXT")
        add_column(conn, "stickers", "fish_name", "TEXT")
        add_column(conn, "stickers", "context", "TEXT")  # JSON: otisky okolí (originál + úlovky)
        add_column(conn, "catches", "context", "TEXT")   # co úlovek přidal do okolí samolepky (kvůli odebrání)
        add_column(conn, "users", "avatar", "TEXT")
        add_column(conn, "users", "motto", "TEXT")
        add_column(conn, "users", "showcase", "TEXT")  # JSON seznam vybraných odznaků, NULL = poslední tři
        fix_fish(conn)
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS stickers_fish_name ON stickers(fish_name)")
        add_column(conn, "catches", "similarity", "REAL")
        add_column(conn, "catches", "points", "REAL")
        add_column(conn, "catches", "image", "TEXT")
        # Negativní vzory („tohle není rybka“) označí admin; origin = sticker:ID / catch:ID kvůli dohledání.
        add_column(conn, "refs", "negative", "INTEGER NOT NULL DEFAULT 0")
        add_column(conn, "refs", "origin", "TEXT")
        columns = {r["name"] for r in conn.execute("PRAGMA table_info(users)")}
        if "password" not in columns:
            conn.execute("ALTER TABLE users ADD COLUMN password TEXT")  # salt$hash, NULL = staré konto bez hesla
        if "nick_key" not in columns:
            # COLLATE NOCASE nezná diakritiku („Šárka“ ≠ „šárka“), proto vlastní klíč.
            conn.execute("ALTER TABLE users ADD COLUMN nick_key TEXT")
            for r in conn.execute("SELECT id, nick FROM users").fetchall():
                conn.execute("UPDATE users SET nick_key = ? WHERE id = ?", (nick_key(r["nick"]), r["id"]))
            merge_duplicate_nicks(conn)
        conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_nick_key ON users(nick_key)")


def add_column(conn, table, column, decl):
    if column not in {r["name"] for r in conn.execute(f"PRAGMA table_info({table})")}:
        conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")


def fix_fish(conn):
    """Srovná rybky s katalogem: vzácnost podle druhu (druh, který v prostředí už není, se vylosuje znovu)
    a každé, která ještě nemá jméno, vymyslí nové."""
    catalog = {(env, name): rarity for env, options in FISH.items() for name, rarity in options}
    for r in conn.execute("SELECT id, env, fish, rarity, fish_name FROM stickers").fetchall():
        fish, rarity = r["fish"], catalog.get((r["env"], r["fish"]))
        if rarity is None:
            fish, rarity = pick_fish(r["env"])
        name = r["fish_name"] or new_fish_name(conn)
        if (fish, rarity, name) != (r["fish"], r["rarity"], r["fish_name"]):
            conn.execute("UPDATE stickers SET fish = ?, rarity = ?, fish_name = ? WHERE id = ?",
                         (fish, rarity, name, r["id"]))


def new_fish_name(conn):
    """Jméno, které ještě žádná rybka nemá. Dokud to jde, nové ryby nesdílí ani přívlastek, ani křestní jméno."""
    taken = {r[0] for r in conn.execute("SELECT fish_name FROM stickers WHERE fish_name IS NOT NULL")}
    used_words = {w.rstrip("ýá") for n in taken for w in n.split()[:2]}
    for attempt in range(200):
        ending = random.choice(list(FISH_NAMES))
        adjective, first = random.choice(FISH_ADJECTIVES), random.choice(FISH_NAMES[ending])
        name = f"{adjective}{ending} {first}"
        fresh = adjective not in used_words and first.rstrip("ýá") not in used_words
        if name not in taken and (fresh or attempt >= 100):
            return name
    n = 2
    while f"{name} {n}." in taken:
        n += 1
    return f"{name} {n}."


def nick_key(nick):
    return nick.strip().casefold()


def valid_nick(data):
    nick = str(data.get("nick", "")).strip()
    if not 3 <= len(nick) <= 20:
        raise ApiError(400, "Přezdívka musí mít 3 až 20 znaků")
    return nick


def merge_duplicate_nicks(conn):
    """Prázdné duplicitní účty (stejná přezdívka jinou velikostí písmen) smaže, nejstarší nechá."""
    dupes = conn.execute("SELECT nick_key FROM users GROUP BY nick_key HAVING COUNT(*) > 1").fetchall()
    for (key,) in dupes:
        rows = conn.execute("SELECT id FROM users WHERE nick_key = ? ORDER BY created", (key,)).fetchall()
        for (uid,) in rows[1:]:
            used = conn.execute(
                "SELECT (SELECT COUNT(*) FROM stickers WHERE author_id = ?) + (SELECT COUNT(*) FROM catches WHERE finder_id = ?)",
                (uid, uid),
            ).fetchone()[0]
            if used:
                conn.execute("UPDATE users SET nick_key = ? WHERE id = ?", (f"{key}#{uid}", uid))
            else:
                conn.execute("DELETE FROM users WHERE id = ?", (uid,))


def hash_password(password, salt=None):
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), 200_000).hex()
    return f"{salt}${digest}"


def check_password(password, stored):
    salt = stored.split("$", 1)[0]
    return secrets.compare_digest(hash_password(password, salt), stored)


def valid_password(data):
    password = str(data.get("password") or "")
    if len(password) < 4:
        raise ApiError(400, "Heslo musí mít aspoň 4 znaky")
    return password


def distance_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def fuzzed_center(sticker_id, lat, lon):
    """Střed kruhu na mapě: posunutý od samolepky, ale vždy stejně, aby nešel zprůměrovat."""
    h = hashlib.sha256(sticker_id.encode()).digest()
    angle = h[0] / 255 * 2 * math.pi
    dist = (0.3 + 0.5 * h[1] / 255) * MAP_CIRCLE_M
    dlat = dist * math.cos(angle) / 111320
    dlon = dist * math.sin(angle) / (111320 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def parse_embedding(value):
    if not isinstance(value, list) or not 16 <= len(value) <= 4096:
        raise ApiError(400, "Neplatný otisk obrázku")
    try:
        return [float(x) for x in value]
    except (TypeError, ValueError):
        raise ApiError(400, "Neplatný otisk obrázku")


def finders_by_sticker(conn):
    """Kdo už kterou rybku ulovil (přezdívky v pořadí úlovků)."""
    out = {}
    for r in conn.execute(
        """SELECT c.sticker_id, u.nick FROM catches c JOIN users u ON u.id = c.finder_id
           WHERE c.accepted = 1 ORDER BY c.time"""
    ):
        out.setdefault(r["sticker_id"], []).append(r["nick"])
    return out


def sticker_context(row):
    """Otisky okolí samolepky. Úplně staré samolepky je zvlášť nemají – jejich otisky jsou z celého snímku."""
    return json.loads(row["context"] or row["embeddings"])


def crop_context(crops):
    """Z otisků výřezů snímku vybere ty, které ukazují i okolí (starší aplikace posílala jen jeden)."""
    return crops[CONTEXT_CROPS] if len(crops) == 3 else crops


def keep_originals(shots, originals=2):
    """Nejvýš MAX_STICKER_SHOTS otisků; přebývají-li, odpadnou nejstarší z úlovků, otisky originálu zůstanou."""
    if len(shots) <= MAX_STICKER_SHOTS:
        return shots
    return shots[:originals] + shots[-(MAX_STICKER_SHOTS - originals):]


def context_similarity(context, shots):
    return max(cosine(c, s) for c in context for s in shots)


def reachable(conn, lat, lon, acc, exclude=None):
    """Aktivní rybky, které by šly ulovit z tohohle místa: tetování a předměty kdekoli, samolepky v dosahu.
    Bez polohy (nový předmět nebo tetování) všechny – i samolepka z ulice by jako „předmět“ byla duplikát."""
    rows = conn.execute(
        "SELECT s.*, a.nick AS author FROM stickers s JOIN users a ON a.id = s.author_id WHERE s.status = 'active'"
    ).fetchall()
    limit = MAX_DISTANCE_M + min(max(acc or 0, 0), 50)
    return [
        r for r in rows
        if r["id"] != exclude and (lat is None or r["env"] in PORTABLE or (
            r["lat"] is not None and distance_m(lat, lon, r["lat"], r["lon"]) <= limit))
    ]


def catch_points(env, rarity):
    return OBJECT_POINTS if env == OBJECT else RARITY_POINTS[rarity]


def author_points(env):
    return 0 if env == OBJECT else AUTHOR_POINTS


def pick_fish(env):
    options = FISH[env]
    weights = [RARITY_WEIGHT[r] for _, r in options]
    return random.choices(options, weights=weights)[0]


def level_threshold(level):
    return LEVEL_STEP * (level - 1) * level


def level_info(points):
    level = 1
    while points >= level_threshold(level + 1):
        level += 1
    return {
        "level": level,
        "title": LEVEL_TITLES[min(level, len(LEVEL_TITLES)) - 1],
        "points": points,
        "levelStart": level_threshold(level),
        "nextLevel": level_threshold(level + 1),
    }


def user_stats(conn, uid):
    caught = conn.execute(
        """SELECT s.fish, s.rarity, s.env, s.author_id, c.time, c.lat, c.lon,
                  c.time = (SELECT MIN(c2.time) FROM catches c2 WHERE c2.sticker_id = c.sticker_id AND c2.accepted = 1) AS first
           FROM catches c JOIN stickers s ON s.id = c.sticker_id
           WHERE c.finder_id = ? AND c.accepted = 1""",
        (uid,),
    ).fetchall()
    user = conn.execute("SELECT points, avatar FROM users WHERE id = ?", (uid,)).fetchone()
    stickers = conn.execute("SELECT COUNT(*) FROM stickers WHERE author_id = ?", (uid,)).fetchone()[0]
    best, fans = conn.execute(
        """SELECT MAX(n), SUM(n) FROM (SELECT COUNT(*) AS n FROM catches c JOIN stickers s ON s.id = c.sticker_id
           WHERE s.author_id = ? AND c.accepted = 1 GROUP BY s.id)""",
        (uid,),
    ).fetchone()
    times = [datetime.fromtimestamp(r["time"], LOCAL_TZ) for r in caught]
    days = Counter(t.date() for t in times)
    return {
        "catches": len(caught),
        "species": len({r["fish"] for r in caught}),
        "rare": sum(r["rarity"] in ("vzácná", "legendární") for r in caught),
        "legendary": sum(r["rarity"] == "legendární" for r in caught),
        "envs": len({r["env"] for r in caught}),
        "tattoos": sum(r["env"] == TATTOO for r in caught),
        "objects": sum(r["env"] == OBJECT for r in caught),
        "first_finds": sum(r["first"] for r in caught),
        "authors": len({r["author_id"] for r in caught}),
        "night": sum(t.hour >= 22 or t.hour < 4 for t in times),
        "morning": sum(5 <= t.hour < 8 for t in times),
        "days": len(days),
        "best_day": max(days.values(), default=0),
        "far": farthest_km([(r["lat"], r["lon"]) for r in caught if r["lat"] is not None]),
        "points": int(user["points"]),
        "avatar": int(bool(user["avatar"])),
        "stickers": stickers,
        "best_sticker": best or 0,
        "fans": fans or 0,
    }


def farthest_km(places):
    """Největší vzdálenost dvou úlovků v km (stačí zjistit, jestli přesáhne FAR_KM)."""
    best = 0.0
    for i, a in enumerate(places):
        for b in places[i + 1:]:
            best = max(best, distance_m(*a, *b) / 1000)
            if best >= FAR_KM:
                return FAR_KM
    return int(best)


def achievements(stats, unlocked_at=None):
    unlocked_at = unlocked_at or {}
    return [
        {"id": aid, "name": name, "description": desc, "metric": metric, "rarity": rarity,
         "badge": rarity in BADGE_RARITIES, "progress": min(stats[metric], target), "target": target,
         "unlocked": stats[metric] >= target, "unlockedAt": unlocked_at.get(aid)}
        for aid, name, desc, metric, target, rarity in ACHIEVEMENTS
    ]


def record_unlocks(conn, uid, stats):
    """Zapíše čas, kdy hráč úspěch poprvé splnil. Vrací {id úspěchu: čas}."""
    known = dict(conn.execute("SELECT achievement, time FROM unlocks WHERE user_id = ?", (uid,)).fetchall())
    now = time.time()
    for aid, _, _, metric, target, _ in ACHIEVEMENTS:
        if aid not in known and stats[metric] >= target:
            conn.execute("INSERT OR IGNORE INTO unlocks (user_id, achievement, time) VALUES (?, ?, ?)", (uid, aid, now))
            known[aid] = now
    return known


def showcase(achs, chosen):
    """Odznaky vedle jména: ty, které si hráč vybral, jinak poslední tři získané (při shodě vzácnější)."""
    earned = {a["id"]: a for a in achs if a["unlocked"] and a["badge"]}
    if chosen is not None:
        picked = [earned[i] for i in json.loads(chosen) if i in earned]
    else:
        picked = sorted(earned.values(), key=lambda a: (a["unlockedAt"] or 0, RARITIES.index(a["rarity"])), reverse=True)
    return [{k: a[k] for k in ("id", "name", "description", "metric", "rarity")} for a in picked[:SHOWCASE_SIZE]]


def avatar_path(user):
    # Jméno souboru se při každé změně fotky mění, takže ?v= stačí na obejití cache v telefonu.
    return f"/api/images/avatar/{user['id']}?v={user['avatar'][:-4]}" if user["avatar"] else None


def player_json(conn, user, with_achievements=False):
    """Veřejný profil hráče: úroveň, motto, profilovka, odznaky a pár čísel."""
    stats = user_stats(conn, user["id"])
    achs = achievements(stats, record_unlocks(conn, user["id"], stats))
    badges = showcase(achs, user["showcase"])
    out = {
        "nick": user["nick"], "motto": user["motto"], "avatar": avatar_path(user),
        "points": user["points"], "pointsText": fmt_points(user["points"]), "level": level_info(user["points"]),
        "badges": badges, "badgeCount": sum(a["unlocked"] and a["badge"] for a in achs),
        "catches": stats["catches"], "species": stats["species"], "stickers": stats["stickers"],
    }
    if with_achievements:
        shown = {b["id"] for b in badges}
        out["achievements"] = [{**a, "showcased": a["id"] in shown} for a in achs]
        out["showcaseAuto"] = user["showcase"] is None
    return out


def rybka_check(conn, embeddings):
    """Je na fotce rybka? Vrací (skóre, rozpoznáno) pro nejlepší z dodaných otisků (výřezů).
    Rybka = nejbližší pozitivní vzor má aspoň RYBKA_MIN_SIMILARITY a je blíž než všechny negativní.
    (None, True), dokud galerie nemá žádné pozitivní vzory."""
    rows = conn.execute("SELECT embedding, negative FROM refs").fetchall()
    positive = [json.loads(r["embedding"]) for r in rows if not r["negative"]]
    negative = [json.loads(r["embedding"]) for r in rows if r["negative"]]
    if not positive:
        return None, True
    best = None
    for e in embeddings:
        pos = max(cosine(e, x) for x in positive)
        neg = max((cosine(e, x) for x in negative), default=-1.0)
        candidate = (pos >= RYBKA_MIN_SIMILARITY and pos > neg, pos)
        best = candidate if best is None or candidate > best else best
    return best[1], best[0]


def learned_count(conn):
    return conn.execute("SELECT COUNT(*) FROM stickers").fetchone()[0]


def add_ref(conn, embedding, source, origin=None):
    conn.execute(
        "INSERT INTO refs (embedding, source, origin, created) VALUES (?, ?, ?, ?)",
        (json.dumps(rounded(embedding)), source, origin, time.time()),
    )


def rounded(embedding):
    return [round(x, 4) for x in embedding]


def save_image(data, name, key="image"):
    """Uloží JPEG poslaný jako base64; vrátí jméno souboru, nebo None, když žádný nepřišel."""
    raw = data.get(key)
    if not raw:
        return None
    try:
        jpeg = base64.b64decode(raw, validate=True)
    except (ValueError, TypeError):
        raise ApiError(400, "Neplatný obrázek")
    if not jpeg.startswith(b"\xff\xd8\xff") or len(jpeg) > MAX_IMAGE:
        raise ApiError(400, "Neplatný obrázek")
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    (IMAGES_DIR / f"{name}.jpg").write_bytes(jpeg)
    return f"{name}.jpg"


def fmt_points(points):
    return f"{points:g}".replace(".", ",")


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def sticker_json(row, include_location=False):
    out = {
        "id": row["id"],
        "name": row["name"],
        "status": row["status"],
        "fish": row["fish"],
        "fishName": row["fish_name"],
        "rarity": row["rarity"],
        "env": row["env"],
        "envName": ENVIRONMENTS.get(row["env"]),
        "tattoo": row["env"] == TATTOO,
        "portable": row["env"] in PORTABLE,
        "hint": row["hint"],
        "image": bool(row["image"]),
    }
    if include_location:
        out.update(lat=row["lat"], lon=row["lon"])
    return out


class Raw:
    """Odpověď, která není JSON (obrázek, HTML, přesměrování)."""

    def __init__(self, content_type, body, headers=None, status=200):
        self.content_type = content_type
        self.body = body
        self.headers = headers or {}
        self.status = status


def redirect(url):
    return Raw("text/plain; charset=utf-8", b"", {"Location": url, "Cache-Control": "no-cache"}, status=302)


_release = {"data": None, "etag": None, "next": 0.0}
_release_lock = threading.Lock()


def latest_release():
    """Nejnovější vydání z GitHubu: verze, datum, odkaz na APK, velikost a novinky. Ptá se nejvýš jednou
    za RELEASE_TTL vteřin, a to podmíněně (ETag) – odpověď „nic nového“ se GitHubu nepočítá do limitu
    60 dotazů za hodinu. Když GitHub neodpovídá nebo odmítne, vrátí poslední známé a zkusí to za minutu."""
    with _release_lock:
        if time.time() < _release["next"]:
            return _release["data"]
        etag = _release["etag"]
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "rybky-server"}
    if etag:
        headers["If-None-Match"] = etag
    data, wait = None, RELEASE_TTL
    try:
        request = urllib.request.Request(f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest", headers=headers)
        with urllib.request.urlopen(request, timeout=5) as response:
            r = json.load(response)
            etag = response.headers.get("ETag")
        apk = next((a for a in r.get("assets", []) if a["name"].endswith(".apk")), None)
        data = {"version": r["tag_name"].lstrip("v"), "published": r["published_at"], "notes": r.get("body") or "",
                "url": apk["browser_download_url"] if apk else r["html_url"],
                "file": apk["name"] if apk else None, "size": apk["size"] if apk else None}
    except urllib.error.HTTPError as e:
        if e.code != 304:  # 304 = beze změny
            print(f"GitHub: nejnovější verzi nejde zjistit: {e!r}", flush=True)
            wait = 60
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(f"GitHub: nejnovější verzi nejde zjistit: {e!r}", flush=True)
        wait = 60
    with _release_lock:
        if data:
            _release.update(data=data, etag=etag)
        _release["next"] = time.time() + wait
        return _release["data"]


def inline_markdown(text):
    """Tučné, `kód` a odkazy z Markdownu; všechno ostatní se escapuje."""
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"`(.+?)`", r"<code>\1</code>", text)
    return re.sub(r"\[(.+?)\]\((https?://[^\s)]+)\)", r'<a href="\2">\1</a>', text)


def plural(n, one, few, many):
    return one if n == 1 else few if 2 <= n <= 4 else many


class Handler(admin.AdminMixin, BaseHTTPRequestHandler):
    server_version = "Rybky/0.10"
    open_db = staticmethod(lambda: db())

    # --- infrastruktura -------------------------------------------------

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def dispatch(self, method):
        url = urlparse(self.path)
        if url.path == "/admin" or url.path.startswith("/admin/"):
            return self.admin(method, url.path)
        routes = [
            ("GET", r"/$", self.home),
            ("GET", r"/intro/?$", self.intro),
            ("GET", r"/intro/(icon|ryba)\.png$", self.intro_image),
            ("GET", r"/download/?$", self.download),
            ("POST", r"/api/register$", self.register),
            ("POST", r"/api/login$", self.login),
            ("POST", r"/api/password$", self.set_password),
            ("GET", r"/api/me$", self.me),
            ("POST", r"/api/profile$", self.update_profile),
            ("GET", r"/api/leaderboard$", self.leaderboard),
            ("GET", r"/api/environments$", self.environments),
            ("GET", r"/api/fish$", self.fish_atlas),
            ("GET", r"/api/references$", self.references),
            ("POST", r"/api/references/seed$", self.seed_references),
            ("POST", r"/api/stickers$", self.create_sticker),
            ("GET", r"/api/nearby$", self.nearby),
            ("POST", r"/api/scan$", self.scan),
            ("GET", r"/api/map$", self.map),
            ("GET", r"/api/images/(catch|sticker|avatar)/(\w+)$", self.image),
        ]
        try:
            for m, pattern, fn in routes:
                match = re.match(pattern, url.path)
                if m == method and match:
                    self.query = {k: v[0] for k, v in parse_qs(url.query).items()}
                    with db() as conn:
                        self.conn = conn
                        result = fn(*match.groups())
                    if isinstance(result, Raw):
                        return self.send_raw(result.status, result)
                    return self.send_json(200, result)
            raise ApiError(404, "Neznámá adresa")
        except ApiError as e:
            self.send_json(e.status, {"error": e.message})
        except Exception as e:  # noqa: BLE001 – vrátit chybu klientovi, ne spadnout
            self.log_error("chyba: %r", e)
            self.send_json(500, {"error": "Chyba serveru"})

    def send_json(self, status, payload, headers=None):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_raw(status, Raw("application/json; charset=utf-8", body, headers))

    def send_raw(self, status, raw):
        self.send_response(status)
        self.send_header("Content-Type", raw.content_type)
        self.send_header("Content-Length", str(len(raw.body)))
        for k, v in raw.headers.items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(raw.body)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        if length > MAX_BODY:
            raise ApiError(413, "Moc velký požadavek")
        try:
            return json.loads(self.rfile.read(length))
        except json.JSONDecodeError:
            raise ApiError(400, "Neplatný JSON")

    def user(self):
        auth = self.headers.get("Authorization", "")
        token = auth.removeprefix("Bearer ").strip()
        row = self.conn.execute("SELECT * FROM users WHERE token = ?", (token,)).fetchone()
        if not row:
            raise ApiError(401, "Nejsi přihlášený")
        return row

    @staticmethod
    def coords(data):
        try:
            lat, lon = float(data["lat"]), float(data["lon"])
            acc = float(data.get("accuracy") or 0)
        except (KeyError, TypeError, ValueError):
            raise ApiError(400, "Chybí poloha")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ApiError(400, "Neplatná poloha")
        return lat, lon, acc

    # --- úvodní stránka a stažení -----------------------------------------

    def home(self):
        return redirect("/intro")

    def download(self):
        """Vždy nejnovější APK – odkaz rybky.pravn.uk/download jde sdílet i dát do QR kódu."""
        release = latest_release()
        return redirect(release["url"] if release else RELEASES_URL + "/latest")

    def intro_image(self, name):
        return Raw("image/png", (WEB_DIR / f"{name}.png").read_bytes(), {"Cache-Control": "public, max-age=86400"})

    def intro(self):
        """Úvodní stránka hry: jak se hraje, bodování z nastavení serveru, pár živých čísel a nejnovější verze."""
        release = latest_release()
        one = lambda sql: self.conn.execute(sql).fetchone()[0]  # noqa: E731
        fish = one("SELECT COUNT(*) FROM stickers WHERE status = 'active'")
        players = one("SELECT COUNT(*) FROM users")
        catches = one("SELECT COUNT(*) FROM catches WHERE accepted = 1")
        meta, notes = ["nejnovější verze"], ""
        if release:
            published = datetime.fromisoformat(release["published"].replace("Z", "+00:00")).astimezone(LOCAL_TZ)
            meta = [f"verze {release['version']}", f"{published.day}. {published.month}. {published.year}"]
            if release["size"]:
                meta.insert(1, f"{release['size'] / 1e6:.0f} MB")
            notes = "".join(f"<li>{inline_markdown(line[2:].strip())}</li>"
                            for line in release["notes"].splitlines() if line.startswith(("- ", "* ")))
        css_names = dict(zip(RARITIES, ("common", "uncommon", "rare", "legendary")))
        total = sum(RARITY_WEIGHT.values())
        values = {
            "version": html.escape(release["version"]) if release else "",
            "download_meta": html.escape(" · ".join(meta)),
            "file": html.escape(release["file"] or "APK") if release else "APK",
            "notes": notes or "<li>Opravy a vylepšení.</li>",
            "releases_url": RELEASES_URL,
            "stats": "".join(f"<span><strong>{n}</strong> {label}</span>" for n, label in [
                (fish, plural(fish, "rybka ve hře", "rybky ve hře", "rybek ve hře")),
                (players, plural(players, "hráč", "hráči", "hráčů")),
                (catches, plural(catches, "úlovek", "úlovky", "úlovků")),
            ]),
            "species": str(sum(len(v) for v in FISH.values())),
            "achievements": str(len(ACHIEVEMENTS)),
            "rarities": "".join(
                f'<div class="rarity {css_names[r]}"><span class="pill">{r}</span>'
                f'<strong>{fmt_points(RARITY_POINTS[r])} b.</strong>'
                f'<span class="muted">zhruba {round(100 * RARITY_WEIGHT[r] / total)} % rybek</span></div>'
                for r in RARITIES
            ),
            "object_points": fmt_points(OBJECT_POINTS),
            "author_points": fmt_points(AUTHOR_POINTS),
            "levels": " → ".join(LEVEL_TITLES),
        }
        page = (WEB_DIR / "intro.html").read_text(encoding="utf-8")
        for key, value in values.items():
            page = page.replace("{{" + key + "}}", value)
        return Raw("text/html; charset=utf-8", page.encode(), {"Cache-Control": "no-cache"})

    # --- endpointy ------------------------------------------------------

    def register(self):
        data = self.body()
        nick = valid_nick(data)
        password = valid_password(data)
        token = secrets.token_urlsafe(24)
        try:
            self.conn.execute(
                "INSERT INTO users (nick, nick_key, password, token, created) VALUES (?, ?, ?, ?, ?)",
                (nick, nick_key(nick), hash_password(password), token, time.time()),
            )
        except sqlite3.IntegrityError:
            raise ApiError(409, "Tahle přezdívka už je obsazená. Jestli je tvoje, přihlas se.")
        return {"token": token, "nick": nick}

    def login(self):
        """Přihlášení přezdívkou a heslem. Staré účty bez hesla si první přihlášení heslem „zabere“."""
        data = self.body()
        password = valid_password(data)
        row = self.conn.execute(
            "SELECT * FROM users WHERE nick_key = ?", (nick_key(str(data.get("nick", ""))),)
        ).fetchone()
        if not row:
            raise ApiError(404, "Takovou přezdívku neznám")
        if row["password"] is None:
            self.conn.execute("UPDATE users SET password = ? WHERE id = ?", (hash_password(password), row["id"]))
        elif not check_password(password, row["password"]):
            raise ApiError(401, "Špatné heslo")
        return {"token": row["token"], "nick": row["nick"]}

    def set_password(self):
        u = self.user()
        self.conn.execute("UPDATE users SET password = ? WHERE id = ?", (hash_password(valid_password(self.body())), u["id"]))
        return {"ok": True}

    def me(self):
        u = self.user()
        aquarium = self.conn.execute(
            """SELECT s.*, c.time AS caught, c.id AS catch_id, c.image AS catch_image, a.nick AS author
               FROM catches c JOIN stickers s ON s.id = c.sticker_id JOIN users a ON a.id = s.author_id
               WHERE c.finder_id = ? AND c.accepted = 1 ORDER BY c.time DESC""",
            (u["id"],),
        ).fetchall()
        mine = self.conn.execute(
            """SELECT s.*, (SELECT COUNT(*) FROM catches c WHERE c.sticker_id = s.id AND c.accepted = 1) AS finds
               FROM stickers s WHERE s.author_id = ? ORDER BY s.created DESC""",
            (u["id"],),
        ).fetchall()
        finders = finders_by_sticker(self.conn)
        return {
            **player_json(self.conn, u, with_achievements=True),
            "hasPassword": u["password"] is not None,
            "aquarium": [
                {**sticker_json(r), "author": r["author"], "caught": r["caught"],
                 "catchId": r["catch_id"], "catchImage": bool(r["catch_image"])} for r in aquarium
            ],
            "stickers": [{**sticker_json(r), "finds": r["finds"], "finders": finders.get(r["id"], [])} for r in mine],
        }

    def update_profile(self):
        """Úprava profilu. Posílá se jen to, co se mění: nick, motto, avatar (JPEG v base64, "" = smazat),
        password, showcase (seznam id odznaků, null = automaticky poslední tři)."""
        u = self.user()
        data = self.body()
        if "nick" in data:
            nick = valid_nick(data)
            try:
                self.conn.execute("UPDATE users SET nick = ?, nick_key = ? WHERE id = ?", (nick, nick_key(nick), u["id"]))
            except sqlite3.IntegrityError:
                raise ApiError(409, "Tahle přezdívka už je obsazená")
        if "motto" in data:
            motto = str(data["motto"] or "").strip()
            if len(motto) > MOTTO_MAX:
                raise ApiError(400, f"Motto může mít nejvýš {MOTTO_MAX} znaků")
            self.conn.execute("UPDATE users SET motto = ? WHERE id = ?", (motto or None, u["id"]))
        if "password" in data:
            self.conn.execute("UPDATE users SET password = ? WHERE id = ?", (hash_password(valid_password(data)), u["id"]))
        if "avatar" in data:
            avatar = save_image(data, f"avatar-{u['id']}-{secrets.token_hex(4)}", key="avatar")
            if u["avatar"]:
                (IMAGES_DIR / u["avatar"]).unlink(missing_ok=True)
            self.conn.execute("UPDATE users SET avatar = ? WHERE id = ?", (avatar, u["id"]))
        if "showcase" in data:
            chosen = data["showcase"]
            if chosen is not None and (not isinstance(chosen, list) or len(chosen) > SHOWCASE_SIZE
                                       or not set(chosen) <= ACHIEVEMENT_IDS):
                raise ApiError(400, f"Vystavit jde nejvýš {SHOWCASE_SIZE} odznaky")
            self.conn.execute("UPDATE users SET showcase = ? WHERE id = ?",
                              (None if chosen is None else json.dumps(chosen), u["id"]))
        return self.me()

    def leaderboard(self):
        """Nejlepších 10 hráčů podle bodů. Když v nich nejsem, přidá se i moje pořadí."""
        u = self.user()

        def entry(row):
            rank = 1 + self.conn.execute("SELECT COUNT(*) FROM users WHERE points > ?", (row["points"],)).fetchone()[0]
            return {**player_json(self.conn, row), "rank": rank, "me": row["id"] == u["id"]}

        rows = self.conn.execute("SELECT * FROM users ORDER BY points DESC, created LIMIT ?", (LEADERBOARD_SIZE,)).fetchall()
        top = [entry(r) for r in rows]
        return {"top": top, "me": None if any(e["me"] for e in top) else entry(u),
                "players": self.conn.execute("SELECT COUNT(*) FROM users").fetchone()[0]}

    def environments(self):
        return [{"id": k, "name": v} for k, v in ENVIRONMENTS.items()]

    def fish_atlas(self):
        """Všechny druhy ryb ve hře: kolikrát je hráč ulovil a kolik jich je nalepených po světě."""
        u = self.user()
        caught = dict(self.conn.execute(
            """SELECT s.fish, COUNT(*) FROM catches c JOIN stickers s ON s.id = c.sticker_id
               WHERE c.finder_id = ? AND c.accepted = 1 GROUP BY s.fish""",
            (u["id"],),
        ).fetchall())
        in_world = dict(self.conn.execute(
            "SELECT fish, COUNT(*) FROM stickers WHERE status = 'active' GROUP BY fish"
        ).fetchall())
        return [
            {"name": name, "rarity": rarity, "env": env, "envName": ENVIRONMENTS[env],
             "points": catch_points(env, rarity), "caught": caught.get(name, 0), "inWorld": in_world.get(name, 0)}
            for env, options in FISH.items() for name, rarity in options
        ]

    def references(self):
        """Galerie vzorů pro kontrolu „je to rybka?“ přímo v telefonu."""
        self.user()
        rows = self.conn.execute("SELECT embedding, negative FROM refs").fetchall()
        learned = learned_count(self.conn)
        return {"minSimilarity": RYBKA_MIN_SIMILARITY,
                "embeddings": [json.loads(r["embedding"]) for r in rows if not r["negative"]],
                "negatives": [json.loads(r["embedding"]) for r in rows if r["negative"]],
                "learning": learned < LEARNING_STICKERS, "learned": learned, "learningTarget": LEARNING_STICKERS}

    def seed_references(self):
        """Výchozí vzory (obrázek rybky v různých barvách a natočeních) – jen do prázdné galerie.
        Otisky počítá telefon, protože server model nemá."""
        self.user()
        if self.conn.execute("SELECT 1 FROM refs WHERE source = 'seed'").fetchone():
            return {"seeded": False}
        shots = self.body().get("embeddings")
        if not isinstance(shots, list) or not 1 <= len(shots) <= 100:
            raise ApiError(400, "Chybí vzory")
        for e in shots:
            add_ref(self.conn, parse_embedding(e), "seed")
        return {"seeded": True}

    def create_sticker(self):
        """Nalepení: autor samolepku vyfotí (stačí jednou), telefon pošle otisky několika výřezů fotky,
        fotku a polohu. Tetování a předměty polohu nemají; u tetování je místo názvu jméno člověka."""
        u = self.user()
        data = self.body()
        env = data.get("env")
        if env not in ENVIRONMENTS:
            raise ApiError(400, "Vyber prostředí")
        if env in PORTABLE:
            lat = lon = acc = None
        else:
            lat, lon, acc = self.coords(data)
            if acc > 100:
                raise ApiError(422, f"GPS je moc nepřesná ({acc:.0f} m). Zkus to venku.")
        shots = data.get("embeddings")
        if not isinstance(shots, list) or not 1 <= len(shots) <= 10:
            raise ApiError(400, "Samolepku nejdřív vyfoť")
        embeddings = [parse_embedding(e) for e in shots]
        if len({len(e) for e in embeddings}) != 1:
            raise ApiError(400, "Neplatný otisk obrázku")
        if learned_count(self.conn) >= LEARNING_STICKERS and not rybka_check(self.conn, embeddings)[1]:
            raise ApiError(422, "Na fotce není rybka z Rybek. Vyfoť ji zblízka.")
        # Stejnou rybku nejde přidat dvakrát: když by ji lov poznal jako už existující, je to duplikát.
        # Stejný motiv na jiném místě (nebo jiném předmětu) projde, protože se liší okolím.
        context = crop_context(embeddings)
        for r in reachable(self.conn, lat, lon, acc):
            if context_similarity(context, sticker_context(r)) >= CONTEXT_MIN_SIMILARITY:
                what = {TATTOO: "Tohle tetování", OBJECT: "Tenhle předmět"}.get(r["env"], "Tahle samolepka")
                raise ApiError(409, f"{what} už ve hře je – „{r['name']}“, přidal(a) {r['author']}. "
                                    "Stejnou rybku dvakrát přidat nejde.")
        name = str(data.get("name") or "").strip()
        if not 2 <= len(name) <= 40:
            raise ApiError(400, {TATTOO: "Napiš jméno osoby (2 až 40 znaků)",
                                 OBJECT: "Napiš, co to je (2 až 40 znaků)"}.get(env, "Pojmenuj samolepku (2 až 40 znaků)"))
        hint = str(data.get("hint") or "").strip()[:120] or None
        fish, rarity = pick_fish(env)
        for _ in range(10):
            sid = "".join(secrets.choice(ID_ALPHABET) for _ in range(8))
            if not self.conn.execute("SELECT 1 FROM stickers WHERE id = ?", (sid,)).fetchone():
                break
        image = save_image(data, f"sticker-{sid}")
        now = time.time()
        self.conn.execute(
            """INSERT INTO stickers (id, author_id, name, fish, fish_name, rarity, env, hint, lat, lon, accuracy,
                                     embeddings, context, image, created, activated)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (sid, u["id"], name, fish, new_fish_name(self.conn), rarity, env, hint, lat, lon, acc,
             json.dumps([rounded(e) for e in embeddings]), json.dumps([rounded(e) for e in context]), image, now, now),
        )
        for e in embeddings:
            add_ref(self.conn, e, "sticker", f"sticker:{sid}")
        # Kolik dostane lovec a kolik autor za každý úlovek – ukáže se hned po nalepení.
        return {**sticker_json(self.conn.execute("SELECT * FROM stickers WHERE id = ?", (sid,)).fetchone()),
                "catchPoints": catch_points(env, rarity), "catchPointsText": fmt_points(catch_points(env, rarity)),
                "authorPoints": author_points(env)}

    def nearby(self):
        """Otisky samolepek v okolí a všech tetování a předmětů pro rozpoznávání v kameře. Polohu neprozrazuje.
        S ?context=1 (aplikace od 0.11) posílá místo všech otisků jen otisky okolí."""
        u = self.user()
        try:
            lat, lon = float(self.query["lat"]), float(self.query["lon"])
        except (KeyError, ValueError):
            raise ApiError(400, "Chybí poloha")
        rows = self.conn.execute(
            """SELECT s.id, s.name, s.env, s.rarity, s.fish_name, s.lat, s.lon, s.embeddings, s.context, s.author_id,
                      EXISTS(SELECT 1 FROM catches c WHERE c.sticker_id = s.id AND c.finder_id = ? AND c.accepted = 1) AS caught
               FROM stickers s WHERE s.status = 'active'""",
            (u["id"],),
        ).fetchall()
        if self.query.get("context"):
            shots = lambda r: {"context": sticker_context(r), "minSimilarity": CONTEXT_MIN_SIMILARITY, "margin": MATCH_MARGIN}
        else:
            shots = lambda r: {"embeddings": json.loads(r["embeddings"]),
                               "minSimilarity": MIN_SIMILARITY_PORTABLE if r["env"] in PORTABLE else MIN_SIMILARITY}
        return [
            {"id": r["id"], **shots(r),
             "kind": r["env"] if r["env"] in PORTABLE else "sticker",
             "tattoo": r["env"] == TATTOO,
             "name": r["name"] if r["env"] in PORTABLE else None,
             "rarity": r["rarity"],
             "fishName": r["fish_name"] if r["caught"] or r["author_id"] == u["id"] else None,
             "own": r["author_id"] == u["id"], "caught": bool(r["caught"])}
            for r in rows
            if r["env"] in PORTABLE or distance_m(lat, lon, r["lat"], r["lon"]) <= NEARBY_M
        ]

    def scan(self):
        """Úlovek. Aplikace od 0.11 posílá otisky všech výřezů snímku (embeddings), starší jen jeden (embedding)."""
        u = self.user()
        data = self.body()
        sid = str(data.get("id", "")).strip().upper()
        legacy = not isinstance(data.get("embeddings"), list)
        if legacy:
            crops = [parse_embedding(data.get("embedding"))]
        elif 1 <= len(data["embeddings"]) <= 5:
            crops = [parse_embedding(e) for e in data["embeddings"]]
        else:
            raise ApiError(400, "Neplatný otisk obrázku")
        row = self.conn.execute(
            "SELECT s.*, a.nick AS author FROM stickers s JOIN users a ON a.id = s.author_id WHERE s.id = ?",
            (sid,),
        ).fetchone()
        if not row:
            raise ApiError(404, "Takovou samolepku neznám")
        if row["author_id"] == u["id"]:
            return {"result": "own", "sticker": sticker_json(row), "message": "Tohle je tvoje samolepka."}
        if row["status"] != "active":
            raise ApiError(409, "Tahle samolepka už tu není")
        portable = row["env"] in PORTABLE
        if portable:
            dist = None
            try:  # poloha u tetování není nutná, ale hodí se na porovnání se samolepkami v okolí
                lat, lon, acc = self.coords(data)
            except ApiError:
                lat = lon = acc = None
        else:
            lat, lon, acc = self.coords(data)
            dist = distance_m(lat, lon, row["lat"], row["lon"])
        shots = json.loads(row["embeddings"])
        learning = learned_count(self.conn) < LEARNING_STICKERS
        context = crop_context(crops)
        if legacy:
            similarity = max(cosine(crops[0], e) for e in shots)
            min_similarity = MIN_SIMILARITY_PORTABLE if portable else MIN_SIMILARITY
            rival = -1.0
            # U tetování a předmětů s mírnější hranicí musí fotka navíc vypadat jako rybka.
            fish = not portable or learning or rybka_check(self.conn, crops)[1]
        else:
            # Která rybka to je, rozhoduje okolí; a musí být jasně podobnější než ostatní rybky v dosahu
            # (i vlastní a už ulovené), jinak by stejný motiv jinde šel ulovit místo ní.
            similarity = context_similarity(context, sticker_context(row))
            min_similarity = CONTEXT_MIN_SIMILARITY
            rival = max((context_similarity(context, sticker_context(r))
                         for r in reachable(self.conn, lat, lon, acc, exclude=sid)), default=-1.0)
            fish = learning or rybka_check(self.conn, crops)[1]
        limit = None if portable else MAX_DISTANCE_M + min(max(acc, 0), 50)
        near = portable or dist <= limit
        already = self.conn.execute(
            "SELECT 1 FROM catches WHERE sticker_id = ? AND finder_id = ? AND accepted = 1", (sid, u["id"])
        ).fetchone()
        unique = similarity - rival >= MATCH_MARGIN
        accepted = near and fish and similarity >= min_similarity and unique and not already
        self.before_stats = user_stats(self.conn, u["id"]) if accepted else None
        points = catch_points(row["env"], row["rarity"]) if accepted else 0
        catch_id = self.conn.execute(
            """INSERT INTO catches (sticker_id, finder_id, lat, lon, accuracy, distance, similarity, accepted, points, time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (sid, u["id"], lat, lon, acc, dist, round(similarity, 4), int(accepted), points, time.time()),
        ).lastrowid
        # Přesnou vzdálenost nevracíme – šlo by z ní dopočítat polohu samolepky.
        base = {"sticker": sticker_json(row), "author": row["author"]}
        if already:
            return {**base, "result": "already", "message": "Tuhle rybu už máš v akváriu."}
        if not near:
            return {**base, "result": "too_far",
                    "message": "Tahle samolepka visí jinde. Jdi blíž, nebo ji někdo přesunul."}
        if not fish:
            return {**base, "result": "no_match", "message": "Na fotce není vidět rybka. Zaměř samolepku do čtverce."}
        if not unique and similarity >= min_similarity:
            return {**base, "result": "no_match",
                    "message": "Podobá se to víc rybkám najednou. Ustup, ať je vidět i okolí samolepky."}
        if not accepted:
            return {**base, "result": "no_match", "message": "Obrázek nesedí. Zkus samolepku vyfotit i s okolím."}
        # Fotka úlovku jde do akvária. Motiv (nejužší výřez) jde mezi vzory rybky, okolí k samolepce,
        # ať se pozná z víc úhlů; co se přidalo, si úlovek pamatuje, aby to admin mohl vzít zpět.
        image = save_image(data, f"catch-{catch_id}")
        added = [] if legacy else [rounded(c) for c in context]
        self.conn.execute("UPDATE catches SET image = ?, context = ? WHERE id = ?", (image, json.dumps(added), catch_id))
        add_ref(self.conn, crops[0], "catch", f"catch:{catch_id}")
        self.conn.execute(
            "UPDATE stickers SET embeddings = ?, context = ? WHERE id = ?",
            (json.dumps((shots + [rounded(crops[0])])[-MAX_STICKER_SHOTS:]),
             json.dumps(keep_originals(sticker_context(row) + added)), sid),
        )
        self.conn.execute("UPDATE users SET points = points + ? WHERE id = ?", (points, u["id"]))
        self.conn.execute("UPDATE users SET points = points + ? WHERE id = ?",
                          (author_points(row["env"]), row["author_id"]))
        # Úlovek je už zapsaný výš, takže se dá porovnat stav před ním a po něm.
        before = {a["id"] for a in achievements(self.before_stats) if a["unlocked"]}
        after = user_stats(self.conn, u["id"])
        new = [a for a in achievements(after, record_unlocks(self.conn, u["id"], after))
               if a["unlocked"] and a["id"] not in before]
        old_level = level_info(u["points"])["level"]
        level = level_info(u["points"] + points)
        fish = f"{row['fish_name']} ({row['fish']}, {row['rarity']})"
        if row["env"] == TATTOO:
            message = f"Ulovil(a) jsi rybu {fish} z tetování, které nosí {row['name']}!"
        elif row["env"] == OBJECT:
            message = f"Ulovil(a) jsi rybu {fish} z předmětu „{row['name']}“."
        else:
            message = f"Ulovil(a) jsi rybu {fish}! Samolepku nalepil(a) {row['author']}."
        return {**base, "result": "caught", "points": points, "pointsText": fmt_points(points), "catchId": catch_id,
                "level": level, "levelUp": level["level"] > old_level, "newAchievements": new, "message": message}

    def image(self, kind, ident):
        """Fotka úlovku (jen pro toho, kdo ho ulovil) nebo samolepky (jen pro autora)."""
        u = self.user()
        if kind == "catch":
            row = self.conn.execute("SELECT image FROM catches WHERE id = ? AND finder_id = ?", (ident, u["id"])).fetchone()
        elif kind == "avatar":
            # Profilovky vidí všichni (žebříček).
            row = self.conn.execute("SELECT avatar AS image FROM users WHERE id = ?", (ident,)).fetchone()
        else:
            row = self.conn.execute("SELECT image FROM stickers WHERE id = ? AND author_id = ?", (ident, u["id"])).fetchone()
        if not row or not row["image"]:
            raise ApiError(404, "Fotka není")
        return Raw("image/jpeg", (IMAGES_DIR / row["image"]).read_bytes(), {"Cache-Control": "private, max-age=86400"})

    def map(self):
        """Všechny samolepky na světě (kruhy s posunutým středem). Volitelně jen do `radius` metrů."""
        u = self.user()
        try:
            lat, lon = float(self.query["lat"]), float(self.query["lon"])
        except (KeyError, ValueError):
            raise ApiError(400, "Chybí poloha")
        try:
            radius = float(self.query["radius"]) if "radius" in self.query else math.inf
        except ValueError:
            raise ApiError(400, "Neplatný poloměr")
        rows = self.conn.execute(
            """SELECT s.*, a.nick AS author,
                      EXISTS(SELECT 1 FROM catches c WHERE c.sticker_id = s.id AND c.finder_id = ? AND c.accepted = 1) AS caught
               FROM stickers s JOIN users a ON a.id = s.author_id
               WHERE s.status = 'active' AND s.lat IS NOT NULL""",
            (u["id"],),
        ).fetchall()
        finders = finders_by_sticker(self.conn)
        out = []
        for r in rows:
            clat, clon = fuzzed_center(r["id"], r["lat"], r["lon"])
            d = distance_m(lat, lon, clat, clon)
            if d > radius:
                continue
            own = r["author_id"] == u["id"]
            known = bool(r["caught"]) or own
            out.append({
                "id": r["id"],
                "lat": clat, "lon": clon, "radius": MAP_CIRCLE_M,
                "distance": round(d),
                "bearing": round(math.degrees(math.atan2(
                    math.sin(math.radians(clon - lon)) * math.cos(math.radians(clat)),
                    math.cos(math.radians(lat)) * math.sin(math.radians(clat))
                    - math.sin(math.radians(lat)) * math.cos(math.radians(clat)) * math.cos(math.radians(clon - lon)),
                )) % 360),
                "envName": ENVIRONMENTS.get(r["env"]),
                "rarity": r["rarity"],
                "name": r["name"],
                "fish": r["fish"] if known else None,
                "fishName": r["fish_name"] if known else None,
                "hint": r["hint"],
                "author": r["author"],
                "caught": bool(r["caught"]),
                "finders": finders.get(r["id"], []),
                "own": own,
            })
        out.sort(key=lambda x: x["distance"])
        return out


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    init_db()
    admin.configure(db=db, images_dir=IMAGES_DIR, catch_points=catch_points, author_points=author_points,
                    new_fish_name=new_fish_name, level_info=level_info)
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Rybky backend běží na http://0.0.0.0:{port}  (databáze {DB_PATH})")
    server.serve_forever()


if __name__ == "__main__":
    main()
