"""Nightstand Step A zero-shot prompt set v2 (shipped in the app as assets/smart/prompts_v2.bin).

User decision (2026-11-26): no grey areas. Everything that is not a "keep" memory is junk.
Firearms and weapon collections are KEEP ("work of art"). Food, memes and drawings are junk.
Each entry: (id, kind, phrase); the embedded text is TEMPLATE.format(phrase).
"""

TEMPLATE = "a photo of {}."

PROMPTS = [
    # ---- keep: people ----
    ("family_group",   "keep", "a family group posing together"),
    ("friends_group",  "keep", "a group of friends smiling"),
    ("portrait",       "keep", "a smiling person"),
    ("couple",         "keep", "a couple together"),
    ("child",          "keep", "a child playing"),
    ("baby",           "keep", "a baby"),
    ("celebration",    "keep", "a wedding or birthday party"),
    ("holiday",        "keep", "a family holiday celebration"),
    ("event_crowd",    "keep", "people at a concert or sports game"),
    # ---- keep: pets and animals ----
    ("dog",            "keep", "a pet dog"),
    ("cat",            "keep", "a pet cat"),
    ("pet_other",      "keep", "a pet animal"),
    ("wildlife",       "keep", "a wild animal in nature"),
    # ---- keep: places ----
    ("landmark",       "keep", "a famous landmark"),
    ("skyline",        "keep", "a city skyline"),
    ("travel_street",  "keep", "a street while traveling abroad"),
    ("mountains",      "keep", "a scenic mountain landscape"),
    ("beach_sunset",   "keep", "a beach at sunset"),
    ("nature",         "keep", "a forest or lake in nature"),
    ("flowers",        "keep", "flowers in a garden"),
    # ---- keep: things the user loves ----
    ("sports_car",     "keep", "a cool sports car"),
    ("classic_car",    "keep", "a classic car at a car show"),
    ("motorcycle",     "keep", "a motorcycle"),
    ("firearm_coll",   "keep", "a firearm collection"),
    ("rifle_display",  "keep", "a rifle on display"),
    ("handgun",        "keep", "a handgun"),
    ("blades_coll",    "keep", "a collection of knives and swords"),
    # ---- junk: utility shots ----
    ("shooting_target","junk", "a paper shooting target with bullet holes"),
    ("breaker_panel",  "junk", "an electrical breaker panel"),
    ("wiring",         "junk", "exposed electrical wiring"),
    ("parking_spot",   "junk", "a parking garage pillar with a level number sign"),
    ("parking_bay",    "junk", "a parking space number on the ground"),
    ("receipt",        "junk", "a receipt"),
    ("screenshot",     "junk", "a screenshot of a phone screen"),
    ("document",       "junk", "a document with printed text"),
    ("note",           "junk", "a handwritten note"),
    ("ticket",         "junk", "a ticket or boarding pass"),
    ("label_serial",   "junk", "a barcode or serial number label"),
    ("whiteboard",     "junk", "a whiteboard with writing"),
    ("product_box",    "junk", "a product in its cardboard box"),
    ("return_item",    "junk", "an item photographed on the floor to sell or return"),
    ("price_tag",      "junk", "a price tag in a store"),
    ("monitor",        "junk", "a computer monitor screen"),
    ("dashboard",      "junk", "a car dashboard with warning lights"),
    ("damage",         "junk", "a close-up of a broken or damaged item"),
    ("tools",          "junk", "tools on a workbench"),
    ("food",           "junk", "a plate of food"),
    ("menu",           "junk", "a restaurant menu"),
    ("meme",           "junk", "a meme with text"),
    ("drawing",        "junk", "a drawing or sketch on paper"),
    ("blurry",         "junk", "a blurry accidental shot"),
    ("dark_pocket",    "junk", "an almost black dark photo"),
]
