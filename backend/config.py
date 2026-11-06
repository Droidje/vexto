from dataclasses import dataclass
from hashlib import sha256
import json
import os
from pathlib import Path
import re
from typing import Mapping

WORKSPACE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
TOKEN = re.compile(r"[A-Za-z0-9._~+/=-]{24,256}")


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    workspace_by_token: Mapping[bytes, str]
    max_audio_bytes: int

    @classmethod
    def load(cls) -> "Settings":
        try:
            workspaces = json.loads(os.environ.get("VEXTO_WORKSPACES", "{}"))
            limit = int(os.environ.get("VEXTO_MAX_AUDIO_BYTES", 256 * 1024 * 1024))
        except ValueError as error:
            raise RuntimeError("Invalid VEXTO_WORKSPACES or VEXTO_MAX_AUDIO_BYTES") from error
        return cls.from_workspaces(Path(os.environ.get("VEXTO_DATA_DIR", "backend/data")), workspaces, limit)

    @classmethod
    def from_workspaces(
        cls, directory: Path, workspaces: dict[str, str], max_audio_bytes: int = 256 * 1024 * 1024
    ) -> "Settings":
        if not isinstance(workspaces, dict) or not workspaces:
            raise RuntimeError("Set VEXTO_WORKSPACES to a nonempty JSON object of workspace IDs to tokens")
        if not 1 <= max_audio_bytes <= 2 * 1024 * 1024 * 1024:
            raise RuntimeError("VEXTO_MAX_AUDIO_BYTES must be between 1 and 2147483648")
        lookup = {}
        for workspace, token in workspaces.items():
            if not isinstance(workspace, str) or not WORKSPACE.fullmatch(workspace):
                raise RuntimeError("Invalid workspace ID in VEXTO_WORKSPACES")
            if not isinstance(token, str) or not TOKEN.fullmatch(token):
                raise RuntimeError("Workspace tokens must have 24–256 bearer-safe characters")
            digest = sha256(token.encode("ascii")).digest()
            if digest in lookup:
                raise RuntimeError("Each workspace requires a distinct bearer token")
            lookup[digest] = workspace
        return cls(directory, lookup, max_audio_bytes)
