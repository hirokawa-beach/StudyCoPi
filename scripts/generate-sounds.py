"""Generate StudyCoPi's original, shared alarm presets (PCM WAV, no external assets)."""
from pathlib import Path
import math
import struct
import wave

ROOT = Path(__file__).resolve().parent.parent / 'shared' / 'sounds'
ROOT.mkdir(parents=True, exist_ok=True)
RATE = 22050
PRESETS = {
    'bell': [(0, 880, .8), (.9, 880, .8), (1.8, 880, .8)],
    'pulse': [(t, 740, .18) for t in (0, .25, .5, 1, 1.25, 1.5, 2, 2.25, 2.5)],
    'chime': [(0, 523.25, .7), (.6, 659.25, .7), (1.2, 783.99, .7), (1.8, 1046.5, .8)],
}
for name, notes in PRESETS.items():
    samples = []
    for index in range(RATE * 3):
        t = index / RATE
        value = 0.0
        for start, pitch, length in notes:
            age = t - start
            if 0 <= age < length:
                envelope = min(1, age / .012) * min(1, (length - age) / .03) * math.exp(-age * 2)
                value += envelope * (math.sin(2 * math.pi * pitch * age) + .22 * math.sin(2 * math.pi * pitch * 2 * age))
        samples.append(struct.pack('<h', round(max(-1, min(1, value * .38)) * 32767)))
    with wave.open(str(ROOT / f'{name}.wav'), 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(RATE)
        output.writeframes(b''.join(samples))
