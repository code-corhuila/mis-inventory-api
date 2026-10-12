import json
from pathlib import Path
import sys
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import yaml
from jsonschema import Draft202012Validator, FormatChecker

contract = yaml.safe_load(Path("docs/openapi.yaml").read_text(encoding="utf-8"))
mode, lot = sys.argv[1:3]

def request(role, path, method="GET", body=None, status=200, direct=False):
    token = (Path("/credentials") / (role + ".jwt")).read_text(encoding="ascii").strip()
    headers = {"Authorization": "Bearer " + token, "X-Correlation-Id": "expiry-acceptance"}
    if not direct: headers["Origin"] = "http://localhost:4200"
    if body is not None:
        headers.update({"Content-Type": "application/json", "Idempotency-Key": "expiry-exit-" + lot})
    url = ("http://inventory:8080" if direct else "http://gateway:8080") + path
    try: response = urlopen(Request(url, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method), timeout=15)
    except HTTPError as error: response = error
    with response:
        result = json.loads(response.read())
        assert response.status == status, f"Unexpected HTTP {response.status} for {role}/{method}"
        assert response.headers["X-Correlation-Id"] == "expiry-acceptance"
        if not direct: assert response.headers["Access-Control-Allow-Origin"] == "http://localhost:4200"
    return result

def validate(name, body):
    schema = {"$ref": "#/components/schemas/" + name, "components": contract["components"]}
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(body)

if mode == "exit":
    body = {"lotId": lot, "quantity": 10, "reason": "Synthetic expiry acceptance"}
    first = request("INVENTORY", "/api/v1/exits", "POST", body, 201)
    replay = request("INVENTORY", "/api/v1/exits", "POST", body, 200)
    validate("Exit", first); assert replay == first and first["remainingQuantity"] == 0
else:
    for role in ("ADMIN", "INVENTORY", "SUPERVISOR"):
        result = request(role, "/api/v1/alerts/expiry?page=1&limit=20&state=RESOLVED&severity=EXPIRING&lotId=" + lot)
        validate("ExpiryAlertPage", result)
        assert result["meta"]["total"] == 1 and len(result["data"]) == 1
        alert = result["data"][0]
        assert alert["state"] == "RESOLVED" and alert["onHandQuantity"] == 0 and alert["id"] == sys.argv[3]
        empty = request(role, "/api/v1/alerts/expiry?page=2&limit=1&lotId=" + lot)
        validate("ExpiryAlertPage", empty); assert empty["data"] == [] and empty["meta"]["total"] == 1
        filtered = request(role, "/api/v1/alerts/expiry?page=1&limit=20&state=OPEN&lotId=" + lot)
        validate("ExpiryAlertPage", filtered); assert filtered["data"] == [] and filtered["meta"]["total"] == 0
    for role in ("CONSULTATION", "WORKER"):
        denied = request(role, "/api/v1/alerts/expiry", status=403); validate("Error", denied)
    for role in ("ADMIN", "INVENTORY"):
        request(role, "/api/v1/internal/alerts/expiry/evaluate", "POST", {"lotId": lot, "windowDays": 30}, 403, True)
    request("WORKER", "/api/v1/internal/alerts/expiry/evaluate", "POST", {"lotId": lot, "windowDays": 30}, 404)
    evaluation = request("WORKER", "/api/v1/internal/alerts/expiry/evaluate", "POST", {"lotId": lot, "windowDays": 30}, direct=True)
    validate("ExpiryEvaluation", evaluation); assert evaluation["alertPresent"] and evaluation["alert"]["id"] == sys.argv[3]
    assert evaluation["alert"]["resolvedAt"] == alert["resolvedAt"] and evaluation["alert"]["firstDetectedAt"] == alert["firstDetectedAt"]
print("PASS: signed expiry " + mode + "; real Inventory/Gateway HTTP and response contracts")
