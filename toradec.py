#!/usr/bin/python
import sys

def ideg_to_dec(d):
    return d / 324000 * 90

def ideg_to_ra(ra):
    ra = ra / 864000.0 * 360.0
    hour = int(ra / 15.0)
    remainder = ra  - hour * 15
    min = int(remainder * 2)
    second = remainder - min / 2
    return "%02dh%02dm%.5f" % (hour, min, second)

print("RA: ", ideg_to_ra(float(sys.argv[1])), ideg_to_dec(float(sys.argv[2])))
