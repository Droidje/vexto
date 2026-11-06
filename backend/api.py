from hashlib import sha256
import os
from pathlib import Path
from tempfile import mkstemp
from typing import Annotated
from urllib.parse import quote
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.datastructures import Headers, MutableHeaders
from starlette.requests import ClientDisconnect

from .config import Settings
from .schemas import (
    AUDIO_TYPE, JSON_LIMIT, Metadata, Recording, RecordingList, Speaker, SpeakerCreate, SpeakerLink,
    SpeakerLinks, SpeakerList, SpeakerName, Transcript, TranscriptHistory,
)
from .storage import Conflict, Gone, Missing, PreconditionFailed, Store

LIMIT = Annotated[int, Query(ge=1, le=1000)]
OFFSET = Annotated[int, Query(ge=0, le=2_147_483_647)]
REVISION = Annotated[int | None, Query(ge=1, le=2_147_483_647)]
UPLOAD_REQUEST = {"requestBody": {
    "required": True,
    "content": {kind: {"schema": {"type": "string", "format": "binary"}}
                for kind in ("audio/*", "application/octet-stream")},
}}
REVISION_HEADER = "X-Transcript-Revision"


class LimitRequests:
    def __init__(self, app, audio_limit: int):
        self.app = app
        self.audio_limit = audio_limit

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or not scope["path"].startswith("/v1/"):
            await self.app(scope, receive, send)
            return
        limit = JSON_LIMIT
        if scope["method"] == "PUT" and scope["path"].endswith("/audio"):
            limit = self.audio_limit
        length = Headers(scope=scope).get("content-length")

        async def private_response(message):
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers["Cache-Control"] = "no-store"
                headers["X-Content-Type-Options"] = "nosniff"
            await send(message)

        if length is not None:
            if not length.isascii() or not length.isdecimal() or len(length) > 12:
                await JSONResponse({"detail": "Invalid Content-Length"}, status_code=400)(scope, receive, private_response)
                return
            if int(length) > limit:
                await JSONResponse({"detail": "Request too large"}, status_code=413)(scope, receive, private_response)
                return

        received = 0

        async def limited_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise HTTPException(413, "Request too large")
            return message

        await self.app(scope, limited_receive, private_response)


def sync_output(output):
    output.flush()
    os.fsync(output.fileno())


class AudioResponse(StreamingResponse):
    def __init__(self, source, details: dict):
        self.source = source
        filename = details["filename"] or details["id"] + ".audio"
        super().__init__(iter(lambda: source.read(64 * 1024), b""), media_type=details["mime_type"],
                         headers={"Content-Length": str(details["bytes"]),
                                  "Content-Disposition": "attachment; filename*=UTF-8''" + quote(filename, safe="")})

    async def __call__(self, scope, receive, send):
        try:
            await super().__call__(scope, receive, send)
        finally:
            self.source.close()


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.load()
    store = Store(settings.data_dir)
    app = FastAPI(title="Vexto API", version="1.0.0")
    app.add_middleware(LimitRequests, audio_limit=settings.max_audio_bytes)
    bearer = HTTPBearer(auto_error=False)

    def workspace(credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> str:
        if credentials and len(credentials.credentials) <= 256:
            digest = sha256(credentials.credentials.encode("utf-8")).digest()
            identity = settings.workspace_by_token.get(digest)
            if identity is not None:
                return identity
        raise HTTPException(401, "Invalid workspace token", headers={"WWW-Authenticate": "Bearer"})

    Workspace = Annotated[str, Depends(workspace)]

    @app.exception_handler(Missing)
    async def missing(_request: Request, error: Missing):
        return JSONResponse({"detail": str(error)}, status_code=404)

    @app.exception_handler(Conflict)
    async def conflict(_request: Request, error: Conflict):
        return JSONResponse({"detail": str(error)}, status_code=409)

    @app.exception_handler(Gone)
    async def gone(_request: Request, error: Gone):
        return JSONResponse({"detail": str(error)}, status_code=410)

    @app.exception_handler(PreconditionFailed)
    async def precondition(_request: Request, error: PreconditionFailed):
        return JSONResponse({"detail": str(error)}, status_code=412)

    @app.exception_handler(RequestValidationError)
    async def invalid(_request: Request, _error: RequestValidationError):
        # Default FastAPI errors can echo the submitted recording metadata or transcript.
        return JSONResponse({"detail": "Invalid request"}, status_code=422)

    @app.get("/healthz", tags=["Health"])
    def health():
        return {"status": "ok"}

    @app.put("/v1/recordings/{recording}/audio", tags=["Recordings"], openapi_extra=UPLOAD_REQUEST,
             responses={201: {"description": "Audio stored"}, 409: {"description": "Recording ID already used"}})
    async def upload(recording: UUID, request: Request, identity: Workspace):
        mime = request.headers.get("content-type", "").partition(";")[0].strip().lower()
        if not AUDIO_TYPE.fullmatch(mime):
            raise HTTPException(415, "Expected audio/* or application/octet-stream")
        if request.headers.get("content-encoding", "identity").lower() != "identity":
            raise HTTPException(415, "Audio must be sent without HTTP content encoding")
        descriptor, name = mkstemp(prefix="upload-", dir=store.staging)
        temp = Path(name)
        digest = sha256()
        size = 0
        try:
            with os.fdopen(descriptor, "wb") as output:
                async for chunk in request.stream():
                    if chunk:
                        size += len(chunk)
                        digest.update(chunk)
                        await run_in_threadpool(output.write, chunk)
                if size == 0:
                    raise HTTPException(422, "Empty audio")
                await run_in_threadpool(sync_output, output)
            created = await run_in_threadpool(
                store.publish_audio, identity, str(recording), temp, digest.hexdigest(), size, mime
            )
        except ClientDisconnect as error:
            raise HTTPException(400, "Upload was interrupted") from error
        finally:
            temp.unlink(missing_ok=True)
        return JSONResponse({"id": str(recording)}, status_code=201 if created else 200)

    @app.get("/v1/recordings", response_model=RecordingList, tags=["Recordings"])
    def recordings(identity: Workspace, limit: LIMIT = 1000, offset: OFFSET = 0):
        return {"recordings": store.recordings(identity, limit, offset)}

    @app.get("/v1/recordings/{recording}", response_model=Recording, tags=["Recordings"])
    def recording_details(recording: UUID, identity: Workspace):
        return store.recording(identity, str(recording))

    @app.put("/v1/recordings/{recording}/metadata", response_model=Recording, tags=["Recordings"])
    def metadata(recording: UUID, payload: Metadata, identity: Workspace):
        return store.set_metadata(identity, str(recording), payload)

    @app.delete("/v1/recordings/{recording}", status_code=204, tags=["Recordings"])
    def delete_recording(recording: UUID, identity: Workspace):
        store.delete_recording(identity, str(recording))
        return Response(status_code=204)

    @app.get("/v1/recordings/{recording}/audio", response_class=StreamingResponse, tags=["Recordings"],
             responses={200: {"content": {"application/octet-stream": {"schema": {
                 "type": "string", "format": "binary",
             }}}}})
    def download(recording: UUID, identity: Workspace):
        key = str(recording)
        try:
            source, details = store.open_audio(identity, key)
        except FileNotFoundError as error:
            store.recording(identity, key)
            raise HTTPException(503, "Stored audio is unavailable") from error
        return AudioResponse(source, details)

    @app.put("/v1/recordings/{recording}/transcript", tags=["Transcripts"],
             responses={412: {"description": "Different transcript already exists"}})
    @app.post("/v1/recordings/{recording}/transcript", tags=["Transcripts"],
              responses={412: {"description": "Different transcript already exists"}})
    def save_transcript(recording: UUID, payload: Transcript, identity: Workspace, request: Request, response: Response,
                        if_none_match: Annotated[str | None, Header()] = None):
        revision = store.set_transcript(identity, str(recording), payload,
                                        create_only=request.method == "POST" or if_none_match == "*")
        response.headers[REVISION_HEADER] = str(revision)
        return {"revision": revision}

    @app.get("/v1/recordings/{recording}/transcript", response_model=Transcript, tags=["Transcripts"])
    def transcript(recording: UUID, identity: Workspace, revision: REVISION = None):
        body, current = store.transcript(identity, str(recording), revision)
        return Response(body, media_type="application/json", headers={REVISION_HEADER: str(current)})

    @app.get("/v1/recordings/{recording}/transcript/revisions", response_model=TranscriptHistory, tags=["Transcripts"])
    def transcript_history(recording: UUID, identity: Workspace):
        return {"revisions": store.history(identity, str(recording))}

    @app.get("/v1/speakers", response_model=SpeakerList, tags=["Speakers"])
    def speakers(identity: Workspace, limit: LIMIT = 1000, offset: OFFSET = 0):
        return {"speakers": store.speakers(identity, limit, offset)}

    @app.post("/v1/speakers", response_model=Speaker, status_code=201, tags=["Speakers"])
    def create_speaker(payload: SpeakerCreate, identity: Workspace, response: Response):
        speaker, created = store.create_speaker(identity, payload.name, str(payload.id) if payload.id else None)
        if not created:
            response.status_code = 200
        return speaker

    @app.patch("/v1/speakers/{speaker}", response_model=Speaker, tags=["Speakers"])
    def rename_speaker(speaker: UUID, payload: SpeakerName, identity: Workspace):
        return store.rename_speaker(identity, str(speaker), payload.name)

    @app.delete("/v1/speakers/{speaker}", status_code=204, tags=["Speakers"])
    def delete_speaker(speaker: UUID, identity: Workspace):
        store.delete_speaker(identity, str(speaker))
        return Response(status_code=204)

    @app.get("/v1/recordings/{recording}/speaker-links", response_model=SpeakerLinks, tags=["Speakers"])
    def speaker_links(recording: UUID, identity: Workspace, response: Response, revision: REVISION = None):
        links, current = store.links(identity, str(recording), revision)
        if current is not None:
            response.headers[REVISION_HEADER] = str(current)
        return {"links": links}

    @app.put("/v1/recordings/{recording}/speaker-links/{local_id:path}", response_model=SpeakerLinks, tags=["Speakers"])
    def set_speaker_link(recording: UUID, local_id: str, payload: SpeakerLink, identity: Workspace,
                         expected_revision: Annotated[int | None, Header(alias=REVISION_HEADER, ge=1)] = None):
        if not local_id.strip() or len(local_id) > 128:
            raise HTTPException(422, "Invalid local speaker ID")
        speaker = str(payload.speaker_id) if payload.speaker_id else None
        return {"links": store.set_link(identity, str(recording), local_id, speaker, expected_revision)}

    return app
