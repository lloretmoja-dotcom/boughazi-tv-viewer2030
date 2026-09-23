"""
Comprueba, uno por uno, si el enlace de vídeo (stream_url) de cada canal
de la tabla bt_channels responde de verdad.

- Los canales que fallan se marcan como "is_broken = true": desaparecen
  solos de la aplicación de la tele, PERO NUNCA se borran de la base de
  datos.
- Los canales que estaban marcados como caídos y ahora vuelven a
  responder, se reactivan solos ("is_broken = false"), sin que nadie
  tenga que hacerlo a mano desde el panel de administración.

Es la misma comprobación que ya hace el botón "Comprobar canales" del
panel de administración, pero hecha desde un ordenador de GitHub en vez
de desde el navegador — así no depende de que alguien lo pulse, no se
bloquea por CORS, y puede comprobar los más de 4000 canales en paralelo
en vez de uno detrás de otro.
"""

import asyncio
import os
import sys

import aiohttp

SUPABASE_URL = os.environ.get("SUPABASE_URL", "https://oansihwqjcfjackfhgbd.supabase.co").rstrip("/")
SUPABASE_ANON_KEY = os.environ.get(
    "SUPABASE_ANON_KEY", "sb_publishable_tn7CCQaV5otb7w5nKVdGgQ_uhOQCvwa"
)

CONCURRENCY = 50
REQUEST_TIMEOUT_SECONDS = 10
PAGE_SIZE = 1000


async def fetch_all_channels(session):
    """Trae TODOS los canales (estén caídos o no), en tandas de 1000,
    igual que hace la propia app de la tele."""
    channels = []
    offset = 0
    while True:
        url = f"{SUPABASE_URL}/rest/v1/bt_channels?select=id,name,stream_url,is_broken"
        headers = {
            "apikey": SUPABASE_ANON_KEY,
            "Authorization": f"Bearer {SUPABASE_ANON_KEY}",
            "Range-Unit": "items",
            "Range": f"{offset}-{offset + PAGE_SIZE - 1}",
        }
        async with session.get(url, headers=headers) as resp:
            resp.raise_for_status()
            batch = await resp.json()
        channels.extend(batch)
        if len(batch) < PAGE_SIZE:
            break
        offset += PAGE_SIZE
    return channels


async def check_one(session, sem, channel):
    """Devuelve True si el canal responde bien, False si no. Se hace un
    segundo intento antes de dar un canal por caído, por si ha sido solo
    un corte momentáneo de la red."""
    url = (channel.get("stream_url") or "").strip()
    if not url.startswith("http"):
        return False

    async def attempt():
        try:
            timeout = aiohttp.ClientTimeout(total=REQUEST_TIMEOUT_SECONDS)
            async with session.get(url, timeout=timeout, allow_redirects=True) as resp:
                return resp.ok
        except Exception:
            return False

    async with sem:
        if await attempt():
            return True
    async with sem:
        return await attempt()


async def apply_update(session, sem, channel_id, is_broken):
    url = f"{SUPABASE_URL}/rest/v1/bt_channels?id=eq.{channel_id}"
    headers = {
        "apikey": SUPABASE_ANON_KEY,
        "Authorization": f"Bearer {SUPABASE_ANON_KEY}",
        "Content-Type": "application/json",
        "Prefer": "return=minimal",
    }
    payload = {"is_broken": is_broken, "last_checked_at": _now_iso()}
    async with sem:
        async with session.patch(url, headers=headers, json=payload) as resp:
            if resp.status not in (200, 204):
                body = await resp.text()
                print(f"  ! No se pudo actualizar el canal {channel_id}: HTTP {resp.status} — {body}")


def _now_iso():
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).isoformat()


async def main():
    connector = aiohttp.TCPConnector(limit=CONCURRENCY)
    async with aiohttp.ClientSession(connector=connector) as session:
        print("Descargando la lista completa de canales…")
        channels = await fetch_all_channels(session)
        print(f"Se van a comprobar {len(channels)} canales…")

        sem = asyncio.Semaphore(CONCURRENCY)
        results = await asyncio.gather(*(check_one(session, sem, ch) for ch in channels))

        newly_hidden = []
        recovered = []
        for channel, is_alive in zip(channels, results):
            was_broken = bool(channel.get("is_broken"))
            if is_alive and was_broken:
                recovered.append(channel)
            elif not is_alive and not was_broken:
                newly_hidden.append(channel)

        print(f"\nCanales que dejan de funcionar y se ocultan ahora: {len(newly_hidden)}")
        for ch in newly_hidden:
            print(f"  - {ch.get('name')} ({ch['id']})")

        print(f"\nCanales que vuelven a funcionar y se reactivan: {len(recovered)}")
        for ch in recovered:
            print(f"  - {ch.get('name')} ({ch['id']})")

        # IMPORTANTE: se actualiza la fecha "última comprobación" de TODOS
        # los canales, no solo de los que cambian de estado. Así, en el
        # panel de administración se puede ver en cualquier momento cuándo
        # fue la última vez que el sistema comprobó todo de verdad —
        # antes no había ninguna prueba visible de que esto se hubiera
        # ejecutado, y con miles de canales casi siempre en verde, era
        # imposible distinguir "está bien" de "no se ha comprobado nunca".
        updates = [(ch["id"], not is_alive) for ch, is_alive in zip(channels, results)]
        if updates:
            await asyncio.gather(
                *(apply_update(session, sem, cid, broken) for cid, broken in updates)
            )

        total_broken_now = sum(1 for alive in results if not alive)
        print(f"\nTotal de canales comprobados: {len(channels)}")
        print(f"Total ahora mismo caídos (ocultos): {total_broken_now}")
        print(f"Total ahora mismo funcionando: {len(channels) - total_broken_now}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except Exception as e:
        print(f"Fallo al comprobar los canales: {e}")
        sys.exit(1)
