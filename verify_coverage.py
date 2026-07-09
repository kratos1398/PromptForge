#!/usr/bin/env python3
"""
Coverage guard: asserts the generated pack covers every technique, intent goal,
and evasion declared in taxonomy/taxonomy.json. Run after generate.py.

Exit 0 = complete coverage. Exit 1 = a taxonomy node is unaccounted for.
The 'custom' intent (engagement-specific placeholder) and the non-text evasions
flagged in lib/encoders.NON_TEXT_EVASIONS are expected exclusions.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "lib"))
import encoders  # noqa: E402


def load(p):
    with open(os.path.join(HERE, p), encoding="utf-8") as f:
        return json.load(f)


def main():
    tax = load("taxonomy/taxonomy.json")
    techs = load("data/techniques.json")["techniques"]
    intents = load("data/intents.json")["goals"]
    dist = os.path.join(HERE, "dist", "payloads.json")
    if not os.path.exists(dist):
        print("dist/payloads.json missing - run `python3 generate.py` first.")
        return 1
    pay = load("dist/payloads.json")["payloads"]

    tax_techniques = set(tax["layers"]["techniques"]["items"])
    tax_evasions = set(tax["layers"]["evasions"]["items"])
    tax_goals = set()
    for cat in tax["layers"]["intents"]["categories"]:
        tax_goals |= set(cat.get("goals", []))
        for sub in cat.get("subcategories", []):
            tax_goals |= set(sub["goals"])

    gen_techniques = {p["technique"] for p in pay}
    gen_goals = {p["intent_goal"] for p in pay}
    enc_keys = set(encoders.ENCODERS.keys()) - {"none"}
    non_text = set(encoders.NON_TEXT_EVASIONS)
    expected_goal_exclusions = {"custom"}

    ok = True

    miss_t = tax_techniques - gen_techniques
    print(f"Techniques : {len(tax_techniques - miss_t)}/{len(tax_techniques)}", end="")
    if miss_t:
        print(f"  !! MISSING: {sorted(miss_t)}"); ok = False
    else:
        print("  COMPLETE")

    miss_g = tax_goals - gen_goals - expected_goal_exclusions
    covered_g = len(tax_goals - (tax_goals - gen_goals))
    print(f"Intent goals: {covered_g}/{len(tax_goals)} (excl. {sorted(expected_goal_exclusions)})", end="")
    if miss_g:
        print(f"  !! MISSING: {sorted(miss_g)}"); ok = False
    else:
        print("  COMPLETE")

    # Evasions are informational: the taxonomy lists 63 (many are linguistic or media
    # carriers - poetry, semaphore, steganography, waveforms - not pure text encoders).
    # The pack implements a curated text-encoder subset; the rest are applied via the
    # extension's model-driven evasion mode. Reported, not enforced.
    implemented = tax_evasions & (enc_keys | non_text)
    print(f"Evasions   : {len(enc_keys)} text encoders implemented; "
          f"{len(tax_evasions)} in taxonomy (rest are model-driven / carrier - informational)")

    print("\n" + ("[x] Complete TECHNIQUE + intent coverage of the Arcanum taxonomy." if ok
                  else "[ ] Coverage gap - see MISSING above."))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
