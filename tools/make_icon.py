import os
import zipfile
from PIL import Image, ImageDraw, ImageFont, ImageFilter

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE_JAR = os.path.join(PROJECT, "libs", "simulatedcoasters-0.1.5.jar")
DST = os.path.join(PROJECT, "src", "main", "resources", "logo.png")
FONT = r"C:\Windows\Fonts\ariblk.ttf"

TEXT = "fix"
ROT = -10.0
CX, CY = 742, 852
FONT_SIZE = 330
OUTPUT_SIZE = 512

RED = (229, 53, 47, 255)
WHITE = (255, 255, 255, 255)
DARK = (18, 32, 52, 255)
SHADOW = (8, 18, 34, 150)


def load_base():
    if not os.path.isfile(SOURCE_JAR):
        raise SystemExit(
            "missing %s\n"
            "Put the original simulatedcoasters-0.1.5.jar into libs/ first "
            "(it is gitignored and not shipped)." % SOURCE_JAR
        )
    with zipfile.ZipFile(SOURCE_JAR) as jar:
        data = jar.read("logo.png")
    tmp = os.path.join(PROJECT, "_logo_src.png")
    with open(tmp, "wb") as fh:
        fh.write(data)
    try:
        return Image.open(tmp).convert("RGBA")
    finally:
        pass


base = load_base()
W, H = base.size

font = ImageFont.truetype(FONT, FONT_SIZE)

layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(layer)

d.text((CX, CY), TEXT, font=font, fill=DARK, anchor="mm",
       stroke_width=38, stroke_fill=DARK)
d.text((CX, CY), TEXT, font=font, fill=WHITE, anchor="mm",
       stroke_width=24, stroke_fill=WHITE)
d.text((CX, CY), TEXT, font=font, fill=RED, anchor="mm",
       stroke_width=0)

rot = layer.rotate(ROT, resample=Image.BICUBIC, center=(CX, CY))

shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
shadow.putalpha(rot.getchannel("A"))
shadow = Image.composite(
    Image.new("RGBA", (W, H), SHADOW),
    Image.new("RGBA", (W, H), (0, 0, 0, 0)),
    shadow.getchannel("A"),
)
shadow = shadow.filter(ImageFilter.GaussianBlur(10))
shadow = shadow.transform(
    (W, H), Image.AFFINE, (1, 0, -6, 0, 1, -12), resample=Image.BICUBIC
)

out = Image.alpha_composite(base, shadow)
out = Image.alpha_composite(out, rot)
out = out.resize((OUTPUT_SIZE, OUTPUT_SIZE), Image.LANCZOS)
out.save(DST, "PNG", optimize=True)

stale = os.path.join(PROJECT, "_logo_src.png")
if os.path.isfile(stale):
    os.remove(stale)

print("written:", DST)
print("size:", out.size, "bytes:", os.path.getsize(DST))
