#!/usr/bin/env python3
"""Rybky – MVP backend (jen standardní knihovna Pythonu + SQLite).

Spuštění:  python3 server.py [port]        (výchozí port 8000)
Telefon přes USB:  adb reverse tcp:8000 tcp:8000
"""
import hashlib
import json
import math
import os
import random
import re
import secrets
import sqlite3
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

DB_PATH = Path(os.environ.get("RYBKY_DB") or Path(__file__).with_name("rybky.db"))

# Nález se uzná do této vzdálenosti od místa aktivace (+ nepřesnost GPS nálezce, max. 50 m).
MAX_DISTANCE_M = 50
# Na mapě se ukazuje jen kruh, ve kterém samolepka je.
MAP_CIRCLE_M = 120
# Do této vzdálenosti dostane telefon otisky samolepek, aby je mohl rozpoznávat v kameře.
NEARBY_M = 300
# Minimální kosinová podobnost fotky nálezce s naučenými fotkami samolepky.
MIN_SIMILARITY = 0.75
# Tetování je na kůži a pokaždé v jiném světle a úhlu – stačí menší shoda (ale musí projít kontrolou „je to rybka“).
MIN_SIMILARITY_TATTOO = 0.65
# Kolik fotek si samolepka pamatuje; každý úlovek přidá jednu, aby se poznávala z víc úhlů.
MAX_STICKER_SHOTS = 20
# Minimální podobnost s galerií vzorů, aby fotka prošla jako „rybka z Rybek“ (ne živá ryba, zeď…).
RYBKA_MIN_SIMILARITY = 0.6
# Rybek je víc druhů obrázků: prvních tolik samolepek/tetování se nekontroluje, jen se z nich galerie učí.
LEARNING_STICKERS = 10

RARITY_WEIGHT = {"běžná": 60, "nevšední": 25, "vzácná": 12, "legendární": 3}
RARITY_POINTS = {"běžná": 10, "nevšední": 20, "vzácná": 40, "legendární": 100}
AUTHOR_POINTS = 5

ENVIRONMENTS = {
    "voda": "U vody",
    "park": "Park a příroda",
    "mesto": "Město",
    "uvnitr": "Vnitřní prostory",
    "vrchol": "Vrchol a výška",
    "tetovani": "Tetování",
}
# Tetování nemá polohu – nosí ho člověk s sebou. Ulovit jde kdekoli, jen podle obrázku.
TATTOO = "tetovani"
FISH = {
    "voda": [("Kapr", "běžná"), ("Pstruh", "nevšední"), ("Štika", "vzácná"), ("Vyza", "legendární")],
    "park": [("Karas", "běžná"), ("Lín", "nevšední"), ("Úhoř", "vzácná")],
    "mesto": [("Městská sardinka", "běžná"), ("Tramvajová plotice", "nevšední"), ("Kanálový sumec", "vzácná")],
    "uvnitr": [("Gupka", "běžná"), ("Neonka", "nevšední"), ("Skalára", "vzácná")],
    "vrchol": [("Horská slunka", "nevšední"), ("Létající ryba", "legendární")],
    "tetovani": [("Inkoustová rybka", "nevšední"), ("Tetovaný kapr", "vzácná"), ("Živá legenda", "legendární")],
}

ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

# Úroveň L začíná na LEVEL_STEP * (L-1) * L bodech: 2 → 100, 3 → 300, 4 → 600, 5 → 1000 …
LEVEL_STEP = 50
LEVEL_TITLES = ["Jikra", "Plůdek", "Rybička", "Rybář", "Zkušený rybář", "Mistr rybář", "Kapitán", "Vodník"]

# (id, název, popis, metrika ze user_stats, cíl)
ACHIEVEMENTS = [
    ("prvni_ulovek", "První úlovek", "Ulov svou první rybu", "catches", 1),
    ("rybar", "Rybář", "Ulov 10 ryb", "catches", 10),
    ("mistr_rybar", "Mistr rybář", "Ulov 50 ryb", "catches", 50),
    ("sberatel", "Sběratel", "Ulov 5 různých druhů", "species", 5),
    ("atlas", "Kompletní atlas", "Ulov všechny druhy ryb", "species", sum(len(v) for v in FISH.values())),
    ("vzacny_kus", "Vzácný kus", "Ulov vzácnou nebo legendární rybu", "rare", 1),
    ("legenda", "Legenda", "Ulov legendární rybu", "legendary", 1),
    ("cestovatel", "Cestovatel", "Ulov rybu ve všech prostředích", "envs", len(ENVIRONMENTS)),
    ("inkoust", "Lovec inkoustu", "Ulov rybu z tetování", "tattoos", 1),
    ("lepic", "Lepič", "Nalep první samolepku", "stickers", 1),
    ("pilny_lepic", "Pilný lepič", "Nalep 10 samolepek", "stickers", 10),
    ("oblibene_misto", "Oblíbené místo", "Tvoji samolepku ulovilo 5 lidí", "best_sticker", 5),
]


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
            CREATE UNIQUE INDEX IF NOT EXISTS one_catch
                ON catches(sticker_id, finder_id) WHERE accepted = 1;
            """
        )
        columns = {r["name"] for r in conn.execute("PRAGMA table_info(stickers)")}
        if "name" not in columns:
            conn.execute("ALTER TABLE stickers ADD COLUMN name TEXT")
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


def nick_key(nick):
    return nick.strip().casefold()


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
        """SELECT s.fish, s.rarity, s.env FROM catches c JOIN stickers s ON s.id = c.sticker_id
           WHERE c.finder_id = ? AND c.accepted = 1""",
        (uid,),
    ).fetchall()
    stickers = conn.execute("SELECT COUNT(*) FROM stickers WHERE author_id = ?", (uid,)).fetchone()[0]
    best = conn.execute(
        """SELECT MAX(n) FROM (SELECT COUNT(*) AS n FROM catches c JOIN stickers s ON s.id = c.sticker_id
           WHERE s.author_id = ? AND c.accepted = 1 GROUP BY s.id)""",
        (uid,),
    ).fetchone()[0]
    return {
        "catches": len(caught),
        "species": len({r["fish"] for r in caught}),
        "rare": sum(r["rarity"] in ("vzácná", "legendární") for r in caught),
        "legendary": sum(r["rarity"] == "legendární" for r in caught),
        "envs": len({r["env"] for r in caught}),
        "tattoos": sum(r["env"] == TATTOO for r in caught),
        "stickers": stickers,
        "best_sticker": best or 0,
    }


def achievements(stats):
    return [
        {"id": aid, "name": name, "description": desc, "metric": metric,
         "progress": min(stats[metric], target), "target": target, "unlocked": stats[metric] >= target}
        for aid, name, desc, metric, target in ACHIEVEMENTS
    ]


def rybka_similarity(conn, embedding):
    """Jak moc se fotka podobá nejbližšímu vzoru v galerii. None, dokud galerie není naplněná."""
    rows = conn.execute("SELECT embedding FROM refs").fetchall()
    if not rows:
        return None
    return max(cosine(embedding, json.loads(r["embedding"])) for r in rows)


def learned_count(conn):
    return conn.execute("SELECT COUNT(*) FROM stickers").fetchone()[0]


def add_ref(conn, embedding, source):
    conn.execute(
        "INSERT INTO refs (embedding, source, created) VALUES (?, ?, ?)",
        (json.dumps([round(x, 4) for x in embedding]), source, time.time()),
    )


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
        "rarity": row["rarity"],
        "env": row["env"],
        "envName": ENVIRONMENTS.get(row["env"]),
        "tattoo": row["env"] == TATTOO,
        "hint": row["hint"],
    }
    if include_location:
        out.update(lat=row["lat"], lon=row["lon"])
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "Rybky/0.1"

    # --- infrastruktura -------------------------------------------------

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def dispatch(self, method):
        url = urlparse(self.path)
        routes = [
            ("POST", r"/api/register$", self.register),
            ("POST", r"/api/login$", self.login),
            ("POST", r"/api/password$", self.set_password),
            ("GET", r"/api/me$", self.me),
            ("GET", r"/api/environments$", self.environments),
            ("GET", r"/api/fish$", self.fish_atlas),
            ("GET", r"/api/references$", self.references),
            ("POST", r"/api/references/seed$", self.seed_references),
            ("POST", r"/api/stickers$", self.create_sticker),
            ("GET", r"/api/nearby$", self.nearby),
            ("POST", r"/api/scan$", self.scan),
            ("GET", r"/api/map$", self.map),
        ]
        try:
            for m, pattern, fn in routes:
                match = re.match(pattern, url.path)
                if m == method and match:
                    self.query = {k: v[0] for k, v in parse_qs(url.query).items()}
                    with db() as conn:
                        self.conn = conn
                        result = fn(*match.groups())
                    return self.send_json(200, result)
            raise ApiError(404, "Neznámá adresa")
        except ApiError as e:
            self.send_json(e.status, {"error": e.message})
        except Exception as e:  # noqa: BLE001 – vrátit chybu klientovi, ne spadnout
            self.log_error("chyba: %r", e)
            self.send_json(500, {"error": "Chyba serveru"})

    def send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
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

    # --- endpointy ------------------------------------------------------

    def register(self):
        data = self.body()
        nick = str(data.get("nick", "")).strip()
        if not 3 <= len(nick) <= 20:
            raise ApiError(400, "Přezdívka musí mít 3 až 20 znaků")
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
            """SELECT s.*, c.time AS caught, a.nick AS author
               FROM catches c JOIN stickers s ON s.id = c.sticker_id JOIN users a ON a.id = s.author_id
               WHERE c.finder_id = ? AND c.accepted = 1 ORDER BY c.time DESC""",
            (u["id"],),
        ).fetchall()
        mine = self.conn.execute(
            """SELECT s.*, (SELECT COUNT(*) FROM catches c WHERE c.sticker_id = s.id AND c.accepted = 1) AS finds
               FROM stickers s WHERE s.author_id = ? ORDER BY s.created DESC""",
            (u["id"],),
        ).fetchall()
        return {
            "nick": u["nick"],
            "hasPassword": u["password"] is not None,
            "points": u["points"],
            "level": level_info(u["points"]),
            "achievements": achievements(user_stats(self.conn, u["id"])),
            "aquarium": [
                {**sticker_json(r), "author": r["author"], "caught": r["caught"]} for r in aquarium
            ],
            "stickers": [{**sticker_json(r), "finds": r["finds"]} for r in mine],
        }

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
             "points": RARITY_POINTS[rarity], "caught": caught.get(name, 0), "inWorld": in_world.get(name, 0)}
            for env, options in FISH.items() for name, rarity in options
        ]

    def references(self):
        """Galerie vzorů pro kontrolu „je to rybka?“ přímo v telefonu."""
        self.user()
        rows = self.conn.execute("SELECT embedding FROM refs").fetchall()
        learned = learned_count(self.conn)
        return {"minSimilarity": RYBKA_MIN_SIMILARITY, "embeddings": [json.loads(r["embedding"]) for r in rows],
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
        """Nalepení: autor samolepku vyfotí (stačí jednou), telefon pošle otisk fotky a polohu.
        Tetování polohu nemá, místo názvu má jméno člověka, který ho nosí."""
        u = self.user()
        data = self.body()
        env = data.get("env")
        if env not in ENVIRONMENTS:
            raise ApiError(400, "Vyber prostředí")
        if env == TATTOO:
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
        learning = learned_count(self.conn) < LEARNING_STICKERS
        rybka = [] if learning else [rybka_similarity(self.conn, e) for e in embeddings]
        if any(r is not None and r < RYBKA_MIN_SIMILARITY for r in rybka):
            raise ApiError(422, "Na fotce není rybka z Rybek. Vyfoť samolepku nebo tetování zblízka.")
        name = str(data.get("name") or "").strip()
        if not 2 <= len(name) <= 40:
            raise ApiError(400, "Napiš jméno osoby (2 až 40 znaků)" if env == TATTOO
                           else "Pojmenuj samolepku (2 až 40 znaků)")
        hint = str(data.get("hint") or "").strip()[:120] or None
        fish, rarity = pick_fish(env)
        for _ in range(10):
            sid = "".join(secrets.choice(ID_ALPHABET) for _ in range(8))
            if not self.conn.execute("SELECT 1 FROM stickers WHERE id = ?", (sid,)).fetchone():
                break
        now = time.time()
        self.conn.execute(
            """INSERT INTO stickers (id, author_id, name, fish, rarity, env, hint, lat, lon, accuracy,
                                     embeddings, created, activated)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (sid, u["id"], name, fish, rarity, env, hint, lat, lon, acc, json.dumps(embeddings), now, now),
        )
        for e in embeddings:
            add_ref(self.conn, e, "sticker")
        return sticker_json(self.conn.execute("SELECT * FROM stickers WHERE id = ?", (sid,)).fetchone())

    def nearby(self):
        """Otisky samolepek v okolí a všech tetování pro rozpoznávání v kameře. Polohu neprozrazuje."""
        u = self.user()
        try:
            lat, lon = float(self.query["lat"]), float(self.query["lon"])
        except (KeyError, ValueError):
            raise ApiError(400, "Chybí poloha")
        rows = self.conn.execute(
            """SELECT s.id, s.name, s.env, s.lat, s.lon, s.embeddings, s.author_id,
                      EXISTS(SELECT 1 FROM catches c WHERE c.sticker_id = s.id AND c.finder_id = ? AND c.accepted = 1) AS caught
               FROM stickers s WHERE s.status = 'active'""",
            (u["id"],),
        ).fetchall()
        return [
            {"id": r["id"], "embeddings": json.loads(r["embeddings"]),
             "tattoo": r["env"] == TATTOO,
             "minSimilarity": MIN_SIMILARITY_TATTOO if r["env"] == TATTOO else MIN_SIMILARITY, "name": r["name"] if r["env"] == TATTOO else None,
             "own": r["author_id"] == u["id"], "caught": bool(r["caught"])}
            for r in rows
            if r["env"] == TATTOO or distance_m(lat, lon, r["lat"], r["lon"]) <= NEARBY_M
        ]

    def scan(self):
        u = self.user()
        data = self.body()
        sid = str(data.get("id", "")).strip().upper()
        embedding = parse_embedding(data.get("embedding"))
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
        tattoo = row["env"] == TATTOO
        if tattoo:
            lat = lon = acc = dist = None
        else:
            lat, lon, acc = self.coords(data)
            dist = distance_m(lat, lon, row["lat"], row["lon"])
        shots = json.loads(row["embeddings"])
        similarity = max(cosine(embedding, e) for e in shots)
        if tattoo and not learned_count(self.conn) < LEARNING_STICKERS:
            # U tetování s mírnější hranicí musí fotka navíc vypadat jako rybka.
            rybka = rybka_similarity(self.conn, embedding)
            if rybka is not None and rybka < RYBKA_MIN_SIMILARITY:
                similarity = 0.0
        min_similarity = MIN_SIMILARITY_TATTOO if tattoo else MIN_SIMILARITY
        limit = None if tattoo else MAX_DISTANCE_M + min(max(acc, 0), 50)
        near = tattoo or dist <= limit
        already = self.conn.execute(
            "SELECT 1 FROM catches WHERE sticker_id = ? AND finder_id = ? AND accepted = 1", (sid, u["id"])
        ).fetchone()
        accepted = near and similarity >= min_similarity and not already
        self.before_stats = user_stats(self.conn, u["id"]) if accepted else None
        self.conn.execute(
            """INSERT INTO catches (sticker_id, finder_id, lat, lon, accuracy, distance, accepted, time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (sid, u["id"], lat, lon, acc, dist, int(accepted), time.time()),
        )
        # Přesnou vzdálenost nevracíme – šlo by z ní dopočítat polohu samolepky.
        base = {"sticker": sticker_json(row), "author": row["author"]}
        if already:
            return {**base, "result": "already", "message": "Tuhle rybu už máš v akváriu."}
        if not near:
            return {**base, "result": "too_far",
                    "message": "Tahle samolepka visí jinde. Jdi blíž, nebo ji někdo přesunul."}
        if not accepted:
            return {**base, "result": "no_match", "message": "Obrázek nesedí. Zkus samolepku vyfotit zblízka."}
        add_ref(self.conn, embedding, "catch")
        self.conn.execute(
            "UPDATE stickers SET embeddings = ? WHERE id = ?",
            (json.dumps((shots + [[round(x, 4) for x in embedding]])[-MAX_STICKER_SHOTS:]), sid),
        )
        points = RARITY_POINTS[row["rarity"]]
        self.conn.execute("UPDATE users SET points = points + ? WHERE id = ?", (points, u["id"]))
        self.conn.execute("UPDATE users SET points = points + ? WHERE id = ?", (AUTHOR_POINTS, row["author_id"]))
        # Úlovek je už zapsaný výš, takže se dá porovnat stav před ním a po něm.
        before = {a["id"] for a in achievements(self.before_stats) if a["unlocked"]}
        new = [a for a in achievements(user_stats(self.conn, u["id"])) if a["unlocked"] and a["id"] not in before]
        old_level = level_info(u["points"])["level"]
        level = level_info(u["points"] + points)
        return {**base, "result": "caught", "points": points,
                "level": level, "levelUp": level["level"] > old_level, "newAchievements": new,
                "message": f"Ulovil(a) jsi rybu {row['fish']} ({row['rarity']}) z tetování, které nosí {row['name']}!"
                if tattoo else
                f"Ulovil(a) jsi rybu {row['fish']} ({row['rarity']})! Samolepku nalepil(a) {row['author']}."}

    def map(self):
        u = self.user()
        try:
            lat, lon = float(self.query["lat"]), float(self.query["lon"])
        except (KeyError, ValueError):
            raise ApiError(400, "Chybí poloha")
        try:
            radius = min(float(self.query.get("radius", 3000)), 20000)
        except ValueError:
            raise ApiError(400, "Neplatný poloměr")
        rows = self.conn.execute(
            """SELECT s.*, a.nick AS author,
                      EXISTS(SELECT 1 FROM catches c WHERE c.sticker_id = s.id AND c.finder_id = ? AND c.accepted = 1) AS caught
               FROM stickers s JOIN users a ON a.id = s.author_id
               WHERE s.status = 'active' AND s.lat IS NOT NULL""",
            (u["id"],),
        ).fetchall()
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
                "hint": r["hint"],
                "author": r["author"],
                "caught": bool(r["caught"]),
                "own": own,
            })
        out.sort(key=lambda x: x["distance"])
        return out


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    init_db()
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"Rybky backend běží na http://0.0.0.0:{port}  (databáze {DB_PATH})")
    server.serve_forever()


if __name__ == "__main__":
    main()
