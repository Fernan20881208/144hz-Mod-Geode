#!/usr/bin/env python3
"""Apply the RMX5106 144 Hz patch to Geode Android Launcher 1.8.0."""

from __future__ import annotations

import os
import re
from pathlib import Path

ROOT = Path(os.environ.get("LAUNCHER_DIR", "launcher"))
TARGET_HZ = float(os.environ.get("TARGET_REFRESH_RATE", "144.0"))


def read(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"Required file not found: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def lines(*items: str) -> str:
    return "\n".join(items)


def replace_once(text: str, old: str, new: str, name: str) -> str:
    if old not in text:
        raise RuntimeError(f"Expected source block was not found: {name}")
    return text.replace(old, new, 1)


def patch_manifest_and_game_mode() -> None:
    manifest = ROOT / "app/src/main/AndroidManifest.xml"
    text = read(manifest)

    if 'android:name="android.game_mode_config"' not in text:
        application = re.search(r"(<application\b[^>]*>)", text, re.DOTALL)
        if not application:
            raise RuntimeError("<application> was not found in AndroidManifest.xml")

        metadata = lines(
            "",
            "        <meta-data",
            '            android:name="android.game_mode_config"',
            '            android:resource="@xml/game_mode_config" />',
        )
        text = text[: application.end()] + metadata + text[application.end() :]
        write(manifest, text)

    write(
        ROOT / "app/src/main/res/xml/game_mode_config.xml",
        lines(
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<game-mode-config xmlns:android="http://schemas.android.com/apk/res/android" />',
            "",
        ),
    )
    write(
        ROOT / "app/src/main/res/xml-v31/game_mode_config.xml",
        lines(
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<game-mode-config',
            '    xmlns:android="http://schemas.android.com/apk/res/android"',
            '    android:supportsPerformanceGameMode="true"',
            '    android:supportsBatteryGameMode="false" />',
            "",
        ),
    )
    write(
        ROOT / "app/src/main/res/xml-v33/game_mode_config.xml",
        lines(
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<game-mode-config',
            '    xmlns:android="http://schemas.android.com/apk/res/android"',
            '    android:supportsPerformanceGameMode="true"',
            '    android:supportsBatteryGameMode="false"',
            '    android:allowGameDownscaling="false"',
            '    android:allowGameFpsOverride="false" />',
            "",
        ),
    )


def patch_surface_view() -> None:
    path = ROOT / (
        "app/src/main/java/org/cocos2dx/lib/Cocos2dxGLSurfaceView.kt"
    )
    text = read(path)

    pattern = re.compile(
        r"    @RequiresApi\(Build\.VERSION_CODES\.R\)\n"
        r"    fun updateRefreshRate\(\) \{.*?\n"
        r"    \}\n\n"
        r"    override fun onPause",
        re.DOTALL,
    )

    replacement = lines(
        "    @RequiresApi(Build.VERSION_CODES.R)",
        "    fun updateRefreshRate() {",
        "        val modes = display.supportedModes?.toList().orEmpty()",
        "        val chosenDisplay = modes.minByOrNull {",
        f"            abs(it.refreshRate - {TARGET_HZ}f)",
        "        }?.takeIf {",
        f"            abs(it.refreshRate - {TARGET_HZ}f) < 1.0f",
        "        } ?: modes.maxByOrNull { it.refreshRate }",
        "",
        "        if (chosenDisplay == null) {",
        '            println("Force144Hz: no display mode was available")',
        "            return",
        "        }",
        "",
        "        val chosenRefreshRate = chosenDisplay.refreshRate",
        "        println(",
        '            "Force144Hz: modeId=${chosenDisplay.modeId} " +',
        '                "@ $chosenRefreshRate Hz"',
        "        )",
        "",
        "        val surface = holder.surface",
        "        if (surface.isValid) {",
        "            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {",
        "                surface.setFrameRate(",
        "                    chosenRefreshRate,",
        "                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,",
        "                    Surface.CHANGE_FRAME_RATE_ALWAYS,",
        "                )",
        "            } else {",
        "                surface.setFrameRate(",
        "                    chosenRefreshRate,",
        "                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,",
        "                )",
        "            }",
        "        }",
        "",
        "        if (isAttachedToWindow) {",
        "            val activity = context as Activity",
        "            val attributes = activity.window.attributes",
        "            attributes.preferredDisplayModeId = chosenDisplay.modeId",
        "            attributes.preferredRefreshRate = chosenRefreshRate",
        "            activity.window.attributes = attributes",
        "        }",
        "    }",
        "",
        "    override fun onPause",
    )

    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise RuntimeError("updateRefreshRate() could not be patched")
    write(path, text)


def patch_activity() -> None:
    path = ROOT / "app/src/main/java/com/geode/launcher/GeometryDashActivity.kt"
    text = read(path)

    text = replace_once(
        text,
        "                window.attributes.preferredRefreshRate = chosenRefreshRate",
        lines(
            "                val attributes = window.attributes",
            "                attributes.preferredDisplayModeId = chosenDisplay.modeId",
            "                attributes.preferredRefreshRate = chosenRefreshRate",
            "                window.attributes = attributes",
        ),
        "initial preferred refresh rate",
    )

    old_focus = lines(
        "    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {",
        "        super.onWindowFocusChanged(hasWindowFocus)",
        "        mHasWindowFocus = hasWindowFocus",
        "        if (hasWindowFocus && !mIsOnPause) {",
        "            resumeGame()",
        "        }",
        "",
        "        hideSystemUi()",
        "    }",
    )
    new_focus = lines(
        "    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {",
        "        super.onWindowFocusChanged(hasWindowFocus)",
        "        mHasWindowFocus = hasWindowFocus",
        "        if (hasWindowFocus && !mIsOnPause) {",
        "            resumeGame()",
        "        }",
        "",
        "        if (hasWindowFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {",
        "            mGLSurfaceView?.post { mGLSurfaceView?.updateRefreshRate() }",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 250L",
        "            )",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 750L",
        "            )",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 1500L",
        "            )",
        "        }",
        "",
        "        hideSystemUi()",
        "    }",
    )
    text = replace_once(text, old_focus, new_focus, "onWindowFocusChanged()")

    old_resume = lines(
        "    override fun onResume() {",
        "        super.onResume()",
        "        mIsOnPause = false",
        "        BaseRobTopActivity.isPaused = false",
        "        if (mHasWindowFocus && !this.mIsRunning) {",
        "            resumeGame()",
        "        }",
        "    }",
    )
    new_resume = lines(
        "    override fun onResume() {",
        "        super.onResume()",
        "        mIsOnPause = false",
        "        BaseRobTopActivity.isPaused = false",
        "        if (mHasWindowFocus && !this.mIsRunning) {",
        "            resumeGame()",
        "        }",
        "",
        "        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {",
        "            mGLSurfaceView?.post { mGLSurfaceView?.updateRefreshRate() }",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 500L",
        "            )",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 1500L",
        "            )",
        "            mGLSurfaceView?.postDelayed(",
        "                { mGLSurfaceView?.updateRefreshRate() }, 3000L",
        "            )",
        "        }",
        "    }",
    )
    text = replace_once(text, old_resume, new_resume, "onResume()")
    write(path, text)


def patch_version() -> None:
    path = ROOT / "app/build.gradle.kts"
    text = read(path)

    text, name_count = re.subn(
        r'versionName\s*=\s*"[^"]+"',
        'versionName = "1.8.0-144hz-rmx5106"',
        text,
        count=1,
    )
    text, code_count = re.subn(
        r"versionCode\s*=\s*\d+",
        "versionCode = 290144",
        text,
        count=1,
    )
    if name_count != 1 or code_count != 1:
        raise RuntimeError("versionName/versionCode could not be patched")
    write(path, text)


def verify() -> None:
    checks = {
        ROOT / "app/src/main/res/xml-v33/game_mode_config.xml": [
            'android:allowGameFpsOverride="false"',
            'android:allowGameDownscaling="false"',
        ],
        ROOT / "app/src/main/java/org/cocos2dx/lib/Cocos2dxGLSurfaceView.kt": [
            "Surface.CHANGE_FRAME_RATE_ALWAYS",
            "preferredDisplayModeId",
        ],
        ROOT / "app/src/main/java/com/geode/launcher/GeometryDashActivity.kt": [
            "preferredDisplayModeId",
            "postDelayed",
        ],
        ROOT / "app/build.gradle.kts": [
            'versionName = "1.8.0-144hz-rmx5106"',
        ],
    }

    for path, required in checks.items():
        text = read(path)
        for marker in required:
            if marker not in text:
                raise RuntimeError(f"Verification failed: {marker!r} missing from {path}")


if __name__ == "__main__":
    if not ROOT.is_dir():
        raise SystemExit(f"Launcher directory does not exist: {ROOT}")

    patch_manifest_and_game_mode()
    patch_surface_view()
    patch_activity()
    patch_version()
    verify()
    print(f"144 Hz patch applied successfully to {ROOT}")
