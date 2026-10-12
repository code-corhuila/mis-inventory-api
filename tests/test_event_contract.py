import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = json.loads((ROOT / "docs/stock-changed.schema.json").read_text(encoding="utf-8"))
ID = "11111111-1111-4111-8111-111111111111"
EVENT = dict(eventId=ID, eventType="inventory.stock.changed", schemaVersion=1, sequence=1,
             occurredAt="2026-10-11T12:00:00Z", traceId="event-test", movementId=ID,
             movementType="ENTRY", lotId=ID, medicationId=ID, supplierId=ID,
             expiresOn="2099-01-01", quantity=10, delta=10, onHandQuantity=10, entryId=ID, exitId=None)

class EventContractTest(unittest.TestCase):
    def setUp(self):
        self.validator = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
        self.event = copy.deepcopy(EVENT)

    def test_entry_and_exit_are_valid(self):
        self.validator.validate(self.event)
        self.event.update(movementType="EXIT", delta=-10, entryId=None, exitId=ID)
        self.validator.validate(self.event)

    def test_rejects_missing_or_extra_fields(self):
        for name in EVENT:
            with self.subTest(name=name):
                changed = dict(EVENT); del changed[name]
                with self.assertRaises(ValidationError): self.validator.validate(changed)
        self.event["authorization"] = "not-allowed"
        with self.assertRaises(ValidationError): self.validator.validate(self.event)

    def test_rejects_invalid_formats_and_bounds(self):
        for name, value in [("eventId", "invalid"), ("occurredAt", "yesterday"),
                            ("expiresOn", "2099-02-30"), ("traceId", "a/b"),
                            ("sequence", 0), ("quantity", 1000001), ("delta", 0),
                            ("onHandQuantity", -1), ("quantity", True)]:
            with self.subTest(name=name, value=value):
                changed = dict(EVENT); changed[name] = value
                with self.assertRaises(ValidationError): self.validator.validate(changed)

    def test_rejects_wrong_movement_origin_or_delta_sign(self):
        for patch in [dict(delta=-10), dict(entryId=None), dict(exitId=ID),
                      dict(movementType="EXIT", delta=10, entryId=None, exitId=ID)]:
            with self.subTest(patch=patch):
                changed = dict(EVENT); changed.update(patch)
                with self.assertRaises(ValidationError): self.validator.validate(changed)

if __name__ == "__main__":
    unittest.main()
