#!/usr/bin/env python3
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Draws a fictitious CURP constancia for the demo (scripts/demo.py) and
writes it to stdout as a PNG. It is marked as a test document with fictitious
data. It runs in the MCP server's image, which has Pillow (from
agentic-mifos-sandbox):

    docker compose --profile ollama run --rm --no-deps -T --entrypoint python \\
      mcp-decision - <CURP> "<NAME>" < scripts/demo_constancia.py > curp.png

The identity workflow reads the CURP, the name, the certification by the civil
registry and the issue date (today, in UTC) from it.
"""

import datetime
import io
import sys

from PIL import Image, ImageDraw, ImageFont

MONTHS = ["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
          "noviembre", "diciembre"]


def constancia(curp: str, name: str, today: datetime.date) -> bytes:
    text, title = ImageFont.load_default(size=30), ImageFont.load_default(size=38)
    image = Image.new("RGB", (1400, 820), "white")
    draw = ImageDraw.Draw(image)
    lines = [
        (title, "CONSTANCIA DE LA CLAVE UNICA DE REGISTRO DE POBLACION"), (text, ""),
        (text, f"Clave: {curp}"), (text, f"Nombre: {name}"), (text, "Entidad de registro: CIUDAD DE MEXICO"), (text, ""),
        (text, "CURP Certificada: verificada con el Registro Civil"), (text, ""),
        (text, f"Ciudad de Mexico, a {today.day} de {MONTHS[today.month - 1]} de {today.year}"),
        (text, f"Fecha de emision: {today.isoformat()}"), (text, ""),
        (text, "DOCUMENTO DE PRUEBA - DATOS FICTICIOS - SANDBOX"),
    ]
    y = 50
    for font, line in lines:
        draw.text((60, y), line, fill="black", font=font)
        y += 60
    out = io.BytesIO()
    image.save(out, "PNG")
    return out.getvalue()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: demo_constancia.py <CURP> <NAME>  (writes the PNG to stdout)")
    # UTC, as the identity workflow dates its checks
    sys.stdout.buffer.write(constancia(sys.argv[1], sys.argv[2], datetime.datetime.now(datetime.UTC).date()))
