#!/usr/bin/python
import csv
import time
from dataclasses import dataclass
from typing import List
import re

HYG_DIR = "../../src/HYG-Database"
MAX_MAGNITUDE = 13

GREEK_LETTERS = {
    'Alp': 'α',
    'Bet': 'β',
    'Gam': 'γ',
    'Del': 'δ',
    'Eps': 'ε',
    'Zet': 'ζ',
    'Eta': 'η',
    'The': 'θ',
    'Iot': 'ι',
    'Kap': 'κ',
}

@dataclass
class DeepSkyObject:
    typ: str  # 'Gxy', 'Star', etc
    ra: float
    dec: float
    mag: float
    names: List[str]


digits_re = re.compile('\d+')
spaces_re = re.compile(' +')

def parse_bayerflamsteed(org: str) -> str:
    # Remove the numeric designation
    s = digits_re.sub(' ', org).strip()
    if ' ' not in s:
        s = org
    s = spaces_re.sub(' ', s).strip()
    return s

def ra_to_degrees(h: float) -> float:
    """HYG database reprenets RA in [0, 24].
    Convert it to [0, 360]"""
    return h * 15


def read_hygfull_csv() -> List[DeepSkyObject]:
    dsos: List[DeepSkyObject] = []

    n = 0
    with open(f"{HYG_DIR}/hygfull.csv") as fd:
        r = csv.DictReader(fd)
        for line in r:
            mag_str = line["Mag"]
            if not mag_str:
                continue
            mag = float(mag_str)
            if mag > MAX_MAGNITUDE:
                continue

            names: List[str] = []
            if name := line["ProperName"]:
                names.append(name)
            if bfs := line["BayerFlamsteed"]:
                names.append(parse_bayerflamsteed(bfs))
            if hd := line["HD"]:
                names.append(f"hd{hd}")

            if not names:
                continue

            dsos.append(
                DeepSkyObject(
                    typ="Star",
                    ra=ra_to_degrees(float(line["RA"])),
                    dec=float(line["Dec"]),
                    mag=mag,
                    names=names,
                )
            )

    return dsos


def read_dso_csv() -> List[DeepSkyObject]:
    dsos: List[DeepSkyObject] = []

    with open(f"{HYG_DIR}/dso.csv") as fd:
        r = csv.DictReader(fd)
        for line in r:
            typ = line["type"]
            if not typ:
                continue

            names: List[str] = []
            if name := line["name"]:
                names.append(name)

            if cat1 := line["cat1"]:
                id1 = line["id1"]
                names.append(f"{cat1}{id1}")
            if cat2 := line["cat2"]:
                id2 = line["id2"]
                names.append(f"{cat2}{id2}")

            if not names:
                continue

            mag_str = line["mag"]
            if not mag_str:
                mag = 4 # pick an arbitrary value
            else:
                mag = float(mag_str)
                if mag > MAX_MAGNITUDE:
                    mag = MAX_MAGNITUDE

            dsos.append(
                DeepSkyObject(
                    typ=typ,
                    ra=ra_to_degrees(float(line["ra"])),
                    dec=float(line["dec"]),
                    mag=mag,
                    names=names,
                )
            )
        return dsos


def main():
    dsos = read_dso_csv()
    dsos += read_hygfull_csv()

    now = time.strftime("%Y%m%dT%H%M%S", time.gmtime())

    with open(f"./app/src/main/assets/wellknowndso_{now}.csv", "w") as fd:
        fd.write("typ,ra,dec,mag,names\n")
        for dso in dsos:
            names = "/".join(dso.names)
            fd.write(f"{dso.typ},{dso.ra},{dso.dec},{dso.mag},{names}\n")

main()
