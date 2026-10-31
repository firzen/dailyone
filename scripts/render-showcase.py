#!/usr/bin/env python3
"""Lay out unmodified Android screenshots into the README product overview (macOS)."""
import base64
from pathlib import Path
import struct
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
shots = root / 'docs/images/screenshots'
parts = ['''<svg xmlns="http://www.w3.org/2000/svg" width="1800" height="1800" viewBox="0 0 1800 1800">
<defs><linearGradient id="bg" x2="1" y2="1"><stop stop-color="#F5FAFC"/><stop offset="1" stop-color="#E9F2F6"/></linearGradient>
<filter id="shadow" x="-30%" y="-10%" width="160%" height="140%"><feDropShadow dx="0" dy="20" stdDeviation="18" flood-color="#143A4A" flood-opacity=".16"/></filter></defs>
<rect width="1800" height="1100" rx="40" fill="url(#bg)"/>
<circle cx="1480" cy="300" r="480" fill="#DDEFF6"/>
<g font-family="PingFang SC, sans-serif" fill="#22343E">
<text x="76" y="126" font-family="Helvetica Neue, sans-serif" font-size="47" font-weight="700">DailyOne</text>
<text x="78" y="171" font-size="20" fill="#168CCC" letter-spacing="3">ANDROID JOURNAL</text>
<text x="76" y="340" font-size="66" font-weight="650">把今天留下来。</text>
<text x="76" y="430" font-size="60" font-weight="650">让回忆有迹可循。</text>
<text x="80" y="519" font-size="27" fill="#60737D">文字、照片、视频和去过的地方。</text>
<text x="80" y="564" font-size="27" fill="#60737D">一份记录，三种方式回看。</text>
<rect x="78" y="632" width="405" height="60" rx="30" fill="#168CCC"/>
<text x="107" y="671" font-family="Helvetica Neue, sans-serif" font-size="25" fill="white">A Day One alternative.</text>
<text x="80" y="780" font-size="24">离线记录 · 保留原图</text>
<text x="80" y="826" font-size="24">Day One 导入 · 自有 WebDAV 同步</text>
<text x="80" y="1032" font-size="18" fill="#71848D">真实 Android 界面 / 独立演示内容</text>
''']
for index, (name, title, subtitle) in enumerate([
    ('timeline', '列表', '按时间，重温日常'),
    ('calendar', '日历', '按日期，找回某一天'),
    ('map', '地图', '按地点，串起足迹'),
]):
    path = shots / f'{name}.png'
    data = path.read_bytes()
    width, height = struct.unpack('>II', data[16:24])
    x = 714 + index * 348
    y = 254 if index != 1 else 146
    screen_w = 286
    screen_h = round(screen_w * height / width)
    frame_h = screen_h + 16
    uri = 'data:image/png;base64,' + base64.b64encode(data).decode()
    parts.append(f'<defs><clipPath id="screen{index}"><rect x="{x+8}" y="{y+8}" width="{screen_w}" height="{screen_h}" rx="25"/></clipPath></defs>')
    parts.append(f'<rect x="{x}" y="{y}" width="302" height="{frame_h}" rx="33" fill="#26373F" filter="url(#shadow)"/>')
    parts.append(f'<image x="{x+8}" y="{y+8}" width="{screen_w}" height="{screen_h}" href="{uri}" clip-path="url(#screen{index})"/>')
    parts.append(f'<text x="{x+151}" y="{y+frame_h+53}" text-anchor="middle" font-size="26" font-weight="600">{title}</text>')
    parts.append(f'<text x="{x+151}" y="{y+frame_h+91}" text-anchor="middle" font-size="20" fill="#60737D">{subtitle}</text>')
parts.append('</g></svg>')
with tempfile.TemporaryDirectory(prefix='dailyone-showcase-') as folder:
    svg = Path(folder) / 'showcase.svg'
    svg.write_text(''.join(parts))
    subprocess.run(['qlmanage', '-t', '-s', '1800', '-o', folder, str(svg)], check=True, stdout=subprocess.DEVNULL)
    crop = Path(folder) / 'crop.swift'
    crop.write_text("""import Foundation
import CoreGraphics
import ImageIO
let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL, nil)!
let image = CGImageSourceCreateImageAtIndex(src, 0, nil)!
let cropped = image.cropping(to: CGRect(x: 0, y: 0, width: 1800, height: 1100))!
let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[2]) as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(dest, cropped, nil)
CGImageDestinationFinalize(dest)
""")
    subprocess.run(['swift', str(crop), str(Path(folder) / 'showcase.svg.png'), str(root / 'docs/images/showcase.png')], check=True)

print(root / 'docs/images/showcase.png')
