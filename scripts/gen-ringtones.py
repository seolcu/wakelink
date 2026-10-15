#!/usr/bin/env python3
"""Synthesizes WakeLink's built-in alarm sounds.

Writes android/app/src/main/res/raw/ringtone_<name>.ogg (Ogg Vorbis, which
every supported Android version can play) for each name in
android/.../sounds/SoundRef.kt's PRESET_NAMES. Everything is generated from
plain sine math here, so there are no third-party samples or licenses
involved. Each clip is one loop of the pattern; the alarm plays it looping.

    scripts/gen-ringtones.py          # needs python3 and ffmpeg (libvorbis)
"""
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

RATE = 44100
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "res", "raw")


def silence(seconds):
    return [0.0] * int(seconds * RATE)


def tone(freq, seconds, partials=((1.0, 1.0),), decay=0.0, attack=0.004, release=0.01):
    """A note: sum of (multiple-of-freq, amplitude) partials, optional exponential decay."""
    n = int(seconds * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        s = 0.0
        for mult, amp in partials:
            # Higher partials die away faster, like a struck object.
            partial_decay = math.exp(-decay * mult * t) if decay else 1.0
            s += amp * partial_decay * math.sin(2 * math.pi * freq * mult * t)
        env = min(1.0, t / attack) if attack else 1.0
        env *= min(1.0, (seconds - t) / release) if release else 1.0
        out.append(s * env)
    return out


def sweep(f0, f1, seconds):
    n = int(seconds * RATE)
    out, phase = [], 0.0
    for i in range(n):
        t = i / RATE
        f = f0 + (f1 - f0) * (t / seconds)
        phase += 2 * math.pi * f / RATE
        env = min(1.0, t / 0.01, (seconds - t) / 0.02)
        out.append((math.sin(phase) + 0.25 * math.sin(3 * phase)) * env)
    return out


def mix(base, layer, at):
    start = int(at * RATE)
    if len(base) < start + len(layer):
        base.extend([0.0] * (start + len(layer) - len(base)))
    for i, v in enumerate(layer):
        base[start + i] += v
    return base


def note(name):
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    return 440.0 * 2 ** ((names[name[0]] + 12 * (int(name[1:]) - 4)) / 12)


BRIGHT = ((1.0, 1.0), (3.0, 0.3), (5.0, 0.12))  # softened square-ish beep


def beep():
    # Classic alarm clock: four quick beeps, then a breath.
    out = []
    for _ in range(4):
        out += tone(1760, 0.09, BRIGHT) + silence(0.06)
    return out + silence(0.45)


def digital():
    # Two-tone "di-dit di-dit" like a digital watch alarm.
    out = []
    for _ in range(4):
        out += tone(1320, 0.07, BRIGHT) + silence(0.03) + tone(1760, 0.07, BRIGHT) + silence(0.08)
    return out + silence(0.5)


CHIME = ((1.0, 1.0), (2.0, 0.35), (3.0, 0.15), (4.2, 0.08))


def chime():
    out = silence(2.6)
    for i, n in enumerate(["C5", "E5", "G5", "C6"]):
        mix(out, tone(note(n), 1.6, CHIME, decay=2.2, release=0.3), i * 0.32)
    return out[: int(2.6 * RATE)]


def rising():
    # Three upward sweeps, each a little higher: hard to sleep through.
    out = []
    for f0, f1 in ((600, 1100), (700, 1300), (800, 1500)):
        out += sweep(f0, f1, 0.45) + silence(0.12)
    return out + silence(0.5)


BELL = ((0.5, 0.5), (1.0, 1.0), (1.19, 0.6), (1.56, 0.45), (2.0, 0.4), (2.74, 0.25), (3.76, 0.12))


def bell():
    out = silence(3.2)
    mix(out, tone(note("A4"), 3.0, BELL, decay=0.9, release=0.4), 0.0)
    mix(out, tone(note("A4"), 1.6, BELL, decay=0.9, release=0.4), 1.6)
    return out[: int(3.2 * RATE)]


MARIMBA = ((1.0, 1.0), (3.93, 0.35), (9.2, 0.08))


def marimba():
    melody = ["G5", "E5", "C5", "E5", "G5", "C6", "G5", "E5"]
    out = silence(len(melody) * 0.22 + 0.5)
    for i, n in enumerate(melody):
        mix(out, tone(note(n), 0.6, MARIMBA, decay=6.0, release=0.1), i * 0.22)
    return out


SOUNDS = {
    "beep": beep,
    "digital": digital,
    "chime": chime,
    "rising": rising,
    "bell": bell,
    "marimba": marimba,
}


def normalize(samples, peak=0.89):  # about -1 dBFS
    top = max(abs(s) for s in samples) or 1.0
    return [s * peak / top for s in samples]


def write_ogg(name, samples):
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name + ".wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(s * 32767)) for s in samples))
        out_path = os.path.join(OUT_DIR, f"ringtone_{name}.ogg")
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav_path,
             "-c:a", "libvorbis", "-q:a", "4", "-map_metadata", "-1", out_path],
            check=True,
        )
        return out_path


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, make in SOUNDS.items():
        path = write_ogg(name, normalize(make()))
        print(f"{os.path.relpath(path)}  {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    sys.exit(main())
