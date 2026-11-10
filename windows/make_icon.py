# -*- coding: utf-8 -*-
"""生成 S3FileSync 的 Windows 图标 app/icon.ico（云 + 上下双向箭头，S3 蓝）。"""
import os

from PIL import Image, ImageDraw

BLUE = (37, 99, 235, 255)
BLUE_DARK = (29, 78, 216, 255)
WHITE = (255, 255, 255, 255)


def draw_icon(size):
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 64.0

    # 云朵主体：三圆 + 底座矩形
    d.ellipse([(18 * s, 34 * s), (40 * s, 56 * s)], fill=BLUE)
    d.ellipse([(30 * s, 24 * s), (56 * s, 50 * s)], fill=BLUE)
    d.ellipse([(44 * s, 34 * s), (62 * s, 52 * s)], fill=BLUE)
    d.rectangle([(20 * s, 40 * s), (58 * s, 58 * s)], fill=BLUE)
    # 底部收平（透明）
    d.rectangle([(14 * s, 50 * s), (64 * s, 64 * s)], fill=(0, 0, 0, 0))

    w = max(2, int(4 * s))   # 线宽（像素）
    hr = 5                   # 箭头半宽（0-64 坐标系）

    # 左：上行箭头；右：下行箭头（双向同步）
    x1, ya, yb = 27, 46, 30
    d.line([(x1 * s, ya * s), (x1 * s, yb * s)], fill=WHITE, width=w, joint='curve')
    d.polygon([(x1 * s, (yb - hr) * s),
               ((x1 - hr) * s, (yb + hr * 0.4) * s),
               ((x1 + hr) * s, (yb + hr * 0.4) * s)], fill=WHITE)

    x2, yc, yd = 45, 30, 46
    d.line([(x2 * s, yc * s), (x2 * s, yd * s)], fill=WHITE, width=w, joint='curve')
    d.polygon([(x2 * s, (yd + hr) * s),
               ((x2 - hr) * s, (yd - hr * 0.4) * s),
               ((x2 + hr) * s, (yd - hr * 0.4) * s)], fill=WHITE)

    return img


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, 'app', 'icon.ico')
    sizes = [(16, 16), (20, 20), (24, 24), (32, 32), (40, 40),
             (48, 48), (64, 64), (128, 128), (256, 256)]
    source = draw_icon(256)
    source.save(out, format='ICO', sizes=sizes)
    print('wrote', out, os.path.getsize(out), 'bytes')


if __name__ == '__main__':
    main()
