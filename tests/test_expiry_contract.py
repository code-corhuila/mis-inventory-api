import unittest
from copy import deepcopy
from pathlib import Path
import yaml
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

contract = yaml.safe_load((Path(__file__).resolve().parents[1] / "docs/openapi.yaml").read_text(encoding="utf-8"))


class ExpiryContractTest(unittest.TestCase):
    def validate(self, name, body):
        schema = {"$ref": "#/components/schemas/" + name, "components": contract["components"]}
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(body)

    def test_evaluation_presence_and_state(self):
        uid = "11111111-1111-4111-8111-111111111111"
        instant = "2026-10-12T12:00:00Z"
        alert = dict(id=uid, lotId=uid, severity="EXPIRING", state="OPEN", expiresOn="2026-10-13",
                     onHandQuantity=10, evaluatedOn="2026-10-12", windowDays=30, evaluatedBy=uid,
                     firstDetectedAt=instant, lastEvaluatedAt=instant, resolvedAt=None)
        for body in [dict(lotId=uid, alertPresent=False, alert=None), dict(lotId=uid, alertPresent=True, alert=alert)]:
            self.validate("ExpiryEvaluation", body)
        resolved = deepcopy(alert)
        resolved.update(state="RESOLVED", resolvedAt=instant, onHandQuantity=0)
        self.validate("ExpiryAlertPage", dict(data=[resolved], meta=dict(page=1, limit=20, total=1)))
        for bad in [dict(lotId=uid, alertPresent=True, alert=None), dict(lotId=uid, alertPresent=False, alert=alert)]:
            with self.assertRaises(ValidationError):
                self.validate("ExpiryEvaluation", bad)
        with self.assertRaises(ValidationError):
            self.validate("ExpiryAlert", dict(alert, resolvedAt=instant))
