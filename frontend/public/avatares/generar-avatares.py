"""
Genera avatares Forseti (1024x1024) con aire (4 niveles) en 3 versiones:
  - bicolor-fondo-blanco       (logo bicolor sobre fondo blanco — Gmail, redes formales)
  - blanco-fondo-mandarina     (logo blanco sobre mandarina deep — WhatsApp, alta visibilidad)
  - mandarina-fondo-blanco     (logo todo mandarina sobre fondo blanco — Instagram, plano)

Niveles de aire (el numero = % del lienzo que ocupa el logo):
  74 (menos aire) / 66 / 58 (medio) / 50 (mas aire)
"""
from pathlib import Path
from PIL import Image, ImageDraw

AQUI = Path(__file__).parent

INK = (15, 23, 42)
MANDARINA = (251, 146, 60)
MANDARINA_DEEP = (154, 52, 18)
MANDARINA_SOFT = (254, 215, 170)
WHITE = (255, 255, 255)


def draw_rounded_triangle(draw, pts, color, scale, stroke_width=6):
    sp = [(int(x * scale), int(y * scale)) for x, y in pts]
    sw = max(1, int(stroke_width * scale))
    draw.polygon(sp, fill=color + (255,))
    n = len(sp)
    for i in range(n):
        draw.line([sp[i], sp[(i + 1) % n]], fill=color + (255,), width=sw)
    for p in sp:
        r = sw // 2
        draw.ellipse((p[0] - r, p[1] - r, p[0] + r, p[1] + r), fill=color + (255,))


def render_logo(size, color_top, color_bottom, opacity_bottom=243):
    scale = size / 100.0
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    layer_top = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_rounded_triangle(ImageDraw.Draw(layer_top), [(50, 18), (78, 62), (22, 62)], color_top, scale)
    img = Image.alpha_composite(img, layer_top)
    layer_bot = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw_rounded_triangle(ImageDraw.Draw(layer_bot), [(50, 82), (22, 38), (78, 38)], color_bottom, scale)
    if opacity_bottom < 255:
        alpha = layer_bot.split()[3].point(lambda a: int(a * opacity_bottom / 255))
        layer_bot.putalpha(alpha)
    img = Image.alpha_composite(img, layer_bot)
    return img


CANVAS = 1024
AIRES = [74, 66, 58, 50]  # % del lienzo que ocupa el logo


def make_avatar(name: str, color_top, color_bottom, bg, aire: int):
    """Lienzo `bg` + logo centrado al `aire`% del lienzo."""
    logo_size = int(CANVAS * aire / 100)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), bg + (255,))
    logo = render_logo(logo_size * 2, color_top, color_bottom).resize((logo_size, logo_size), Image.LANCZOS)
    offset = (CANVAS - logo_size) // 2
    canvas.paste(logo, (offset, offset), logo)
    out = AQUI / f"forseti-avatar-{name}-{aire}.png"
    canvas.save(out)
    print("OK", out.name)


for aire in AIRES:
    # 3 versiones x 4 niveles = 12 avatares
    make_avatar("bicolor-fondo-blanco", INK, MANDARINA, WHITE, aire)
    make_avatar("blanco-fondo-mandarina", WHITE, MANDARINA_SOFT, MANDARINA_DEEP, aire)
    make_avatar("mandarina-fondo-blanco", MANDARINA, MANDARINA, WHITE, aire)

print("\nListo. 12 avatares en:", AQUI)
