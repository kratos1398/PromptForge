#!/usr/bin/env python3
"""
Imports the authoritative Arcanum PI Taxonomy (docs/data/taxonomy.json, v1.6.1+)
into PromptForge's data files, so the extension and pack stay in lockstep with
the published taxonomy.

- data/techniques.json : {id, name, why(description+ideas), examples[]} for every technique
- taxonomy/taxonomy.json : technique + evasion id lists synced (intents preserved)

Refresh the source first:
  curl -sSL https://raw.githubusercontent.com/Arcanum-Sec/arc_pi_taxonomy/main/docs/data/taxonomy.json \
    -o taxonomy/arcanum_source.json
then run: python3 import_taxonomy.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "taxonomy", "arcanum_source.json")


def main():
    src = json.load(open(SRC, encoding="utf-8"))

    techs = []
    for t in src["techniques"]:
        why = (t.get("description") or "").strip()
        ideas = [i.strip() for i in (t.get("ideas") or []) if i and i.strip()]
        if ideas:
            why += " Ideas: " + "; ".join(ideas[:5]) + "."
        examples = [e.strip() for e in (t.get("examples") or []) if e and e.strip()][:4]
        techs.append({
            "id": t["id"],
            "name": t.get("title", t["id"]),
            "why": why,
            "examples": examples,
        })

    with open(os.path.join(HERE, "data", "techniques.json"), "w", encoding="utf-8") as f:
        json.dump({
            "_comment": "Imported from the Arcanum Prompt Injection Taxonomy (docs/data/taxonomy.json). "
                        "Fields: id, name, why (description + key ideas), examples (canonical technique "
                        "examples used as model grounding). Regenerate with import_taxonomy.py.",
            "techniques": techs,
        }, f, indent=2, ensure_ascii=False)

    tax_path = os.path.join(HERE, "taxonomy", "taxonomy.json")
    tax = json.load(open(tax_path, encoding="utf-8"))
    tax["layers"]["techniques"]["items"] = [t["id"] for t in src["techniques"]]
    tax["layers"]["evasions"]["items"] = [e["id"] for e in src["evasions"]]
    tax["version_note"] = ("Techniques and evasions synced from the Arcanum PI Taxonomy "
                           "docs/data/taxonomy.json. Intents preserved from PromptForge's normalized set.")
    with open(tax_path, "w", encoding="utf-8") as f:
        json.dump(tax, f, indent=2, ensure_ascii=False)

    print(f"techniques: {len(techs)}")
    print(f"taxonomy technique items: {len(tax['layers']['techniques']['items'])}")
    print(f"taxonomy evasion items:   {len(tax['layers']['evasions']['items'])}")


if __name__ == "__main__":
    main()
