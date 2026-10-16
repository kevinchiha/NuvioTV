#!/usr/bin/env python3
"""Print the install-time compile profiles (.dm) AGP built for one ABI.

Usage: list-install-profiles.py <output-metadata.json> <abi>

Prints one line per profile: "<minApi> <maxApi> <absolute .dm path>". Reads the API ranges
from AGP's output-metadata.json instead of trusting the baselineProfiles/0 and /1 folder
numbers, which AGP does not document. Prints nothing when the build made no profiles.
"""
import json
import os
import sys


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    metadata_path, abi = sys.argv[1], sys.argv[2]
    base = os.path.dirname(os.path.abspath(metadata_path))
    with open(metadata_path) as f:
        metadata = json.load(f)
    suffix = f"-{abi}-release.dm"
    for group in metadata.get("baselineProfiles") or []:
        for rel in group.get("baselineProfiles", []):
            if rel.endswith(suffix):
                path = os.path.join(base, rel)
                if os.path.isfile(path):
                    print(group["minApi"], group["maxApi"], path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
