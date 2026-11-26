"""Synthetic test images for the TFLite check: the 10 generators copied verbatim from
../validate.py (font() .. preprocess()), plus 10 variants of them so the
check covers ~20 varied inputs. No real photos. Imported by validate_tflite.py.
"""
import io, random
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

W, H = 1024, 768


def font(sz):
    try:
        return ImageFont.load_default(size=sz)
    except TypeError:
        return ImageFont.load_default()


def img_target():
    im = Image.new("RGB", (W, H), (139, 106, 70)); d = ImageDraw.Draw(im)
    d.rectangle([292, 40, 732, 728], fill=(240, 234, 218))
    cx, cy = 512, 360
    for r, c in [(200, (20, 20, 20)), (165, (240, 234, 218)), (130, (20, 20, 20)), (95, (240, 234, 218)), (60, (20, 20, 20)), (28, (200, 30, 30))]:
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=c)
    rnd = random.Random(1)
    for _ in range(14):
        x, y = cx + rnd.randint(-120, 120), cy + rnd.randint(-120, 120)
        d.ellipse([x - 6, y - 6, x + 6, y + 6], fill=(250, 250, 250) if rnd.random() < .5 else (60, 40, 30))
    d.text((330, 640), "NRA B-8  25 YD", fill=(30, 30, 30), font=font(36))
    return im


def img_receipt():
    im = Image.new("RGB", (W, H), (96, 78, 62)); d = ImageDraw.Draw(im)
    r = Image.new("RGB", (330, 700), (246, 244, 238)); rd = ImageDraw.Draw(r)
    lines = ["SUPERMART #0412", "123 MAIN ST", "", "MILK 2%        3.49", "BREAD          2.99", "EGGS 12CT      4.19",
             "COFFEE        11.99", "BANANAS        1.27", "", "SUBTOTAL      23.93", "TAX            1.97", "TOTAL         25.90",
             "", "VISA ****4421", "THANK YOU"]
    for i, ln in enumerate(lines):
        rd.text((24, 24 + i * 40), ln, fill=(40, 40, 40), font=font(24))
    r = r.rotate(-6, expand=True, fillcolor=(96, 78, 62))
    im.paste(r, (330, 20))
    return im


def img_landscape(hue=0):
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    top = np.array([60, 90, 170]) if hue == 0 else np.array([120, 60, 140])
    bot = np.array([250, 170, 110]) if hue == 0 else np.array([255, 120, 80])
    for y in range(H):
        t = y / H; d.line([(0, y), (W, y)], fill=tuple(int(v) for v in top * (1 - t) + bot * t))
    d.ellipse([650, 250, 790, 390], fill=(255, 235, 170))
    d.polygon([(0, 520), (200, 300), (380, 470), (560, 260), (820, 480), (1024, 380), (1024, 768), (0, 768)], fill=(60, 70, 100))
    d.polygon([(560, 260), (610, 320), (575, 312), (540, 335), (520, 300)], fill=(245, 245, 250))
    d.rectangle([0, 600, W, H], fill=(40, 90, 130))
    for y in range(605, H, 14):
        d.line([(0, y), (W, y)], fill=(70, 120, 160), width=2)
    return im.filter(ImageFilter.GaussianBlur(1.2))


def img_panel():
    im = Image.new("RGB", (W, H), (150, 152, 150)); d = ImageDraw.Draw(im)
    d.rectangle([262, 30, 762, 740], fill=(205, 207, 210), outline=(90, 90, 90), width=6)
    for i in range(12):
        y = 70 + i * 54
        for x in (300, 540):
            d.rectangle([x, y, x + 180, y + 38], fill=(35, 35, 38))
            d.rectangle([x + 70, y + 10, x + 110, y + 28], fill=(80, 80, 85))
            d.text((x - 30 if x == 300 else x + 190, y + 8), str(i * 2 + (1 if x == 300 else 2)), fill=(20, 20, 20), font=font(20))
    for c, off in [((200, 40, 30), 0), ((20, 20, 20), 30), ((240, 240, 240), 60), ((40, 120, 40), 90)]:
        d.line([(100 + off, 768), (180 + off, 500), (300, 300 + off)], fill=c, width=10)
    return im


def img_screenshot():
    im = Image.new("RGB", (720, 1480), (250, 250, 252)); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 720, 60], fill=(240, 240, 244)); d.text((30, 14), "9:41", fill=(0, 0, 0), font=font(30))
    d.text((560, 14), "5G  87%", fill=(0, 0, 0), font=font(30))
    d.rectangle([0, 60, 720, 170], fill=(255, 255, 255)); d.text((30, 90), "Messages", fill=(0, 0, 0), font=font(48))
    for i in range(10):
        y = 200 + i * 125
        d.ellipse([30, y, 120, y + 90], fill=(120 + i * 10, 140, 200))
        d.text((150, y + 8), f"Contact {i + 1}", fill=(0, 0, 0), font=font(32))
        d.text((150, y + 50), "See you tomorrow at 7, bring the...", fill=(120, 120, 120), font=font(26))
        d.line([(150, y + 110), (720, y + 110)], fill=(225, 225, 230), width=2)
    return im


def img_document():
    im = Image.new("RGB", (W, H), (70, 64, 60)); d = ImageDraw.Draw(im)
    d.rectangle([212, 10, 812, 768], fill=(252, 252, 250))
    d.text((260, 50), "LEASE AGREEMENT", fill=(10, 10, 10), font=font(40))
    rnd = random.Random(3)
    words = "the tenant shall pay rent monthly on or before the first day of each month landlord premises term".split()
    for i in range(22):
        ln = " ".join(rnd.choice(words) for _ in range(9))
        d.text((260, 120 + i * 28), ln, fill=(40, 40, 40), font=font(20))
    return im


def img_parking():
    im = Image.new("RGB", (W, H), (110, 112, 116)); d = ImageDraw.Draw(im)
    d.rectangle([0, 580, W, H], fill=(70, 72, 76))
    d.rectangle([380, 20, 660, 640], fill=(180, 182, 185))
    d.rectangle([400, 120, 640, 360], fill=(242, 194, 0))
    d.text((440, 160), "B2", fill=(20, 20, 20), font=font(150))
    d.text((450, 420), "14", fill=(40, 40, 40), font=font(110))
    for x in (0, 760):
        d.line([(x, 760), (x + 260, 620)], fill=(235, 235, 235), width=10)
    return im


def img_car():
    im = Image.new("RGB", (W, H), (40, 60, 90)); d = ImageDraw.Draw(im)
    d.rectangle([0, 470, W, H], fill=(45, 45, 48))
    d.polygon([(140, 520), (190, 420), (330, 400), (440, 310), (640, 300), (760, 400), (900, 420), (920, 520)], fill=(210, 40, 35))
    d.polygon([(460, 325), (630, 318), (720, 400), (430, 405)], fill=(160, 200, 230))
    for x in (300, 760):
        d.ellipse([x - 70, 450, x + 70, 590], fill=(15, 15, 15)); d.ellipse([x - 30, 490, x + 30, 550], fill=(170, 170, 170))
    return im


def img_group():
    im = Image.new("RGB", (W, H), (120, 180, 230)); d = ImageDraw.Draw(im)
    d.rectangle([0, 520, W, H], fill=(90, 160, 80))
    skins = [(236, 188, 150), (198, 134, 90), (241, 200, 170), (160, 110, 75), (230, 180, 140)]
    shirts = [(200, 50, 60), (40, 90, 180), (240, 200, 40), (60, 150, 90), (150, 60, 160)]
    for i in range(5):
        x = 170 + i * 170; y = 330 - (i % 2) * 40
        d.rounded_rectangle([x - 70, y + 70, x + 70, y + 420], 50, fill=shirts[i])
        d.ellipse([x - 55, y - 50, x + 55, y + 70], fill=skins[i])
        d.ellipse([x - 25, y - 5, x - 13, y + 7], fill=(30, 30, 30)); d.ellipse([x + 13, y - 5, x + 25, y + 7], fill=(30, 30, 30))
        d.arc([x - 25, y + 10, x + 25, y + 45], 20, 160, fill=(120, 30, 30), width=5)
    return im


def img_dark():
    rnd = np.random.default_rng(5)
    a = np.clip(rnd.normal(12, 6, (H, W, 3)), 0, 255).astype(np.uint8)
    return Image.fromarray(a).filter(ImageFilter.GaussianBlur(3))


SYNTH = {
    "target": (img_target, "shooting_target"),
    "receipt": (img_receipt, "receipt"),
    "landscape": (img_landscape, "mountains|beach_sunset"),
    "breaker_panel": (img_panel, "breaker_panel|wiring"),
    "screenshot": (img_screenshot, "screenshot"),
    "document": (img_document, "document"),
    "parking_pillar": (img_parking, "parking_spot"),
    "red_car": (img_car, "sports_car|classic_car"),
    "group_cartoon": (img_group, "family_group|friends_group"),
    "dark_frame": (img_dark, "dark_pocket|blurry"),
}


def preprocess(im):
    im = im.convert("RGB")
    w, h = im.size; s = 256 / min(w, h)
    im = im.resize((max(256, round(w * s)), max(256, round(h * s))), Image.BILINEAR)
    w, h = im.size; l, t = (w - 256) // 2, (h - 256) // 2
    im = im.crop((l, t, l + 256, t + 256))
    return (np.asarray(im, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None]





def jpeg(im, q):
    b = io.BytesIO(); im.save(b, "JPEG", quality=q); b.seek(0); return Image.open(b).convert("RGB")


def images20():
    """10 base scenes + 10 variants (crop/rotate/brightness/blur/jpeg/flip/portrait crop)."""
    base = {k: fn() for k, (fn, _) in SYNTH.items()}
    out = dict(base)
    out["target_rot5"] = base["target"].rotate(5, resample=Image.BILINEAR, fillcolor=(139, 106, 70))
    out["receipt_dark"] = ImageEnhance.Brightness(base["receipt"]).enhance(0.6)
    out["landscape_other_palette"] = img_landscape(1)
    out["panel_crop"] = base["breaker_panel"].crop((200, 0, 900, 768))
    out["screenshot_jpeg30"] = jpeg(base["screenshot"], 30)
    out["document_blur"] = base["document"].filter(ImageFilter.GaussianBlur(2))
    out["parking_flip"] = base["parking_pillar"].transpose(Image.FLIP_LEFT_RIGHT)
    out["car_zoom"] = base["red_car"].crop((100, 200, 950, 650)).resize((W, H))
    out["group_portrait_crop"] = base["group_cartoon"].crop((200, 0, 720, 768))
    out["dark_brighter"] = ImageEnhance.Brightness(base["dark_frame"]).enhance(2.5)
    return out
