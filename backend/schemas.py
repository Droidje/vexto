from datetime import UTC
import re
from typing import Annotated
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator, model_validator

JSON_LIMIT = 8 * 1024 * 1024
MAX_MILLIS = 2_147_483_647
Millis = Annotated[int, Field(strict=True, ge=0, le=MAX_MILLIS)]
AUDIO_TYPE = re.compile(r"(?:audio/[a-z0-9][a-z0-9+._-]{0,119}|application/octet-stream)")


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


def valid_text(value: str) -> str:
    if not value.strip() or "\0" in value:
        raise ValueError("Expected nonempty text")
    value.encode("utf-8")
    return value


class Location(Input):
    latitude: float = Field(strict=True, ge=-90, le=90)
    longitude: float = Field(strict=True, ge=-180, le=180)


class Metadata(Input):
    title: str = Field(max_length=200)
    duration_ms: Millis
    recorded_at: AwareDatetime
    location: Location | None = None
    filename: str = Field(max_length=240)
    mime_type: str

    @field_validator("title")
    @classmethod
    def title_text(cls, value: str) -> str:
        return valid_text(value)

    @field_validator("filename")
    @classmethod
    def safe_filename(cls, value: str) -> str:
        if value in (".", "..") or "/" in value or "\\" in value or not value.isprintable():
            raise ValueError("Invalid filename")
        return valid_text(value)

    @field_validator("mime_type")
    @classmethod
    def valid_mime(cls, value: str) -> str:
        if not AUDIO_TYPE.fullmatch(value):
            raise ValueError("Expected audio/* or application/octet-stream")
        return value

    @field_validator("recorded_at", mode="before")
    @classmethod
    def iso_string(cls, value):
        if not isinstance(value, str):
            raise ValueError("Expected an ISO 8601 datetime string")
        return value

    @field_validator("recorded_at")
    @classmethod
    def utc_timestamp(cls, value):
        try:
            return value.astimezone(UTC)
        except OverflowError as error:
            raise ValueError("Invalid recording date") from error


class Recording(BaseModel):
    id: UUID
    title: str | None
    duration_ms: int | None
    recorded_at: AwareDatetime | None
    location: Location | None
    filename: str | None
    mime_type: str
    bytes: int
    has_transcript: bool


class RecordingList(BaseModel):
    recordings: list[Recording]


class TranscriptLine(Input):
    speaker_id: str = Field(max_length=128)
    start_ms: Millis
    end_ms: Millis
    text: str = Field(max_length=65536)

    @field_validator("speaker_id", "text")
    @classmethod
    def line_text(cls, value: str) -> str:
        return valid_text(value)

    @model_validator(mode="after")
    def time_range(self):
        if self.start_ms >= self.end_ms:
            raise ValueError("Expected a positive time range")
        return self


class Transcript(Input):
    transcript: list[TranscriptLine] = Field(max_length=20_000)


class SpeakerName(Input):
    name: str = Field(max_length=120)

    @field_validator("name")
    @classmethod
    def speaker_name(cls, value: str) -> str:
        value = valid_text(value).strip()
        if not value.isprintable():
            raise ValueError("Invalid speaker name")
        return value


class SpeakerCreate(SpeakerName):
    id: UUID | None = None


class Speaker(BaseModel):
    id: UUID
    name: str


class SpeakerList(BaseModel):
    speakers: list[Speaker]


class SpeakerLink(Input):
    speaker_id: UUID | None


class SpeakerLinks(BaseModel):
    links: dict[str, UUID]


class TranscriptRevision(BaseModel):
    revision: int
    created_at: AwareDatetime
    current: bool


class TranscriptHistory(BaseModel):
    revisions: list[TranscriptRevision]
