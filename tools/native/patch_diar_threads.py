"""Pinned Android-only thread budget extension; leaves model and streaming geometry unchanged."""
from pathlib import Path
import hashlib
import sys
p=Path(sys.argv[1]) / "src/asr/diar/diarizer.cpp"
expected="1536bc044bc17fcc4c7913f983c5314ebef6fd2df14649cef4024832424f2b82"
raw=p.read_bytes()
if b"VEXTO_NEMOTRON_THREADS" not in raw:
    if hashlib.sha256(raw).hexdigest()!=expected: raise ValueError("Unexpected pinned diarizer.cpp")
    text=raw.decode().replace('#include <stdexcept>', '#include <cstdlib>\n#include <stdexcept>')
    text=text.replace('    params.use_gpu = gpu >= 0;', '''    const char* threads = std::getenv("VEXTO_NEMOTRON_THREADS");
    if (threads && threads[0] >= '1' && threads[0] <= '4' && threads[1] == 0)
        params.n_threads = threads[0] - '0';
    params.use_gpu = gpu >= 0;''')
    p.write_bytes(text.encode())
