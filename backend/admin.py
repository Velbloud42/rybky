"""Admin dashboard Rybek na /admin, přístupný jen s hardwarovým klíčem (WebAuthn / FIDO2).

Klíč je jen jeden: zaregistruje se jednorázovým kódem, který server vypíše do logu při startu
(dokud žádný klíč není), a pak se registrace natrvalo zavře. Synchronizované passkeys (telefon,
správce hesel) se odmítají – projde jen klíč vázaný na jedno zařízení.
Ztracený klíč: smazat řádek z tabulky admin_keys a restartovat server → v logu bude nový kód.

Potřebuje balík `webauthn`; bez něj zbytek serveru funguje, jen admin hlásí, že není dostupný.
"""
import base64
import json
import os
import re
import secrets
import threading
import time
from pathlib import Path

try:
    import webauthn
    from webauthn.helpers.structs import (
        AuthenticatorAttachment,
        AuthenticatorSelectionCriteria,
        CredentialDeviceType,
        PublicKeyCredentialDescriptor,
        PublicKeyCredentialHint,
        ResidentKeyRequirement,
        UserVerificationRequirement,
    )
except ImportError:  # admin je volitelný
    webauthn = None

RP_ID = os.environ.get("ADMIN_RP_ID", "localhost")
ORIGIN = os.environ.get("ADMIN_ORIGIN", "http://localhost:8000")
SESSION_TTL = 12 * 3600
FLOW_TTL = 300
COOKIE = "rybky_admin"
PAGE = Path(__file__).with_name("admin.html")
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

_lock = threading.Lock()
_sessions = {}  # id sezení → kdy vyprší
_flows = {}  # id rozpracované registrace/přihlášení → (druh, challenge, kdy vyprší)
_setup = {"code": None, "failures": 0}
_config = {}


def configure(db, images_dir, catch_points, author_points, new_fish_name):
    """Volá server při startu. Když ještě není žádný klíč, vygeneruje kód pro registraci prvního."""
    _config.update(db=db, images_dir=Path(images_dir), catch_points=catch_points, author_points=author_points,
                   new_fish_name=new_fish_name)
    if webauthn is None:
        print("Admin: balík webauthn chybí, /admin nepoběží.")
        return
    with db() as conn:
        if not conn.execute("SELECT 1 FROM admin_keys").fetchone():
            code = "-".join("".join(secrets.choice(CODE_ALPHABET) for _ in range(4)) for _ in range(3))
            _setup["code"] = code
            print(f"Admin: kód pro registraci prvního klíče na {ORIGIN}/admin: {code}", flush=True)


# Jedna souřadnice: stupně, volitelně minuty a vteřiny a světová strana (S a W jsou záporné).
COORD = re.compile(r"""(-?\d+(?:\.\d+)?)\s*°?\s*(?:(\d+(?:\.\d+)?)\s*['′]\s*)?(?:(\d+(?:\.\d+)?)\s*["″]\s*)?([NSEWnsew])?""")


def parse_coords(text):
    """„50.0755, 14.4378“, „37.8016N, 8.7974W“ i „37°48'05.8"N 8°47'50.6"W“ → (lat, lon), nebo None."""
    parts = []
    for deg, minutes, seconds, side in COORD.findall(text):
        value = abs(float(deg)) + float(minutes or 0) / 60 + float(seconds or 0) / 3600
        if deg.startswith("-") or side.upper() in ("S", "W"):
            value = -value
        parts.append((value, side.upper()))
    if len(parts) != 2:
        return None
    (lat, first), (lon, second) = parts
    if first in ("E", "W") and second in ("N", "S"):
        lat, lon = lon, lat
    return (lat, lon) if -90 <= lat <= 90 and -180 <= lon <= 180 else None


def b64u(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64u(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


class AdminError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


class _Raw:
    def __init__(self, content_type, body, headers=None):
        self.content_type = content_type
        self.body = body
        self.headers = headers or {}


ROUTES = [
    ("GET", r"/admin/?$", "page"),
    ("GET", r"/admin/api/state$", "state"),
    ("POST", r"/admin/api/register/begin$", "register_begin"),
    ("POST", r"/admin/api/register/finish$", "register_finish"),
    ("POST", r"/admin/api/login/begin$", "login_begin"),
    ("POST", r"/admin/api/login/finish$", "login_finish"),
    ("POST", r"/admin/api/logout$", "logout"),
    ("GET", r"/admin/api/overview$", "overview"),
    ("POST", r"/admin/api/action$", "action"),
    ("GET", r"/admin/image/(catch|sticker)/(\w+)$", "image"),
]


class AdminMixin:
    """Přimíchá se do HTTP handleru serveru (potřebuje jeho send_json / send_raw / body)."""

    def admin(self, method, path):
        try:
            for m, pattern, name in ROUTES:
                match = re.match(pattern, path)
                if m == method and match:
                    if method == "POST" and self.headers.get("Origin") != ORIGIN:
                        raise AdminError(403, "Neplatný původ požadavku")
                    with _config["db"]() as conn:
                        self.conn = conn
                        result = getattr(self, f"_admin_{name}")(*match.groups())
                    if isinstance(result, _Raw):
                        return self.send_raw(200, result)
                    payload, headers = result if isinstance(result, tuple) else (result, None)
                    return self.send_json(200, payload, headers)
            raise AdminError(404, "Neznámá adresa")
        except AdminError as e:
            self.send_json(e.status, {"error": e.message})
        except Exception as e:  # noqa: BLE001
            self.log_error("admin chyba: %r", e)
            self.send_json(500, {"error": "Chyba serveru"})

    # --- sezení a WebAuthn ---------------------------------------------------

    def _cookie(self):
        for part in (self.headers.get("Cookie") or "").split(";"):
            key, _, value = part.strip().partition("=")
            if key == COOKIE:
                return value
        return None

    def _logged_in(self):
        sid = self._cookie()
        with _lock:
            return bool(sid) and _sessions.get(sid, 0) > time.time()

    def _require_session(self):
        if not self._logged_in():
            raise AdminError(401, "Nejsi přihlášený")

    def _start_session(self, payload):
        sid = secrets.token_urlsafe(32)
        with _lock:
            _sessions[sid] = time.time() + SESSION_TTL
        cookie = f"{COOKIE}={sid}; Path=/admin; HttpOnly; Secure; SameSite=Strict; Max-Age={SESSION_TTL}"
        return payload, {"Set-Cookie": cookie}

    def _new_flow(self, kind, challenge):
        flow = secrets.token_urlsafe(16)
        with _lock:
            now = time.time()
            for key in [k for k, v in _flows.items() if v[2] < now]:
                del _flows[key]
            _flows[flow] = (kind, challenge, now + FLOW_TTL)
        return flow

    def _take_flow(self, flow, kind):
        with _lock:
            entry = _flows.pop(str(flow), None)
        if not entry or entry[0] != kind or entry[2] < time.time():
            raise AdminError(400, "Vypršel čas, zkus to znovu")
        return entry[1]

    def _require_webauthn(self):
        if webauthn is None:
            raise AdminError(503, "Na serveru chybí balík webauthn")

    def _keys(self):
        return self.conn.execute("SELECT * FROM admin_keys ORDER BY created").fetchall()

    # --- stránka a přihlášení ------------------------------------------------

    def _admin_page(self):
        return _Raw("text/html; charset=utf-8", PAGE.read_bytes(), {
            "Cache-Control": "no-store",
            "X-Frame-Options": "DENY",
            "Referrer-Policy": "no-referrer",
            "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; "
                                       "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'",
        })

    def _admin_state(self):
        return {"available": webauthn is not None, "loggedIn": self._logged_in(), "hasKeys": bool(self._keys())}

    def _admin_register_begin(self):
        self._require_webauthn()
        data = self.body()
        keys = self._keys()
        if keys:
            raise AdminError(403, "Klíč už je zaregistrovaný, další přidat nejde")
        else:
            code = str(data.get("code") or "").strip().upper()
            with _lock:
                if _setup["failures"] >= 10:
                    raise AdminError(429, "Moc pokusů. Restartuj server pro nový kód.")
                ok = bool(_setup["code"]) and secrets.compare_digest(code, _setup["code"])
                if not ok:
                    _setup["failures"] += 1
            if not ok:
                raise AdminError(403, "Špatný registrační kód")
        options = webauthn.generate_registration_options(
            rp_id=RP_ID, rp_name="Rybky admin",
            user_id=b"rybky-admin", user_name="admin", user_display_name="Rybky admin",
            authenticator_selection=AuthenticatorSelectionCriteria(
                authenticator_attachment=AuthenticatorAttachment.CROSS_PLATFORM,
                resident_key=ResidentKeyRequirement.DISCOURAGED,
                user_verification=UserVerificationRequirement.DISCOURAGED,
            ),
            exclude_credentials=[PublicKeyCredentialDescriptor(id=unb64u(k["id"])) for k in keys],
            hints=[PublicKeyCredentialHint.SECURITY_KEY],
        )
        return {"flow": self._new_flow("register", options.challenge),
                "options": json.loads(webauthn.options_to_json(options))}

    def _admin_register_finish(self):
        self._require_webauthn()
        data = self.body()
        challenge = self._take_flow(data.get("flow"), "register")
        try:
            verified = webauthn.verify_registration_response(
                credential=data.get("credential"), expected_challenge=challenge,
                expected_rp_id=RP_ID, expected_origin=ORIGIN,
            )
        except Exception as e:  # noqa: BLE001 – knihovna hází různé výjimky
            raise AdminError(400, f"Ověření klíče selhalo: {e}")
        if verified.credential_backed_up or verified.credential_device_type == CredentialDeviceType.MULTI_DEVICE:
            raise AdminError(400, "Tohle je synchronizovaný passkey. Použij fyzický klíč.")
        name = str(data.get("name") or "").strip()[:40] or "Klíč"
        self.conn.execute(
            "INSERT INTO admin_keys (id, public_key, sign_count, name, created) VALUES (?, ?, ?, ?, ?)",
            (b64u(verified.credential_id), b64u(verified.credential_public_key), verified.sign_count, name, time.time()),
        )
        with _lock:
            _setup["code"] = None
        return self._start_session({"ok": True})

    def _admin_login_begin(self):
        self._require_webauthn()
        keys = self._keys()
        if not keys:
            raise AdminError(409, "Zatím není zaregistrovaný žádný klíč")
        options = webauthn.generate_authentication_options(
            rp_id=RP_ID,
            allow_credentials=[PublicKeyCredentialDescriptor(id=unb64u(k["id"])) for k in keys],
            user_verification=UserVerificationRequirement.DISCOURAGED,
        )
        return {"flow": self._new_flow("login", options.challenge),
                "options": json.loads(webauthn.options_to_json(options))}

    def _admin_login_finish(self):
        self._require_webauthn()
        data = self.body()
        challenge = self._take_flow(data.get("flow"), "login")
        credential = data.get("credential") or {}
        key = self.conn.execute("SELECT * FROM admin_keys WHERE id = ?", (str(credential.get("id")),)).fetchone()
        if not key:
            raise AdminError(403, "Neznámý klíč")
        try:
            verified = webauthn.verify_authentication_response(
                credential=credential, expected_challenge=challenge,
                expected_rp_id=RP_ID, expected_origin=ORIGIN,
                credential_public_key=unb64u(key["public_key"]),
                credential_current_sign_count=key["sign_count"],
            )
        except Exception as e:  # noqa: BLE001
            raise AdminError(403, f"Ověření klíče selhalo: {e}")
        self.conn.execute("UPDATE admin_keys SET sign_count = ? WHERE id = ?", (verified.new_sign_count, key["id"]))
        return self._start_session({"ok": True})

    def _admin_logout(self):
        sid = self._cookie()
        with _lock:
            _sessions.pop(sid, None)
        return {"ok": True}, {"Set-Cookie": f"{COOKIE}=; Path=/admin; HttpOnly; Secure; SameSite=Strict; Max-Age=0"}

    # --- data ----------------------------------------------------------------

    def _admin_overview(self):
        self._require_session()
        q = lambda sql, *args: [dict(r) for r in self.conn.execute(sql, args).fetchall()]  # noqa: E731
        one = lambda sql, *args: self.conn.execute(sql, args).fetchone()[0]  # noqa: E731
        return {
            "stats": {
                "users": one("SELECT COUNT(*) FROM users"),
                "usersWithPassword": one("SELECT COUNT(*) FROM users WHERE password IS NOT NULL"),
                "stickers": one("SELECT COUNT(*) FROM stickers WHERE status = 'active' AND env NOT IN ('tetovani', 'predmet')"),
                "tattoos": one("SELECT COUNT(*) FROM stickers WHERE status = 'active' AND env = 'tetovani'"),
                "objects": one("SELECT COUNT(*) FROM stickers WHERE status = 'active' AND env = 'predmet'"),
                "catches": one("SELECT COUNT(*) FROM catches WHERE accepted = 1"),
                "attempts": one("SELECT COUNT(*) FROM catches"),
                "refsPositive": one("SELECT COUNT(*) FROM refs WHERE negative = 0"),
                "refsNegative": one("SELECT COUNT(*) FROM refs WHERE negative = 1"),
            },
            "users": q("""SELECT u.id, u.nick, u.points, u.password IS NOT NULL AS hasPassword, u.created,
                                 (SELECT COUNT(*) FROM stickers s WHERE s.author_id = u.id) AS stickers,
                                 (SELECT COUNT(*) FROM catches c WHERE c.finder_id = u.id AND c.accepted = 1) AS catches
                          FROM users u ORDER BY u.points DESC, u.created"""),
            "stickers": q("""SELECT s.id, s.name, s.env, s.fish, s.fish_name, s.rarity, s.status, s.hint, s.lat, s.lon, s.created,
                                    s.image IS NOT NULL AS hasImage, a.nick AS author,
                                    (SELECT COUNT(*) FROM catches c WHERE c.sticker_id = s.id AND c.accepted = 1) AS finds,
                                    (SELECT GROUP_CONCAT(u.nick, ', ') FROM catches c JOIN users u ON u.id = c.finder_id
                                     WHERE c.sticker_id = s.id AND c.accepted = 1) AS finders
                             FROM stickers s JOIN users a ON a.id = s.author_id ORDER BY s.created DESC"""),
            "catches": q("""SELECT c.id, c.time, c.accepted, c.similarity, c.distance, c.points,
                                   c.image IS NOT NULL AS hasImage, u.nick AS finder, s.name AS sticker, s.env, s.fish, s.fish_name,
                                   EXISTS(SELECT 1 FROM refs r WHERE r.origin = 'catch:' || c.id AND r.negative = 1) AS negative
                            FROM catches c JOIN users u ON u.id = c.finder_id JOIN stickers s ON s.id = c.sticker_id
                            ORDER BY c.time DESC LIMIT 200"""),
            "keys": q("SELECT id, name, created FROM admin_keys ORDER BY created"),
        }

    def _admin_image(self, kind, ident):
        self._require_session()
        table = "catches" if kind == "catch" else "stickers"
        row = self.conn.execute(f"SELECT image FROM {table} WHERE id = ?", (ident,)).fetchone()
        if not row or not row["image"]:
            raise AdminError(404, "Fotka není")
        return _Raw("image/jpeg", (_config["images_dir"] / row["image"]).read_bytes(), {"Cache-Control": "private, max-age=3600"})

    def _admin_action(self):
        self._require_session()
        data = self.body()
        action, ident = data.get("action"), data.get("id")
        conn = self.conn
        if action == "reset_password":
            # Hráč si heslo nastaví znovu prvním přihlášením.
            conn.execute("UPDATE users SET password = NULL WHERE id = ?", (ident,))
        elif action == "delete_user":
            used = conn.execute(
                "SELECT (SELECT COUNT(*) FROM stickers WHERE author_id = ?) + (SELECT COUNT(*) FROM catches WHERE finder_id = ?)",
                (ident, ident),
            ).fetchone()[0]
            if used:
                raise AdminError(409, "Hráč má samolepky nebo úlovky – smazat jde jen prázdný účet")
            conn.execute("DELETE FROM users WHERE id = ?", (ident,))
        elif action in ("sticker_lost", "sticker_restore"):
            conn.execute("UPDATE stickers SET status = ? WHERE id = ?",
                         ("lost" if action == "sticker_lost" else "active", ident))
        elif action == "set_position":
            sticker = conn.execute("SELECT env, lat FROM stickers WHERE id = ?", (ident,)).fetchone()
            if not sticker:
                raise AdminError(404, "Samolepka neexistuje")
            if sticker["lat"] is None:
                raise AdminError(409, "Tetování a předměty polohu nemají")
            position = parse_coords(str(data.get("coords") or ""))
            if not position:
                raise AdminError(400, "Nerozumím souřadnicím. Zkus třeba 50.0755, 14.4378")
            conn.execute("UPDATE stickers SET lat = ?, lon = ?, accuracy = 0 WHERE id = ?", (*position, ident))
        elif action == "rename_fish":
            # Prázdné jméno = vylosovat nové.
            if not conn.execute("SELECT 1 FROM stickers WHERE id = ?", (ident,)).fetchone():
                raise AdminError(404, "Samolepka neexistuje")
            name = " ".join(str(data.get("name") or "").split()) or _config["new_fish_name"](conn)
            if not 2 <= len(name) <= 40:
                raise AdminError(400, "Jméno rybky musí mít 2 až 40 znaků")
            taken = conn.execute("SELECT id, fish_name FROM stickers WHERE id != ? AND fish_name IS NOT NULL", (ident,))
            if any(r["fish_name"].casefold() == name.casefold() for r in taken):
                raise AdminError(409, f"Jméno „{name}“ už má jiná rybka")
            conn.execute("UPDATE stickers SET fish_name = ? WHERE id = ?", (name, ident))
        elif action == "sticker_not_rybka":
            # Vypnout samolepku a její fotky přesunout mezi negativní vzory.
            conn.execute("UPDATE stickers SET status = 'lost' WHERE id = ?", (ident,))
            conn.execute("UPDATE refs SET negative = 1 WHERE origin = ?", (f"sticker:{ident}",))
        elif action == "catch_not_rybka":
            self._revoke_catch(int(ident))
        else:
            raise AdminError(400, "Neznámá akce")
        return {"ok": True}

    def _revoke_catch(self, catch_id):
        """Úlovek nebyl rybka: vzít body, otisk dát mezi negativní a odebrat ho ze samolepky."""
        conn = self.conn
        c = conn.execute(
            """SELECT c.*, s.env, s.rarity, s.author_id, s.embeddings FROM catches c
               JOIN stickers s ON s.id = c.sticker_id WHERE c.id = ?""",
            (catch_id,),
        ).fetchone()
        if not c:
            raise AdminError(404, "Úlovek neexistuje")
        ref = conn.execute("SELECT embedding FROM refs WHERE origin = ?", (f"catch:{catch_id}",)).fetchone()
        if c["accepted"]:
            points = c["points"] if c["points"] is not None else _config["catch_points"](c["env"], c["rarity"])
            conn.execute("UPDATE users SET points = points - ? WHERE id = ?", (points, c["finder_id"]))
            conn.execute("UPDATE users SET points = points - ? WHERE id = ?", (_config["author_points"](c["env"]), c["author_id"]))
            conn.execute("UPDATE catches SET accepted = 0, points = 0 WHERE id = ?", (catch_id,))
        if ref:
            shots = [e for e in json.loads(c["embeddings"]) if e != json.loads(ref["embedding"])]
            if shots:
                conn.execute("UPDATE stickers SET embeddings = ? WHERE id = ?", (json.dumps(shots), c["sticker_id"]))
            conn.execute("UPDATE refs SET negative = 1 WHERE origin = ?", (f"catch:{catch_id}",))
