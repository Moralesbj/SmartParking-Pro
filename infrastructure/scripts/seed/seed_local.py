"""
Local demo seed + role verification. DEVELOPMENT ONLY: refuses any host other than localhost/127.0.0.1.

Uses the public API (never the database) so every step also verifies the application:
  1. Logs in as the bootstrap SUPERADMIN (two-step verification is off by default: password only).
  2. Configures the branch: zone, spaces of the four categories, capacity per category (rates keep the database
     template values), monthly plans per category, camera and barrier.
  3. Creates one account per role (ADMIN, SUPERVISOR, OPERATOR, CUSTOMER) with generated passwords.
  4. Verifies what each role can and cannot do, including flow A and flow D (live ALPR + barrier).

Usage:
  BOOTSTRAP_EMAIL=... BOOTSTRAP_PASSWORD=... python seed_local.py http://localhost:5173 <output-credentials.json>
Optional SP_REUSE_CREDENTIALS=<credentials.json> sets the passwords per role ({"accounts": {"ADMIN": {"password": ...}, ...}}).
The credentials file must live OUTSIDE the repository. Generated passwords are for a throwaway local database only.
"""
from __future__ import annotations

import base64, hashlib, hmac, http.cookiejar, json, os, secrets, struct, sys, time, urllib.error, urllib.parse, urllib.request, uuid
from datetime import datetime, timedelta, timezone

BASE = sys.argv[1].rstrip("/")
OUT = sys.argv[2]
host = urllib.parse.urlparse(BASE).hostname
if host not in ("localhost", "127.0.0.1"):
    sys.exit("Solo se permite ejecutar contra localhost")
ORIGIN = BASE
results: list[tuple[bool, str]] = []
sys.stdout.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252


def save_credentials() -> None:
    """Written after every change so generated passwords are never lost if a later step fails."""
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(creds, f, ensure_ascii=False, indent=2)


def totp(secret: str, step: int) -> str:
    key = base64.b32decode(secret + "=" * (-len(secret) % 8))
    h = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 15
    return "%06d" % ((struct.unpack(">I", h[o:o + 4])[0] & 0x7FFFFFFF) % 1_000_000)


def now_step() -> int:
    return int(time.time()) // 30


class Client:
    def __init__(self, label: str):
        self.label = label
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf: dict | None = None
        self.scope: dict = {}

    def call(self, method: str, path: str, body=None):
        headers = {"Accept": "application/json", "Origin": ORIGIN}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if method != "GET":
            if self.csrf is None:
                self.csrf = self.call("GET", "/auth/csrf")[1]
            headers[self.csrf["headerName"]] = self.csrf["token"]
        headers.update(self.scope)
        req = urllib.request.Request(BASE + "/api/v1" + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        try:
            with self.opener.open(req) as r:
                t = r.read().decode("utf-8")
                return r.status, (json.loads(t) if t else None)
        except urllib.error.HTTPError as e:
            t = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(t) if t else None
            except json.JSONDecodeError:
                return e.code, {"detail": t}

    def login(self, email: str, password: str, mfa_secret: str | None = None):
        s, b = self.call("POST", "/auth/login", {"email": email, "password": password})
        if s != 200:
            raise SystemExit(f"[{self.label}] login falló: {s} {b}")
        if b.get("mfaRequired"):
            raise SystemExit(f"[{self.label}] el servidor pidió verificación en dos pasos; debe estar desactivada (MFA_ENABLED=false)")
        self.csrf = None  # session changed
        sc = b["scopes"][0]
        self.scope = {"X-Organization-Id": sc["organization_id"], "X-Branch-Id": sc["branch_id"]}
        return b


def check(label: str, cond: bool, detail=""):
    results.append((bool(cond), label))
    print(("  OK   " if cond else "  FAIL ") + label + ("" if cond else f"  -> {detail}"))


def expect(client: Client, label: str, method: str, path: str, body=None, status=200):
    s, b = client.call(method, path, body)
    check(f"[{client.label}] {label} → {status}", s == status, f"{s} {b}")
    return b


previous: dict = {}
if os.environ.get("SP_REUSE_CREDENTIALS") and os.path.exists(os.environ["SP_REUSE_CREDENTIALS"]):
    with open(os.environ["SP_REUSE_CREDENTIALS"], encoding="utf-8") as f:
        previous = json.load(f).get("accounts", {})


def password(role: str = "") -> str:
    return previous.get(role, {}).get("password") or "Sp-" + secrets.token_urlsafe(14)


creds: dict = {"generated_at": datetime.now(timezone.utc).isoformat(), "url": BASE, "warning": "Solo base local temporal. No reutilizar.", "accounts": {}}

# ---------------------------------------------------------------- SUPERADMIN
print("\n== SUPERADMIN (cuenta inicial)")
boot_email, boot_password = os.environ["BOOTSTRAP_EMAIL"], os.environ["BOOTSTRAP_PASSWORD"]
sa = Client("SUPERADMIN")
me = sa.login(boot_email, boot_password)
sa_account = {"email": boot_email, "password": boot_password, "role": "SUPERADMIN"}
s, existing = sa.call("GET", "/users")
if s == 200 and len(existing) > 1:
    sys.exit("Esta base ya fue sembrada. Reinicie el backend local para empezar limpio.")
check("[SUPERADMIN] ingresa solo con correo y contraseña", me.get("mfaAvailable") is False and not me["user"]["mfa_enabled"], me.get("mfaAvailable"))
expect(sa, "administra sin segundo factor", "GET", "/catalogs/customers")
s, b = sa.call("POST", "/auth/mfa/setup")
check("[SUPERADMIN] verificación en dos pasos desactivada en el servidor (404 MFA_DISABLED)", s == 404 and (b or {}).get("code") == "MFA_DISABLED", f"{s} {b}")
creds["accounts"]["SUPERADMIN"] = sa_account
save_credentials()

print("\n== Configuración de la sede")
def create(c, catalog, body):
    s, b = c.call("POST", f"/catalogs/{catalog}", body)
    check(f"[{c.label}] crear {catalog} {body.get('name') or body.get('code') or body.get('rule_type') or ''}".rstrip(), s == 200, f"{s} {b}")
    return b["id"] if s == 200 else None

zone = create(sa, "zones", {"name": "Nivel 1", "level": "1"})
spaces = {}
for code, typ, feature in [("C-01", "CAR", "STANDARD"), ("C-02", "CAR", "STANDARD"), ("C-03", "CAR", "STANDARD"), ("C-04", "CAR", "STANDARD"),
                           ("E-01", "CAR", "EV_CHARGING"), ("A-01", "CAR", "ACCESSIBLE"), ("M-01", "MOTORCYCLE", "STANDARD"), ("M-02", "MOTORCYCLE", "STANDARD"),
                           ("B-01", "BICYCLE", "STANDARD"), ("B-02", "BICYCLE", "STANDARD"), ("K-01", "CARGO", "STANDARD")]:
    spaces[code] = create(sa, "spaces", {"code": code, "zone_id": zone, "type": typ, "feature": feature, "status": "AVAILABLE"})
expect(sa, "categoría eliminada ELECTRIC (rechazada)", "POST", "/catalogs/spaces", {"code": "X-01", "type": "ELECTRIC", "status": "AVAILABLE"}, 422)

rates = expect(sa, "tarifas por vehículo de la sede", "GET", "/vehicle-rates") or []
by_type = {r["vehicle_type"]: r for r in rates}
check("[SUPERADMIN] exactamente 4 categorías", sorted(by_type) == ["BICYCLE", "CAR", "CARGO", "MOTORCYCLE"], sorted(by_type))
car_rate = by_type.get("CAR", {})
check("[SUPERADMIN] carro según la plantilla: $150/min, plena $32.000 cada 12 h, mensualidad $180.000",
      float(car_rate.get("minute_rate", 0)) == 150 and float(car_rate.get("full_rate", 0)) == 32000 and car_rate.get("block_minutes") == 720 and float(car_rate.get("monthly_fee", 0)) == 180000, car_rate)
# Demo capacity of this local branch; money values stay as the database template set them.
for typ, capacity in [("MOTORCYCLE", 20), ("CAR", 40), ("BICYCLE", 15), ("CARGO", 4)]:
    r = by_type[typ]
    expect(sa, f"capacidad {typ} = {capacity}", "PUT", f"/vehicle-rates/{typ}", {"minuteRate": r["minute_rate"], "fullRate": r["full_rate"], "blockMinutes": r["block_minutes"],
           "monthlyFee": r["monthly_fee"], "graceMinutes": r["grace_minutes"], "capacity": capacity, "active": True})
create(sa, "subscription-plans", {"name": "Mensualidad carro", "vehicle_type": "CAR", "duration_days": "30"})
create(sa, "subscription-plans", {"name": "Mensualidad moto", "vehicle_type": "MOTORCYCLE", "duration_days": "30"})
create(sa, "subscription-plans", {"name": "Nocturno carro", "vehicle_type": "CAR", "price": "120000", "duration_days": "30", "allowed_days": "1234567", "allowed_start": "19:00", "allowed_end": "07:00", "grace_days": "2"})
camera = create(sa, "devices", {"name": "Cámara entrada", "type": "CAMERA"})
barrier = create(sa, "devices", {"name": "Barrera entrada", "type": "BARRIER"})

print("\n== Cuentas por rol")
# Distinct local parts so no seeded account collides with the bootstrap e-mail.
for role, name, local in [("ADMIN", "Administradora de sede", "administrador"), ("SUPERVISOR", "Supervisor de turno", "supervisor"), ("OPERATOR", "Operador de caja", "operador"), ("CUSTOMER", "Cliente de prueba", "cliente")]:
    email = f"{local}@smartparking.local"
    pw = password(role)
    s, b = sa.call("POST", "/users", {"email": email, "fullName": name, "password": pw, "role": role})
    check(f"[SUPERADMIN] crear usuario {role}", s == 200, f"{s} {b}")
    creds["accounts"][role] = {"email": email, "password": pw, "role": role}
    save_credentials()

# ---------------------------------------------------------------- ADMIN
print("\n== ADMIN")
ad = Client("ADMIN")
ad.login(creds["accounts"]["ADMIN"]["email"], creds["accounts"]["ADMIN"]["password"])
expect(ad, "listar usuarios sin segundo factor", "GET", "/users")
expect(ad, "editar catálogos (zona)", "POST", "/catalogs/zones", {"name": "Nivel 2", "level": "2"})
expect(ad, "crear organización (solo SUPERADMIN)", "POST", "/organizations", {"name": "Otra"}, 403)
expect(ad, "ver integraciones", "GET", "/integrations")
hist = expect(ad, "historial de la tarifa de carro", "GET", "/vehicle-rates/CAR/history")
check("[ADMIN] historial con la versión inicial y el cambio de capacidad", hist is not None and [h["version"] for h in hist] == [2, 1], hist)

# ---------------------------------------------------------------- OPERATOR — flow A
print("\n== OPERATOR (flujo A completo)")
op = Client("OPERATOR")
op.login(creds["accounts"]["OPERATOR"]["email"], creds["accounts"]["OPERATOR"]["password"])
expect(op, "modificar tarifa (prohibido)", "PUT", "/vehicle-rates/CAR", {"minuteRate": 1, "fullRate": 1, "blockMinutes": 720, "monthlyFee": 1, "graceMinutes": 0, "capacity": 1, "active": True}, 403)
expect(op, "consultar tarifas", "GET", "/vehicle-rates")
expect(op, "ver reportes de ingresos (prohibido)", "GET", "/reports/revenue?startsAt=2026-01-01T00:00:00Z&endsAt=2026-02-01T00:00:00Z", status=403)
expect(op, "gestionar usuarios (prohibido)", "GET", "/users", status=403)
cash = expect(op, "abrir caja", "POST", "/cash-sessions/open", {"openingBalance": "100000"})
veh = expect(op, "registrar vehículo", "POST", "/catalogs/vehicles", {"plate": "ABC123", "country_code": "CO", "type": "CAR"})
ses = expect(op, "entrada", "POST", "/parking-sessions/check-in", {"vehicleId": veh["id"], "spaceId": spaces["C-01"], "idempotencyKey": str(uuid.uuid4())})
check("[OPERATOR] tiquete con código de 6 dígitos y QR", ses and len(ses.get("ticket_code") or "") == 6 and (ses.get("qr_payload") or "").startswith("SPK1:"), ses)
liq = expect(op, "liquidar (minuto o fracción)", "POST", f"/parking-sessions/{ses['id']}/quote")
due = float(liq["calculation"]["balanceDue"]) if liq else -1
check("[OPERATOR] estancia recién iniciada: como máximo 1 minuto ($150)", 0 <= due <= 150, liq and liq.get("calculation"))
paid_in_cash = 0
if due > 0:
    expect(op, "cobrar en efectivo", "POST", "/payments", {"orderId": liq["order"]["id"], "cashSessionId": cash["id"], "method": "CASH", "amount": str(int(due)), "idempotencyKey": str(uuid.uuid4())})
    paid_in_cash = int(due)
expect(op, "salida", "POST", f"/parking-sessions/{ses['id']}/check-out")
veh2 = expect(op, "registrar segundo vehículo", "POST", "/catalogs/vehicles", {"plate": "XYZ789", "country_code": "CO", "type": "CAR"})
ses2 = expect(op, "entrada (queda en sede)", "POST", "/parking-sessions/check-in", {"vehicleId": veh2["id"], "spaceId": spaces["C-02"], "idempotencyKey": str(uuid.uuid4())})
moto = expect(op, "registrar moto", "POST", "/catalogs/vehicles", {"plate": "KLM45D", "country_code": "CO", "type": "MOTORCYCLE"})
expect(op, "entrada moto en cupo de carro (rechazada)", "POST", "/parking-sessions/check-in", {"vehicleId": moto["id"], "spaceId": spaces["C-03"], "idempotencyKey": str(uuid.uuid4())}, 409)
expect(op, "entrada moto sin cupo asignado (capacidad por categoría)", "POST", "/parking-sessions/check-in", {"vehicleId": moto["id"], "idempotencyKey": str(uuid.uuid4())})
bike = expect(op, "registrar bicicleta con su serial", "POST", "/catalogs/vehicles", {"plate": "BICI-0042", "country_code": "CO", "type": "BICYCLE"})
bike_stay = expect(op, "entrada bicicleta", "POST", "/parking-sessions/check-in", {"vehicleId": bike["id"], "idempotencyKey": str(uuid.uuid4())})
for minutes, total in [(10, 1500), (213, 31950), (214, 32000), (721, 32150), (934, 64000), (1500, 73000), (10080, 448000)]:
    entry = datetime(2026, 5, 4, 13, 0, tzinfo=timezone.utc)
    sim = expect(op, f"simulador carro {minutes} min", "POST", "/pricing/quote", {"vehicleType": "CAR", "entryAt": entry.isoformat().replace("+00:00", "Z"),
                 "exitAt": (entry + timedelta(minutes=minutes)).isoformat().replace("+00:00", "Z")})
    check(f"[OPERATOR] tabla de la especificación: {minutes} min = ${total:,}".replace(",", "."), sim is not None and float(sim["totalAmount"]) == total, sim and sim.get("totalAmount"))
look = expect(op, "consultar tiquete por placa", "GET", "/parking-sessions/lookup?q=xyz-789")
check("[OPERATOR] consulta muestra placa, tipo, tarifa y estado", look is not None and look["plate"] == "XYZ789" and look["vehicleTypeLabel"] == "Carro"
      and float(look["calculation"]["minuteRate"]) == 150 and look["ticketStatus"] == "PENDING_PAYMENT", look)
by_code = expect(op, "consultar tiquete por código de 6 dígitos", "GET", f"/parking-sessions/lookup?q={ses2['ticket_code']}")
check("[OPERATOR] el código lleva a la misma estancia", by_code is not None and by_code["sessionId"] == ses2["id"], by_code and by_code.get("sessionId"))
by_qr = expect(op, "consultar tiquete por QR", "GET", "/parking-sessions/lookup?q=" + urllib.parse.quote(bike_stay["qr_payload"]))
check("[OPERATOR] el QR de la bicicleta lleva a su estancia", by_qr is not None and by_qr["vehicleTypeLabel"] == "Bicicleta", by_qr and by_qr.get("vehicleTypeLabel"))
expect(op, "descuento (requiere supervisión)", "POST", f"/parking-sessions/{ses2['id']}/discounts", {"amount": "100", "reason": "prueba", "idempotencyKey": str(uuid.uuid4())}, 403)

# flow D: live ALPR through ai-service, then barrier
print("\n== Lectura de placas y barrera (flujo D, servicio ALPR en vivo)")
read = expect(op, "lectura simulada de placa registrada", "POST", "/plate-recognitions/simulate", {"plate": "XYZ789", "deviceId": camera})
check("[OPERATOR] lectura por cámara simulada del servicio OpenCV", read and read.get("mode") == "SIMULATED_CAMERA", read)
check("[OPERATOR] placa leída = XYZ789 y aceptada por reglas", read and read.get("plate_candidate") == "XYZ789" and read.get("review_status") == "AUTO_ACCEPTED", read)
amb = expect(op, "lectura con placa tapada", "POST", "/plate-recognitions/simulate", {"plate": "ABC123", "occlude": True})
check("[OPERATOR] lectura ambigua queda en revisión humana", amb and amb.get("review_status") == "PENDING", amb)
expect(op, "abrir barrera desconectada (rechazado)", "POST", f"/devices/{barrier}/commands", {"command": "OPEN", "recognitionId": read["id"]}, 409)
expect(op, "señal de la barrera", "POST", f"/devices/{barrier}/heartbeat")
expect(op, "abrir con lectura pendiente (rechazado)", "POST", f"/devices/{barrier}/commands", {"command": "OPEN", "recognitionId": amb["id"]}, 409)
cmd = expect(op, "abrir barrera con lectura aceptada", "POST", f"/devices/{barrier}/commands", {"command": "OPEN", "recognitionId": read["id"]})
check("[OPERATOR] barrera simulada confirma sin accionar hardware", cmd and cmd.get("status") == "SIMULATED_ACK" and cmd.get("hardwareActivated") is False, cmd)
expect(op, "apertura manual (solo supervisor)", "POST", f"/devices/{barrier}/commands", {"command": "OPEN", "reason": "prueba"}, 403)

# ---------------------------------------------------------------- CUSTOMER
print("\n== CUSTOMER (portal)")
cu = Client("CUSTOMER")
cu.login(creds["accounts"]["CUSTOMER"]["email"], creds["accounts"]["CUSTOMER"]["password"])
expect(cu, "panel administrativo (prohibido)", "GET", "/catalogs/customers", status=403)
expect(cu, "estancias de la sede (prohibido)", "GET", "/parking-sessions", status=403)
expect(cu, "auditoría (prohibido)", "GET", "/audit-logs", status=403)
cveh = expect(cu, "registrar mi vehículo", "POST", "/portal/vehicles", {"plate": "QWE321", "type": "CAR"})
mine = expect(cu, "ver solo mis vehículos", "GET", "/portal/vehicles")
check("[CUSTOMER] ve exactamente su vehículo", mine is not None and [v["plate"] for v in mine] == ["QWE321"], mine)
start = (datetime.now(timezone.utc) + timedelta(days=1)).replace(hour=15, minute=0, second=0, microsecond=0)
end = start + timedelta(hours=2)
avail = expect(cu, "consultar disponibilidad", "GET", f"/reservations/availability?type=CAR&startsAt={start.isoformat().replace('+00:00', 'Z')}&endsAt={end.isoformat().replace('+00:00', 'Z')}")
target = next((s for s in (avail or []) if s["code"] == "C-04"), None)
res = expect(cu, "reservar cupo C-04 mañana", "POST", "/reservations", {"vehicleId": cveh["id"], "spaceId": target["id"] if target else spaces["C-04"],
             "startsAt": start.isoformat().replace("+00:00", "Z"), "endsAt": end.isoformat().replace("+00:00", "Z"), "idempotencyKey": str(uuid.uuid4())})
expect(cu, "ver mis reservas", "GET", "/portal/reservations")

# ---------------------------------------------------------------- SUPERVISOR
print("\n== SUPERVISOR")
su = Client("SUPERVISOR")
su.login(creds["accounts"]["SUPERVISOR"]["email"], creds["accounts"]["SUPERVISOR"]["password"])
today = datetime.now(timezone.utc)
rev = expect(su, "reporte de ingresos", "GET", f"/reports/revenue?startsAt={(today - timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')}&endsAt={(today + timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ')}")
expect(su, "auditoría", "GET", "/audit-logs")
expect(su, "alertas", "GET", "/anomaly-alerts")
expect(su, "gestionar usuarios (solo admin)", "GET", "/users", status=403)
expect(su, "descuento mayor que el saldo (rechazado)", "POST", f"/parking-sessions/{ses2['id']}/discounts", {"amount": "1000000", "reason": "Prueba de límite", "idempotencyKey": str(uuid.uuid4())}, 409)
expect(su, "modificar tarifa (solo administración)", "PUT", "/vehicle-rates/CAR", {"minuteRate": 1, "fullRate": 1, "blockMinutes": 720, "monthlyFee": 1, "graceMinutes": 0, "capacity": 1, "active": True}, 403)
expect(su, "apertura manual con motivo", "POST", f"/devices/{barrier}/commands", {"command": "OPEN", "reason": "Verificación manual de prueba"})
shift_start = datetime.now(timezone.utc).replace(second=0, microsecond=0) + timedelta(days=1)
op_id = op.call("GET", "/auth/me")[1]["user"]["id"]
expect(su, "programar turno del operador", "POST", "/operator-shifts", {"operatorId": op_id, "scheduledStart": shift_start.isoformat().replace("+00:00", "Z"), "scheduledEnd": (shift_start + timedelta(hours=8)).isoformat().replace("+00:00", "Z")})
expect(su, "turno solapado (rechazado)", "POST", "/operator-shifts", {"operatorId": op_id, "scheduledStart": (shift_start + timedelta(hours=2)).isoformat().replace("+00:00", "Z"), "scheduledEnd": (shift_start + timedelta(hours=6)).isoformat().replace("+00:00", "Z")}, 409)

print("\n== Cierre")
expect(op, "cerrar caja cuadrada", "POST", f"/cash-sessions/{cash['id']}/close", {"countedBalance": str(100000 + paid_in_cash)})
avail_now = expect(op, "disponibilidad de la sede", "GET", "/spaces/availability")
occupied = {t["type"]: t["occupied"] for t in (avail_now or {}).get("types", [])}
check("[OPERATOR] dentro: 1 carro (XYZ789), 1 moto y 1 bicicleta", occupied == {"MOTORCYCLE": 1, "CAR": 1, "BICYCLE": 1, "CARGO": 0}, occupied)
expect(sa, "auditoría completa visible para SUPERADMIN", "GET", "/audit-logs")
users = expect(sa, "usuarios de la organización", "GET", "/users") or []
expected = {a["email"]: role for role, a in creds["accounts"].items()}
check("[SUPERADMIN] existen exactamente las 5 cuentas, una por rol", {u["email"]: u["role"] for u in users} == expected, [(u["email"], u["role"]) for u in users])

ok = sum(1 for r in results if r[0])
print(f"\nResultado: {ok}/{len(results)} verificaciones correctas")
save_credentials()
print(f"Credenciales guardadas en {OUT}")
sys.exit(0 if ok == len(results) else 1)
