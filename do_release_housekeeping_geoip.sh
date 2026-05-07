#!/usr/bin/env sh

BASE=$(dirname "$0")

# Update geoip files from tor.
sh "$BASE/libs/build-geoip-jar.sh"

