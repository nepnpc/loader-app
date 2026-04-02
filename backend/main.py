"""
Loader API — yt-dlp powered video/audio download proxy.
Deploy on Railway / Render / any Linux VPS.
"""
import os
import re
import asyncio
from concurrent.futures import ThreadPoolExecutor
from typing import Optional

import httpx
import yt_dlp
from fastapi import FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

app = FastAPI(title="Loader API", version="1.0.0", docs_url=None, redoc_url=None)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["GET"],
    allow_headers=["*"],
)

executor = ThreadPoolExecutor(max_workers=8)


# ─────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────

def sanitize(name: str, maxlen: int = 60) -> str:
    return re.sub(r"[^\w\s\-]", "", name).strip()[:maxlen]


def _ydl_opts_base() -> dict:
    return {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
    }


def _extract_info_sync(url: str) -> dict:
    with yt_dlp.YoutubeDL(_ydl_opts_base()) as ydl:
        return ydl.extract_info(url, download=False)


def _get_format_url_sync(url: str, format_id: str) -> dict:
    """Return direct stream URL + headers for a given format."""
    opts = _ydl_opts_base()
    opts["format"] = format_id
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)

    # Find exact format
    for f in info.get("formats", []):
        if f.get("format_id") == format_id:
            return {
                "url": f["url"],
                "headers": f.get("http_headers", {}),
                "ext": f.get("ext", "mp4"),
                "title": info.get("title", "download"),
                "filesize": f.get("filesize") or f.get("filesize_approx"),
            }

    # Fallback — use requested_downloads if yt-dlp merged formats
    for rd in info.get("requested_downloads", []):
        return {
            "url": rd.get("url", ""),
            "headers": rd.get("http_headers", {}),
            "ext": rd.get("ext", "mp4"),
            "title": info.get("title", "download"),
            "filesize": rd.get("filesize") or rd.get("filesize_approx"),
        }

    raise ValueError(f"Format {format_id} not found")


# ─────────────────────────────────────────────
# Routes
# ─────────────────────────────────────────────

@app.get("/health")
async def health():
    return {"status": "ok"}


@app.get("/info")
async def get_info(url: str = Query(..., description="Page URL of the video")):
    """Return video metadata + list of available formats."""
    loop = asyncio.get_event_loop()
    try:
        info = await loop.run_in_executor(executor, _extract_info_sync, url)
    except Exception as e:
        raise HTTPException(400, detail=str(e))

    seen: set = set()
    video_fmts, audio_fmts = [], []

    for f in info.get("formats", []):
        vcodec = f.get("vcodec", "none") or "none"
        acodec = f.get("acodec", "none") or "none"
        ext = f.get("ext", "") or ""
        fmt_id = f.get("format_id", "")

        has_v = vcodec != "none"
        has_a = acodec != "none"

        if has_v and has_a:
            # Combined stream — best for compatibility
            height = f.get("height") or 0
            if not height:
                continue
            key = f"V_{height}_{ext}"
            if key in seen:
                continue
            seen.add(key)
            fs = f.get("filesize") or f.get("filesize_approx")
            video_fmts.append({
                "format_id": fmt_id,
                "type": "video",
                "quality": height,
                "ext": ext,
                "label": f"{height}p  {ext.upper()}",
                "filesize": fs,
            })

        elif not has_v and has_a:
            abr = f.get("abr") or 0
            abr_int = int(abr) if abr else 0
            key = f"A_{abr_int}_{ext}"
            if key in seen:
                continue
            seen.add(key)
            fs = f.get("filesize") or f.get("filesize_approx")
            label = f"{abr_int} kbps  {ext.upper()}" if abr_int else f"Audio  {ext.upper()}"
            audio_fmts.append({
                "format_id": fmt_id,
                "type": "audio",
                "quality": abr_int,
                "ext": ext,
                "label": label,
                "filesize": fs,
            })

    # YouTube often only has separate video+audio streams. Add best combined.
    # If no combined streams found, add adaptive format strings for yt-dlp:
    if not video_fmts:
        for res in [2160, 1440, 1080, 720, 480, 360, 240]:
            video_fmts.append({
                "format_id": f"bestvideo[height<={res}]+bestaudio/best[height<={res}]",
                "type": "video",
                "quality": res,
                "ext": "mp4",
                "label": f"{res}p  MP4",
                "filesize": None,
            })

    videos = sorted(video_fmts, key=lambda x: x["quality"], reverse=True)[:6]
    audios = sorted(audio_fmts, key=lambda x: x["quality"], reverse=True)[:4]

    # Guarantee at least one audio option
    if not audios:
        audios = [{
            "format_id": "bestaudio/best",
            "type": "audio",
            "quality": 0,
            "ext": "m4a",
            "label": "Best Audio  M4A",
            "filesize": None,
        }]

    duration = info.get("duration", 0)

    return {
        "title": info.get("title", "Unknown"),
        "thumbnail": info.get("thumbnail", ""),
        "duration": duration or 0,
        "uploader": info.get("uploader", ""),
        "formats": videos + audios,
    }


@app.get("/download")
async def download(
    url: str = Query(...),
    format_id: str = Query(...),
):
    """
    Resolve format → get direct CDN URL via yt-dlp → proxy stream to client.
    No temp files. Starts immediately. Full speed.
    """
    loop = asyncio.get_event_loop()
    try:
        finfo = await loop.run_in_executor(executor, _get_format_url_sync, url, format_id)
    except Exception as e:
        raise HTTPException(400, detail=str(e))

    direct_url: str = finfo["url"]
    headers: dict = {k: v for k, v in finfo["headers"].items()}
    ext: str = finfo["ext"]
    title: str = sanitize(finfo["title"])
    filesize: Optional[int] = finfo.get("filesize")

    content_disp = f'attachment; filename="{title}.{ext}"'

    response_headers = {"Content-Disposition": content_disp}
    if filesize:
        response_headers["Content-Length"] = str(filesize)

    async def proxy_stream():
        async with httpx.AsyncClient(timeout=None, follow_redirects=True) as client:
            async with client.stream("GET", direct_url, headers=headers) as r:
                # Forward Content-Length if the CDN provides it
                async for chunk in r.aiter_bytes(65536):
                    yield chunk

    return StreamingResponse(
        proxy_stream(),
        media_type="application/octet-stream",
        headers=response_headers,
    )
