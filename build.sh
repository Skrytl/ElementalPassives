#!/usr/bin/env bash
# ==============================================================================
# ElementalPassives - Zero-IDE Standalone Build Script (Linux / macOS / Git Bash)
# ==============================================================================
# No IntelliJ, Eclipse, or local Maven installation required!
# Requirements: Java Development Kit (JDK 17 or 21) with javac and jar.
# ==============================================================================

set -e

echo "=================================================="
echo "    Building ElementalPassives Minecraft Plugin   "
echo "=================================================="

# Check for javac
if ! command -v javac &> /dev/null; then
    echo "❌ Error: 'javac' command not found."
    echo "Please ensure JDK 17+ or JDK 21+ is installed and on your PATH."
    exit 1
fi

# Check for jar
if ! command -v jar &> /dev/null; then
    echo "❌ Error: 'jar' command not found."
    echo "Please ensure JDK 17+ or JDK 21+ is installed and on your PATH."
    exit 1
fi

JAVA_VER=$(javac -version 2>&1)
echo "☕ Found Java compiler: $JAVA_VER"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$SCRIPT_DIR"
LIB_DIR="$WORK_DIR/.lib"
BIN_DIR="$WORK_DIR/bin"
OUTPUT_JAR="$WORK_DIR/ElementalPassives.jar"

mkdir -p "$LIB_DIR"
mkdir -p "$BIN_DIR"

PAPER_API_JAR="$LIB_DIR/paper-api-1.20.4-R0.1-SNAPSHOT.jar"
PAPER_REPO_URL="https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.20.4-R0.1-SNAPSHOT/paper-api-1.20.4-R0.1-20240416.143644-428.jar"

# Download Paper API jar if missing
if [ ! -f "$PAPER_API_JAR" ]; then
    echo "📦 Downloading Paper API library from PaperMC repo..."
    if command -v curl &> /dev/null; then
        curl -fSL "$PAPER_REPO_URL" -o "$PAPER_API_JAR"
    elif command -v wget &> /dev/null; then
        wget -q "$PAPER_REPO_URL" -O "$PAPER_API_JAR"
    else
        echo "❌ Error: Neither curl nor wget is available to download dependencies."
        exit 1
    fi
    echo "✅ Paper API downloaded successfully."
else
    echo "✅ Paper API already cached at $PAPER_API_JAR"
fi

# Clean bin directory
rm -rf "$BIN_DIR"/*

echo "🔨 Compiling Java sources..."
javac -encoding UTF-8 \
      -cp "$PAPER_API_JAR" \
      -d "$BIN_DIR" \
      "$WORK_DIR/src/main/java/com/elementalpassives/ElementalPassives.java"

echo "📋 Copying plugin.yml..."
cp "$WORK_DIR/src/main/resources/plugin.yml" "$BIN_DIR/plugin.yml"

echo "📦 Packaging into $OUTPUT_JAR..."
jar cvf "$OUTPUT_JAR" -C "$BIN_DIR" . > /dev/null

echo "=================================================="
echo "✨ SUCCESS! Plugin compiled cleanly!"
echo "📁 Output file: $OUTPUT_JAR"
echo "🎮 Drag and drop 'ElementalPassives.jar' into your server's /plugins/ folder!"
echo "=================================================="
