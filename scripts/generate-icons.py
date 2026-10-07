#!/usr/bin/env python3
"""Derive Android branding assets from docs/assets/inferdroid-logo.svg.

Requires Inkscape. Generated PNGs are checked in, so normal Android Studio
builds do not require this script or Inkscape. Only SVG geometry/filter
adaptations are performed; the supplied artwork remains the master.
"""

import copy
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/assets/inferdroid-logo.svg"
RES = ROOT / "app/src/main/res"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG)
ET.register_namespace("xlink", "http://www.w3.org/1999/xlink")


def tag(name):
    return f"{{{SVG}}}{name}"


def element(name, **attributes):
    return ET.Element(tag(name), attributes)


def compatible_master():
    root = ET.parse(SOURCE).getroot()
    # Inkscape 1.2 does not support feDropShadow. Expand it into equivalent
    # standard primitives, otherwise it omits the entire processor frame.
    for parent in root.iter():
        for child in list(parent):
            if child.tag != tag("feDropShadow"):
                continue
            position = list(parent).index(child)
            parent.remove(child)
            merge = element("feMerge")
            merge.extend([
                element("feMergeNode", **{"in": "shadow"}),
                element("feMergeNode", **{"in": "SourceGraphic"}),
            ])
            primitives = [
                element("feGaussianBlur", **{
                    "in": "SourceAlpha",
                    "stdDeviation": child.get("stdDeviation", "0"),
                    "result": "blurred",
                }),
                element("feOffset", **{
                    "in": "blurred", "dx": child.get("dx", "0"),
                    "dy": child.get("dy", "0"), "result": "offset",
                }),
                element("feFlood", **{
                    "flood-color": child.get("flood-color", "black"),
                    "flood-opacity": child.get("flood-opacity", "1"),
                    "result": "shadow-color",
                }),
                element("feComposite", **{
                    "in": "shadow-color", "in2": "offset",
                    "operator": "in", "result": "shadow",
                }),
                merge,
            ]
            for offset, primitive in enumerate(primitives):
                parent.insert(position + offset, primitive)
    return root


def artwork(root):
    shapes = {tag(name) for name in (
        "g", "path", "rect", "circle", "ellipse", "line", "polygon", "polyline", "use"
    )}
    return [child for child in root if child.tag in shapes]


def tile(root):
    return next(child for child in root if child.get("id") == "app-tile")


def adaptive_foreground(master):
    root = copy.deepcopy(master)
    root.remove(tile(root))
    # 108 dp adaptive layers have a protected central 66 dp circle. Center the
    # processor (the supplied artwork is slightly below the page center) and
    # leave enough space for circular masks and launcher motion.
    group = element("g", transform="translate(512 512) scale(0.70) translate(-512 -536)")
    for child in artwork(root):
        root.remove(child)
        group.append(child)
    root.append(group)
    return root


def adaptive_background(master):
    root = copy.deepcopy(master)
    background = tile(root)
    for child in artwork(root):
        if child is not background:
            root.remove(child)
    # The launcher supplies the outer mask; fill the entire layer, retaining
    # the supplied dark teal gradient without the original rounded tile edge.
    background.attrib.update(x="0", y="0", width="1024", height="1024", rx="0")
    return root


def monochrome(foreground):
    root = copy.deepcopy(foreground)
    # Luminance masking keeps dark node/chip interiors transparent while
    # preserving the original outlines and the central S for themed icons.
    for node in root.iter():
        node.attrib.pop("filter", None)
        style = dict(entry.split(":", 1) for entry in node.get("style", "").split(";")
                     if ":" in entry)
        style.pop("filter", None)
        for name in ("fill", "stroke"):
            for properties in (node.attrib, style):
                if name in properties:
                    value = properties[name].strip()
                    if value != "none":
                        properties[name] = (
                            "white" if name == "stroke" or value.startswith("url(") else "black"
                        )
        if "style" in node.attrib:
            node.set("style", ";".join(f"{key}:{value}" for key, value in style.items()))
    mask = element("mask", id="launcher-mask", maskUnits="userSpaceOnUse",
                   x="0", y="0", width="1024", height="1024")
    mask.append(element("rect", width="1024", height="1024", fill="black"))
    for child in artwork(root):
        root.remove(child)
        mask.append(child)
    root.find(tag("defs")).append(mask)
    root.append(element("rect", width="1024", height="1024",
                        fill="white", mask="url(#launcher-mask)"))
    return root


def main():
    inkscape = shutil.which("inkscape")
    if not inkscape:
        raise SystemExit("Install Inkscape, then rerun: python3 scripts/generate-icons.py")
    master = compatible_master()
    foreground = adaptive_foreground(master)
    assets = [
        (master, RES / "drawable-nodpi/inferdroid_logo.png", 512),
        (foreground, RES / "drawable-nodpi/ic_launcher_foreground.png", 432),
        (adaptive_background(master), RES / "drawable-nodpi/ic_launcher_background.png", 432),
        (monochrome(foreground), RES / "drawable-nodpi/ic_launcher_monochrome.png", 432),
    ]
    with tempfile.TemporaryDirectory(prefix="inferdroid-icons-") as temporary:
        work = Path(temporary)
        env = dict(os.environ, INKSCAPE_PROFILE_DIR=str(work / "profile"),
                   XDG_CACHE_HOME=str(work / "cache"))
        for index, (root, destination, size) in enumerate(assets):
            source = work / f"asset-{index}.svg"
            ET.ElementTree(root).write(source, encoding="utf-8", xml_declaration=True)
            destination.parent.mkdir(parents=True, exist_ok=True)
            result = subprocess.run([
                inkscape, str(source), "--export-area-page",
                f"--export-width={size}", f"--export-height={size}",
                f"--export-filename={destination}",
            ], env=env, capture_output=True, text=True, check=True)
            if "unknown type" in result.stderr:
                raise SystemExit(f"Unsupported SVG feature:\n{result.stderr}")
            print(destination.relative_to(ROOT))


if __name__ == "__main__":
    main()
