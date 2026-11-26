"""Nightstand Step A zero-shot prompt set (v1).

Each entry: (id, kind, phrase). kind is "keep" or "junk". The text that is embedded is
TEMPLATE.format(phrase). Edit here, re-run make_prompt_embeddings.py, ship the JSON.
"""

TEMPLATE = "a photo of {}."

PROMPTS = [
    # ---- keep: memories ----
    ("family_group",   "keep", "a family group posing together"),
    ("friends_group",  "keep", "a group of friends smiling"),
    ("portrait",       "keep", "a smiling person"),
    ("child",          "keep", "a child playing"),
    ("dog",            "keep", "a pet dog"),
    ("cat",            "keep", "a pet cat"),
    ("landmark",       "keep", "a famous landmark"),
    ("skyline",        "keep", "a city skyline"),
    ("mountains",      "keep", "a scenic mountain landscape"),
    ("beach_sunset",   "keep", "a beach at sunset"),
    ("sports_car",     "keep", "a cool sports car"),
    ("classic_car",    "keep", "a classic car at a car show"),
    ("motorcycle",     "keep", "a motorcycle"),
    ("firearm_coll",   "keep", "a firearm collection"),
    ("rifle_display",  "keep", "a rifle on display"),
    ("celebration",    "keep", "a wedding or birthday party"),
    # ---- junk: utility shots ----
    ("shooting_target","junk", "a paper shooting target with bullet holes"),
    ("breaker_panel",  "junk", "an electrical breaker panel"),
    ("wiring",         "junk", "exposed electrical wiring"),
    ("parking_spot",   "junk", "a parking garage pillar with a level number sign"),
    ("receipt",        "junk", "a receipt"),
    ("screenshot",     "junk", "a screenshot of a phone screen"),
    ("document",       "junk", "a document with printed text"),
    ("label_serial",   "junk", "a barcode or serial number label"),
    ("whiteboard",     "junk", "a whiteboard with writing"),
    ("product_box",    "junk", "a product in its cardboard box"),
    ("price_tag",      "junk", "a price tag in a store"),
    ("monitor",        "junk", "a computer monitor screen"),
    ("dashboard",      "junk", "a car dashboard with warning lights"),
    ("damage",         "junk", "a close-up of a broken or damaged item"),
    ("blurry",         "junk", "a blurry accidental shot"),
    ("dark_pocket",    "junk", "an almost black dark photo"),
]

# Not shipped: used only in the sanity test to show how much a synthetic drawing is
# "a drawing" rather than the thing it depicts.
CONTROLS = [
    ("ctl_drawing", "control", "a simple vector illustration"),
]
