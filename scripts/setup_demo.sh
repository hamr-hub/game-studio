#!/bin/bash

# This script copies the game demos to the Android assets folder for easier access
# Run this from the root of game-studio

mkdir -p app/src/main/assets/games

cp ../game-demo/*.zip app/src/main/assets/games/

echo "Demos copied to app/src/main/assets/games/"
