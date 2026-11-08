"""Fit original inventory models using Minecraft 1.21.1's actual GUI transform.

ItemTransform applies T * rotationXYZ * S * right_rotation; ItemRenderer then
translates by (-.5,-.5,-.5). GuiGraphics applies scale(16,-16,16) around the slot
centre. Coordinates here remain in model units, equivalent to GUI pixels.
"""
from __future__ import annotations
import copy
import itertools
import json
import math

HALF_EXTENT = 6.5  # A 16-pixel icon retains at least 1.5 pixels on every edge.


def rotate(point, degrees):
    x, y, z = point
    rx, ry, rz = (math.radians(v) for v in degrees)
    x, y = math.cos(rz)*x-math.sin(rz)*y, math.sin(rz)*x+math.cos(rz)*y
    x, z = math.cos(ry)*x+math.sin(ry)*z, -math.sin(ry)*x+math.cos(ry)*z
    y, z = math.cos(rx)*y-math.sin(rx)*z, math.sin(rx)*y+math.cos(rx)*z
    return x, y, z


def corners(bounds):
    return list(itertools.product(*zip(bounds[:3], bounds[3:])))


def bounds_of(points):
    return [min(p[a] for p in points) for a in range(3)] + [max(p[a] for p in points) for a in range(3)]


def body_bounds(model):
    if any("rotation" in e for e in model["elements"]):
        raise ValueError("Inventory fitting must explicitly account for any new element rotation")
    return bounds_of([p for e in model["elements"] for p in (e["from"], e["to"])])


def local_path(namespace, resource):
    if not resource.startswith(namespace+":"):
        raise ValueError("Inventory models must resolve entirely inside the original namespace")
    return "assets/"+namespace+"/models/"+resource.split(":", 1)[1]+".json"


def resolve(files, namespace, path, parents=()):
    if path in parents:
        raise ValueError("Cyclic inventory model inheritance")
    model = json.loads(files[path])
    result = resolve(files, namespace, local_path(namespace, model["parent"]), (*parents, path)) if "parent" in model else {}
    result = copy.deepcopy(result)
    for key, value in model.items():
        if key in ("display", "textures"):
            # Each display context replaces the parent's complete transform.
            result.setdefault(key, {}).update(value)
        elif key != "parent":
            result[key] = value
    return result


def attached_bounds(slot, media):
    """The same centred scale/rotation, edge alignment and mouth clip as MediaPose."""
    low, high = media["min"], media["max"]
    scale = slot["width"]/(high[0]-low[0])
    centre = [(low[a]+high[a])/2 for a in range(3)]
    sizes = (scale, scale, scale*slot["depth_scale"])
    rotated = [rotate([(p[a]-centre[a])*sizes[a] for a in range(3)], slot["rotation"])
               for p in corners(low+high)]
    edge = max(p[1] for p in rotated) if slot["align"] == "top" else min(p[1] for p in rotated)
    shift = [slot["base"][0], slot["base"][1]-edge, slot["base"][2]]
    visible = bounds_of([[p[a]+shift[a] for a in range(3)] for p in rotated])
    axis = "xyz".index(slot["clip"]["axis"])
    index = axis if slot["clip"]["keep"] == "above" else axis+3
    visible[index] = (max if index < 3 else min)(visible[index], slot["clip"]["value"])
    return visible if all(visible[a] < visible[a+3] for a in range(3)) else None


def envelopes(files, namespace, path, catalog):
    full = resolve(files, namespace, path)
    body = body_bounds(full)
    result = [("body", body)]
    name = path.rsplit("/", 1)[-1].removesuffix(".json")
    generic = name in ("handheld", "template_handheld")
    if not (generic or name.startswith("handheld_")) or name == "handheld_dock":
        return full, result
    platform = None if generic else name.removeprefix("handheld_")
    entries = {entry["platform"]: entry for entry in catalog["entries"]}
    if not generic and platform not in entries:
        return full, result
    compatible = {"gbc": ("gb",), "gba": ("gb", "gbc"), "ngpc": ("ngp",), "wsc": ("ws",)}
    media_names = list(entries) if generic else [platform, *compatible.get(platform, ())]
    slot = catalog["generic_media_slot"] if generic else entries[platform]["media_slot"]
    for media_name in media_names:
        visible = attached_bounds(slot, entries[media_name]["media_bounds"])
        if visible is not None:
            combined = [min(body[a], visible[a]) for a in range(3)] + [max(body[a+3], visible[a+3]) for a in range(3)]
            result.append(("inserted_"+media_name, combined))
    return full, result


def project(points, gui, translation=True):
    result = []
    for point in points:
        centred = [point[a]-8 for a in range(3)]
        right = rotate(centred, gui.get("right_rotation", [0, 0, 0]))
        scaled = [right[a]*gui.get("scale", [1, 1, 1])[a] for a in range(3)]
        rotated = rotate(scaled, gui.get("rotation", [0, 0, 0]))
        result.append([rotated[a]+(gui.get("translation", [0, 0, 0])[a] if translation else 0) for a in range(3)])
    return result


def item_paths(files, namespace):
    prefix = "assets/"+namespace+"/models/item/"
    return sorted(path for path in files if path.startswith(prefix) and path.endswith(".json"))


def fit(files, namespace):
    catalog = json.loads(files["assets/"+namespace+"/model_catalog.json"])
    transforms = {}
    for path in item_paths(files, namespace):
        full, cases = envelopes(files, namespace, path, catalog)
        gui = copy.deepcopy(full.get("display", {}).get("gui", {}))
        current = gui.get("scale", [1, 1, 1])
        points = [p for _, bounds in cases for p in corners(bounds)]
        body = bounds_of(project(corners(cases[0][1]), gui, False))
        centre = [(body[a]+body[a+3])/2 for a in (0, 1)]
        extent = bounds_of(project(points, gui, False))
        # Centre the empty physical item. Reserve symmetric space around that
        # fixed centre for every compatible real card, including a taller GB
        # card inside a GBA. Insertion must not move the empty icon downwards.
        radius = max(abs(extent[a]-centre[a]) for a in (0, 1))
        radius = max(radius, *(abs(extent[a+3]-centre[a]) for a in (0, 1)))
        factor = min(1, HALF_EXTENT/radius)
        gui["scale"] = [round(value*factor, 7) for value in current]
        body = bounds_of(project(corners(cases[0][1]), gui, False))
        gui["translation"] = [round(-(body[a]+body[a+3])/2, 7) for a in (0, 1)] + [gui.get("translation", [0, 0, 0])[2]]
        transforms[path] = gui
    # Resolve all models before writing any result: parent fitting cannot alter a child's input.
    for path, gui in transforms.items():
        model = json.loads(files[path])
        model.setdefault("display", {})["gui"] = gui
        files[path] = (json.dumps(model, ensure_ascii=False, indent=2)+"\n").encode("utf-8")


def audit(files, namespace):
    catalog = json.loads(files["assets/"+namespace+"/model_catalog.json"])
    result = []
    for path in item_paths(files, namespace):
        full, cases = envelopes(files, namespace, path, catalog)
        gui = full.get("display", {}).get("gui", {})
        projected = []
        for name, bounds in cases:
            extent = bounds_of(project(corners(bounds), gui))
            projected.append({"state": name, "bounds": bounds, "projected": extent})
        all_points = [p for _, bounds in cases for p in corners(bounds)]
        result.append({"model": path, "gui": gui, "centering": "empty_physical_item", "states": projected,
                       "body_projected": projected[0]["projected"],
                       "projected": bounds_of(project(all_points, gui))})
    return result


def check(files, namespace):
    checks = 0
    # Off-centre origin and both GUI rotations must stay in the real transform order.
    probe = {"rotation": [90, 0, 0], "translation": [2, 3, 0], "scale": [2, 2, 2]}
    if any(abs(a-b) > 1e-7 for a, b in zip(project([[9, 8, 9]], probe)[0], (4, 1, 0))):
        raise ValueError("Inventory projection no longer follows the actual GUI item transform")
    checks += 1
    rows = audit(files, namespace)
    required = {"handheld", "controller", "blank_cartridge", "cartridge", "handheld_dock", "cartridge_writer", "screen", "cartridge_case", "desk_console", "desk_console_arcade_2p"}
    if not required.issubset({row["model"].rsplit("/", 1)[-1][:-5] for row in rows}):
        raise ValueError("Inventory fitting omitted a registered accessory or generic model")
    checks += 1
    for row in rows:
        gui = row["gui"]
        if any(not math.isfinite(v) for values in gui.values() for v in values) or any(v <= 0 or v > 4 for v in gui.get("scale", [1, 1, 1])):
            raise ValueError("Invalid actual GUI transform: "+row["model"])
        extent = row["body_projected"]
        if any(abs(extent[a]+extent[a+3]) > 2e-5 for a in (0, 1)):
            raise ValueError("Empty physical inventory item is not centred: "+row["model"])
        checks += 2
        for state in row["states"]:
            extent = state["projected"]
            if any(extent[a] < -HALF_EXTENT-2e-5 or extent[a+3] > HALF_EXTENT+2e-5 for a in (0, 1)):
                raise ValueError("Inventory model or real inserted card crosses its safe border: "+row["model"]+"/"+state["state"])
            checks += 1
    return checks
