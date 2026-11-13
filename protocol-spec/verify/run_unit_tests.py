#!/usr/bin/env python3
"""Run the JVM unit tests without the Gradle test executor.

Workaround for this machine: `gradlew :app:testDebugUnitTest` fails in the
Gradle Test Executor with "Could not find or load main class <PATH-like string>"
(the host environment injects a malformed classpath into the worker JVM).
Compilation is unaffected. This script runs JUnitCore directly against the
already-compiled Kotlin classes, using jars from the local Gradle cache.

Prerequisites:
    ./gradlew.bat :app:assembleDebug :app:compileDebugUnitTestKotlin

Usage:
    python protocol-spec/verify/run_unit_tests.py
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE_CACHE = Path(os.environ.get("GRADLE_USER_HOME",
                                  Path.home() / ".gradle")) / "caches" / "modules-2" / "files-2.1"

JDK_CANDIDATES = [
    Path(os.environ.get("JAVA_HOME", "")),
    Path(r"C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"),
    Path(r"C:\Program Files\Java\jdk-17"),
]

CLASS_DIRS = [
    ROOT / "app" / "build" / "tmp" / "kotlin-classes" / "debug",
    ROOT / "app" / "build" / "tmp" / "kotlin-classes" / "debugUnitTest",
]

TEST_CLASSES = ["com.jblt230.ctrl.FrameCodecTest"]


def find_jar(group: str, artifact: str, version: str) -> Path:
    base = GRADLE_CACHE / group / artifact / version
    jars = sorted(base.rglob(f"{artifact}-{version}.jar")) if base.exists() else []
    if not jars:
        raise SystemExit(f"Missing dependency jar: {group}:{artifact}:{version}\n"
                         f"Run gradlew once with network access to populate the cache.")
    return jars[0]


def find_java() -> Path:
    for jdk in JDK_CANDIDATES:
        if not jdk or not jdk.exists():
            continue
        exe = jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.exists():
            return exe
    raise SystemExit("JDK 17 not found. Set JAVA_HOME to a JDK 17 installation.")


def main() -> int:
    missing = [str(p) for p in CLASS_DIRS if not p.is_dir()]
    if missing:
        raise SystemExit("Compiled classes not found:\n  " + "\n  ".join(missing) +
                         "\nRun: ./gradlew.bat :app:assembleDebug :app:compileDebugUnitTestKotlin")

    jars = [
        find_jar("junit", "junit", "4.13.2"),
        find_jar("org.hamcrest", "hamcrest-core", "1.3"),
        find_jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
    ]
    classpath = os.pathsep.join(str(p) for p in [*CLASS_DIRS, *jars])
    cmd = [str(find_java()), "-cp", classpath,
           "org.junit.runner.JUnitCore", *TEST_CLASSES]
    print("Running:", " ".join(cmd[:1] + ["-cp", "<classpath>"] + cmd[3:]))
    return subprocess.call(cmd)


if __name__ == "__main__":
    sys.exit(main())
