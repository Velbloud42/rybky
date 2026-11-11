"""Porovnání fotek podle kresby: klíčové body SIFT a geometrická kontrola (RANSAC).

Pro tetování: každé je jiná kresba, ale všechna jsou na kůži, takže je nerozliší okolí ani otisky
celé fotky – jen tvar čar. Porovnává se jen střed fotky (tam je kresba podle zaměřovacího čtverce),
protože vzorované pozadí (látka, podložka) dělá náhodné shody.

Potřebuje balík opencv-python-headless; bez něj available() vrátí False a server pozná tetování postaru.
"""
import threading

try:
    import cv2
    import numpy as np
except ImportError:  # porovnání kresby je volitelné
    cv2 = np = None

CENTER = 0.7     # jaká část fotky (čtverec ze středu) se porovnává
SIDE = 480       # na kolik pixelů se střed zmenší
RATIO = 0.75     # Loweho test: nejlepší shoda musí být jasně lepší než druhá
RANSAC_PX = 5    # tolerance geometrické kontroly v pixelech

_cache = {}  # (cesta, čas změny) → body a jejich popisy
_lock = threading.Lock()


def available():
    return cv2 is not None


def features_of_jpeg(data):
    """Klíčové body a jejich popisy (SIFT) ze středu JPEGu, nebo None, když to není obrázek."""
    gray = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_GRAYSCALE)
    if gray is None:
        return None
    h, w = gray.shape
    s = int(min(h, w) * CENTER)
    gray = gray[(h - s) // 2:(h - s) // 2 + s, (w - s) // 2:(w - s) // 2 + s]
    gray = cv2.resize(gray, (SIDE, SIDE), interpolation=cv2.INTER_AREA)
    gray = cv2.createCLAHE(2.0, (8, 8)).apply(gray)  # vyrovná světlo, ať vyniknou čáry
    points, descriptors = cv2.SIFT_create(nfeatures=1500).detectAndCompute(gray, None)
    return np.float32([p.pt for p in points]), descriptors


def features_of_file(path):
    """Jako features_of_jpeg, ale pamatuje si výsledek (fotky rybek se počítají jen jednou)."""
    key = (str(path), path.stat().st_mtime)
    with _lock:
        hit = _cache.get(key)
    if hit is None:
        hit = features_of_jpeg(path.read_bytes())
        with _lock:
            _cache[key] = hit
    return hit


def colors_of_jpeg(data):
    """Barvy středu fotky (odstín × sytost × jas) – rozliší stejnou kresbu v jiné barvě nebo na jiném podkladu."""
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    h, w = image.shape[:2]
    s = int(min(h, w) * CENTER)
    image = cv2.resize(image[(h - s) // 2:(h - s) // 2 + s, (w - s) // 2:(w - s) // 2 + s], (160, 160))
    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], None, [12, 6, 4], [0, 180, 0, 256, 0, 256])
    return cv2.normalize(hist, hist).flatten()


def colors_of_file(path):
    key = ("colors", str(path), path.stat().st_mtime)
    with _lock:
        hit = _cache.get(key)
    if hit is None:
        hit = colors_of_jpeg(path.read_bytes())
        with _lock:
            _cache[key] = hit
    return hit


def color_similarity(a, b):
    """Podobnost barev dvou fotek, 0 až 1."""
    if a is None or b is None:
        return 0.0
    return max(0.0, float(cv2.compareHist(a, b, cv2.HISTCMP_CORREL)))


def _crop(data):
    """Barevný střed fotky ve stejném výřezu a velikosti, v jakém se počítají body kresby."""
    image = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if image is None:
        return None
    h, w = image.shape[:2]
    s = int(min(h, w) * CENTER)
    return cv2.resize(image[(h - s) // 2:(h - s) // 2 + s, (w - s) // 2:(w - s) // 2 + s], (SIDE, SIDE),
                      interpolation=cv2.INTER_AREA)


def drawing_colors(data, reference_path):
    """Dvě barvy samolepky na fotce (kresba a podklad, v libovolném pořadí) jako BGR, nebo None.
    Kde je kresba, ukážou body shodné se vzorovou fotkou stylu; v jejich obalu se pixely rozdělí na dvě barvy."""
    query, image = features_of_jpeg(data), _crop(data)
    points = _inlier_points(query, features_of_file(reference_path))
    if image is None or points is None or len(points) < 6:
        return None
    mask = np.zeros(image.shape[:2], np.uint8)
    cv2.fillConvexPoly(mask, cv2.convexHull(points.astype(np.int32)), 255)
    pixels = image[mask > 0].reshape(-1, 3).astype(np.float32)
    if len(pixels) < 200:
        return None
    lab = cv2.cvtColor(pixels.reshape(-1, 1, 3).astype(np.uint8), cv2.COLOR_BGR2LAB).reshape(-1, 3).astype(np.float32)
    _, labels, _ = cv2.kmeans(lab, 2, None, (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0), 3,
                              cv2.KMEANS_PP_CENTERS)
    labels = labels.ravel()
    if min(np.bincount(labels, minlength=2)) < 20:
        return None
    return tuple(np.median(pixels[labels == k], 0) for k in (0, 1))


def _lab(bgr):
    return cv2.cvtColor(np.uint8([[bgr]]), cv2.COLOR_BGR2LAB)[0, 0].astype(float) * [100 / 255, 1, 1] - [0, 128, 128]


def hex_to_bgr(value):
    value = value.lstrip("#")
    return [int(value[4:6], 16), int(value[2:4], 16), int(value[0:2], 16)]


def color_distance(first, second, ink_hex, base_hex):
    """Jak moc dvě naměřené barvy samolepky neodpovídají dvojici kresba/podklad z palety, 0 až 100.
    Porovnává se jen směr rozdílu barev (jas méně) – barevné světlo i vybledlá fotka ho skoro nemění."""
    weight = np.array([0.5, 1, 1])
    expected = (_lab(hex_to_bgr(ink_hex)) - _lab(hex_to_bgr(base_hex))) * weight
    measured = (_lab(first) - _lab(second)) * weight
    if np.linalg.norm(measured) < 3 or np.linalg.norm(expected) < 3:
        return 100.0
    return (1 - abs(float(measured @ expected) / (np.linalg.norm(measured) * np.linalg.norm(expected)))) * 100


def bgr_to_hex(bgr):
    return "#%02x%02x%02x" % tuple(int(v) for v in bgr[::-1])


def _inlier_points(a, b):
    """Body první fotky, které geometricky sedí s druhou (na kresbě), nebo None."""
    if a is None or b is None:
        return None
    (pa, da), (pb, db) = a, b
    if da is None or db is None or len(pa) < 8 or len(pb) < 8:
        return None
    pairs = cv2.BFMatcher(cv2.NORM_L2).knnMatch(da, db, k=2)
    good = [p[0] for p in pairs if len(p) == 2 and p[0].distance < RATIO * p[1].distance]
    if len(good) < 6:
        return None
    src = pa[[m.queryIdx for m in good]]
    transform, mask = cv2.estimateAffinePartial2D(src, pb[[m.trainIdx for m in good]], method=cv2.RANSAC,
                                                  ransacReprojThreshold=RANSAC_PX)
    return None if transform is None else src[mask.ravel().astype(bool)]


def inliers(a, b):
    """Kolik bodů obou fotek k sobě sedí i geometricky (posun, otočení, zvětšení). 0 = nic společného."""
    if a is None or b is None:
        return 0
    (pa, da), (pb, db) = a, b
    if da is None or db is None or len(pa) < 8 or len(pb) < 8:
        return 0
    pairs = cv2.BFMatcher(cv2.NORM_L2).knnMatch(da, db, k=2)
    good = [p[0] for p in pairs if len(p) == 2 and p[0].distance < RATIO * p[1].distance]
    if len(good) < 6:
        return 0
    src = pa[[m.queryIdx for m in good]]
    dst = pb[[m.trainIdx for m in good]]
    transform, mask = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=RANSAC_PX)
    if transform is None:
        return 0
    scale = float(np.hypot(transform[0, 0], transform[1, 0]))
    if not 0.33 < scale < 3:  # nesmyslné zvětšení = náhodná shoda
        return 0
    return int(mask.sum())
