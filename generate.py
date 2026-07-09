#!/usr/bin/env python3
"""
PromptForge pack generator.

Cross-products Arcanum taxonomy layers:  Intent (goal) x Technique x Evasion
into a static payload pack ready for Burp Intruder (simple lists) plus a master
payloads.json carrying full metadata for the Burp extension to consume.

Usage:
    python3 generate.py                 # default evasion set
    python3 generate.py --all-evasions  # every text encoder
    python3 generate.py --all-templates # use every template per technique
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import encoders  # noqa: E402

DATA = os.path.join(HERE, "data")
DIST = os.path.join(HERE, "dist")

# Sensible default evasion set: plaintext + the highest-signal bypasses.
DEFAULT_EVASIONS = ["none", "base64", "l337speak", "truncated_words",
                    "invisible_unicode", "markdown", "json", "morse"]

DEFAULT_REFUSAL = "I can't help with that"


def load(name):
    with open(os.path.join(DATA, name), encoding="utf-8") as f:
        return json.load(f)


def slug(s):
    return "".join(c if c.isalnum() else "_" for c in s).strip("_").lower()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--all-evasions", action="store_true")
    ap.add_argument("--all-templates", action="store_true")
    args = ap.parse_args()

    intents = load("intents.json")["goals"]
    techniques = load("techniques.json")["techniques"]

    evasion_set = list(encoders.ENCODERS.keys()) if args.all_evasions else DEFAULT_EVASIONS

    payloads = []
    pid = 0
    for intent in intents:
        if intent["goal"].startswith("PLACEHOLDER"):
            continue  # skip 'custom' - engagement-specific
        for tech in techniques:
            # Use the authored {{GOAL}} templates (goal-aware). Default: first template per
            # technique; --all-templates uses all 5.
            tmpls = tech.get("templates") or []
            tmpls = tmpls if args.all_templates else tmpls[:1]
            bases = [t.replace("{{GOAL}}", intent["goal"]).replace("{{REFUSAL}}", DEFAULT_REFUSAL)
                     for t in tmpls if t and t.strip()]
            if not bases:
                bases = [f"Using the {tech['name']} technique, {intent['goal']}."]
            for base in bases:
              for ev in evasion_set:
                enc = encoders.ENCODERS[ev]
                try:
                    text = enc(base)
                except Exception as e:  # noqa: BLE001
                    text = base
                    print(f"  ! encoder {ev} failed on pid {pid}: {e}", file=sys.stderr)
                pid += 1
                payloads.append({
                    "id": f"pf-{pid:05d}",
                    "intent_goal": intent["id"],
                    "intent_category": intent["category"],
                    "technique": tech["id"],
                    "evasion": ev,
                    "success_indicator": intent["success"],
                    "payload": text,
                })

    os.makedirs(DIST, exist_ok=True)
    by_intent = os.path.join(DIST, "by_intent")
    by_tech = os.path.join(DIST, "by_technique")
    by_owasp = os.path.join(DIST, "intruder")
    for d in (by_intent, by_tech, by_owasp):
        os.makedirs(d, exist_ok=True)

    # master json
    with open(os.path.join(DIST, "payloads.json"), "w", encoding="utf-8") as f:
        json.dump({"count": len(payloads), "payloads": payloads}, f, indent=2, ensure_ascii=False)

    # Intruder simple lists, grouped two ways. One payload per line.
    def write_groups(folder, key):
        groups = {}
        for p in payloads:
            groups.setdefault(p[key], []).append(p["payload"])
        for g, lines in groups.items():
            with open(os.path.join(folder, f"{slug(g)}.txt"), "w", encoding="utf-8") as f:
                # collapse newlines so each payload stays one Intruder line
                f.write("\n".join(line.replace("\n", "\\n") for line in lines) + "\n")
        return len(groups)

    n_intent = write_groups(by_intent, "intent_category")
    n_tech = write_groups(by_tech, "technique")

    with open(os.path.join(by_owasp, "ALL.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(p["payload"].replace("\n", "\\n") for p in payloads) + "\n")

    print(f"Generated {len(payloads)} payloads")
    print(f"  {n_intent} intent-category lists  -> dist/by_intent/")
    print(f"  {n_tech} technique lists          -> dist/by_technique/")
    print(f"  master metadata                   -> dist/payloads.json")
    print(f"  combined Intruder list            -> dist/intruder/ALL.txt")


if __name__ == "__main__":
    main()
